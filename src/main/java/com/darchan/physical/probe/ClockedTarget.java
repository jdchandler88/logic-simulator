package com.darchan.physical.probe;

import com.darchan.diag.Snapshot;
import com.darchan.diag.run.DebugTarget;
import com.darchan.diag.trace.InstructionDecoder;
import com.darchan.diag.trace.RegisterSpec;
import com.darchan.diag.trace.TraceEvent;
import com.darchan.diag.trace.TraceLayout;
import com.darchan.diag.trace.TraceListener;
import com.darchan.physical.signal.IClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Makes a clocked gate-level circuit a {@link DebugTarget}, so the {@code com.darchan.diag} tools (Debugger,
 * lockstep comparison, snapshot checkpoints, traces) work on it the same way as on a behavioral CPU.
 *
 * <p><b>One step is one clock cycle</b>: the {@link Stimulus} drives the inputs for the cycle (with the clock low),
 * the circuit settles, then the clock rises and falls ({@link IClock#tick()} twice, each settling). The instruction
 * count is the number of clock cycles, and so is the cycle count unless {@link #setCyclesPerStep} charges more per
 * step. The clock must be low initially.
 *
 * <p>State is read through a {@link ProbeSet}: the {@link #snapshot()} holds every probe (see {@link ProbeSet} for
 * how Z and X are represented) followed by a decimal {@code CYCLES} field. Gate-level state cannot be written
 * back, so {@link #restore} is unsupported.
 *
 * <p>Tracing: with a listener attached, every step reports the state before the cycle as a {@link TraceEvent}
 * whose registers are the probes (or a chosen subset, {@link #setTraceRegisters}); a program counter and an
 * instruction (bytes + decoder) can be supplied by whatever sequences the circuit (a testbench "control unit") so
 * trace lines read like a CPU's. Without them the PC column is 0 and the disassembly is {@code CLK}.
 */
public final class ClockedTarget implements DebugTarget {

    /** Drives the circuit's inputs for one clock cycle. */
    @FunctionalInterface
    public interface Stimulus {
        /**
         * @param cycle the cycle about to execute, from 0. Called with the clock low; the target settles the
         *              simulator afterwards.
         */
        void apply(long cycle);
    }

    /** name of the cycle counter field in snapshots */
    public static final String CYCLES = "CYCLES";

    private static final InstructionDecoder CLOCK_DECODER = (address, bytes, length) -> "CLK";

    private final String machine;
    private final IClock clock;
    private final ProbeSet probes;
    private Stimulus stimulus = cycle -> { };
    private IntSupplier programCounter = () -> 0;
    private IntSupplier instructionBytes = () -> 0;
    private IntSupplier instructionLength = () -> 0;
    private InstructionDecoder decoder = CLOCK_DECODER;
    private List<RegisterSpec> traceRegisters;
    private IntSupplier cyclesPerStep = () -> 1;
    private int pcWidth = 16;
    private int maxInstructionLength = 1;

    private long cycles;
    private long steps;
    private TraceListener trace;
    private TraceEvent traceEvent;
    private Probe[] traceProbes;

    /**
     * @param machine {@link Snapshot#machine()} of this target's snapshots
     * @param clock   the clock driving the circuit (low initially)
     * @param probes  the state to expose
     */
    public ClockedTarget(String machine, IClock clock, ProbeSet probes) {
        this.machine = Objects.requireNonNull(machine);
        this.clock = Objects.requireNonNull(clock);
        this.probes = Objects.requireNonNull(probes);
        if (clock.isOn()) {
            throw new IllegalArgumentException("the clock must start low (a step is a rising then a falling edge)");
        }
        if (probes.contains(CYCLES)) {
            throw new IllegalArgumentException("a probe may not be called " + CYCLES);
        }
    }

    public void setStimulus(Stimulus stimulus) {
        this.stimulus = Objects.requireNonNull(stimulus);
    }

    /** where the "next instruction" is, for breakpoints and traces (default 0) */
    public void setProgramCounter(IntSupplier pc) {
        this.programCounter = Objects.requireNonNull(pc);
    }

    /**
     * Describes the instruction about to execute, for traces.
     *
     * @param packedBytes up to 4 bytes, little-endian (byte {@code i} in bits {@code 8i..8i+7})
     * @param length      number of valid bytes, 0..{@code maxLength}
     * @param maxLength   longest instruction, 1..4 (sizes the trace's bytes column)
     * @param decoder     turns the bytes into text
     */
    public void setInstruction(IntSupplier packedBytes, IntSupplier length, int maxLength, InstructionDecoder decoder) {
        if (maxLength < 1 || maxLength > 4) {
            throw new IllegalArgumentException("maxLength must be 1..4: " + maxLength);
        }
        this.maxInstructionLength = maxLength;
        this.instructionBytes = Objects.requireNonNull(packedBytes);
        this.instructionLength = Objects.requireNonNull(length);
        this.decoder = Objects.requireNonNull(decoder);
        resetTraceLayout();
    }

    /**
     * Chooses the trace's register columns; each name must be a probe of at most 32 bits. Use
     * {@link RegisterSpec#flags} to get a flag column. By default every probe of at most 32 bits is traced.
     */
    public void setTraceRegisters(List<RegisterSpec> registers) {
        for (RegisterSpec r : registers) {
            Probe p = probes.get(r.name());
            if (p.width() != r.width()) {
                throw new IllegalArgumentException("probe " + r.name() + " is " + p.width() + " bits, not " + r.width());
            }
        }
        this.traceRegisters = List.copyOf(registers);
        resetTraceLayout();
    }

    /**
     * How many cycles {@link #cycleCount()} advances per step, asked just before each step (before the stimulus).
     * The default is 1: a step is one clock cycle. A datapath that executes one instruction of a CPU per clock (as
     * in a co-simulation) can charge the CPU's architectural cycle count for the instruction about to execute, so
     * that cycle-granularity lockstep comparison against that CPU compares at the same instruction boundaries. The
     * instruction count is always the number of clock cycles.
     */
    public void setCyclesPerStep(IntSupplier cyclesPerStep) {
        this.cyclesPerStep = Objects.requireNonNull(cyclesPerStep);
    }

    /** PC column width in bits (default 16) */
    public void setPcWidth(int bits) {
        this.pcWidth = bits;
        resetTraceLayout();
    }

    public ProbeSet probes() {
        return probes;
    }

    public IClock clock() {
        return clock;
    }

    @Override
    public int step() {
        if (trace != null) {
            emitTrace();
        }
        int cost = cyclesPerStep.getAsInt();
        if (cost < 1) {
            throw new IllegalStateException("cycles per step must be at least 1: " + cost);
        }
        stimulus.apply(steps);
        probes.simulator().settle();
        clock.tick(); // rising edge
        clock.tick(); // falling edge
        steps++;
        cycles += cost;
        return cost;
    }

    private void resetTraceLayout() {
        traceEvent = null;
    }

    /** the layout of this target's trace lines */
    public TraceLayout traceLayout() {
        if (traceEvent == null) {
            List<RegisterSpec> specs = traceRegisters;
            if (specs == null) {
                specs = new ArrayList<>();
                for (Probe p : probes.probes()) {
                    if (p.width() <= 32) {
                        specs.add(RegisterSpec.of(p.name(), p.width()));
                    }
                }
            }
            traceProbes = specs.stream().map(r -> probes.get(r.name())).toArray(Probe[]::new);
            traceEvent = new TraceEvent(new TraceLayout(machine, pcWidth, maxInstructionLength, 14, specs), decoder);
        }
        return traceEvent.layout();
    }

    private void emitTrace() {
        traceLayout();
        TraceEvent e = traceEvent;
        e.begin(cycles, steps, programCounter.getAsInt(), false);
        e.setBytes(instructionBytes.getAsInt(), instructionLength.getAsInt());
        for (int i = 0; i < traceProbes.length; i++) {
            e.setRegister(i, (int) traceProbes[i].read().value());
        }
        trace.onInstruction(e);
    }

    @Override
    public long cycleCount() {
        return cycles;
    }

    /** the number of steps (clock cycles) executed */
    @Override
    public long instructionCount() {
        return steps;
    }

    @Override
    public int programCounter() {
        return programCounter.getAsInt();
    }

    @Override
    public void setTraceListener(TraceListener listener) {
        this.trace = listener;
    }

    /** every probe, then {@code CYCLES} */
    @Override
    public Snapshot snapshot() {
        return probes.addTo(Snapshot.builder(machine)).dec(CYCLES, cycles).build();
    }

    /** @throws UnsupportedOperationException always: flip-flop state cannot be written from outside */
    @Override
    public void restore(Snapshot snapshot) {
        throw new UnsupportedOperationException("gate-level state cannot be restored from a snapshot");
    }

    @Override
    public String toString() {
        return machine + " @ cycle " + cycles + ": " + probes;
    }
}
