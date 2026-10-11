package com.darchan.physical.probe;

import com.darchan.diag.Field;
import com.darchan.diag.Snapshot;
import com.darchan.diag.run.DebugTarget;
import com.darchan.diag.trace.InstructionDecoder;
import com.darchan.diag.trace.RegisterSpec;
import com.darchan.diag.trace.TraceEvent;
import com.darchan.diag.trace.TraceLayout;
import com.darchan.diag.trace.TraceListener;
import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.IClock;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.LogicValue;
import com.darchan.physical.signal.SimpleSignal;
import com.darchan.physical.sim.StateElement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Makes a clocked gate-level circuit a {@link DebugTarget}, so the {@code com.darchan.diag} tools (Debugger,
 * lockstep comparison, snapshot checkpoints, traces) work on it the same way as on a behavioral CPU.
 *
 * <p><b>One step is one clock cycle</b>: the {@link Stimulus} drives the inputs for the cycle (with the clock low),
 * the circuit settles, then the clock rises and falls ({@link IClock#tick()} twice, each settling). The instruction
 * count is the number of clock cycles, and so is the cycle count unless {@link #setCyclesPerStep} charges more per
 * step. The clock must be low initially.
 *
 * <p><b>Snapshots</b> hold, in order:
 * <ol>
 *   <li>every probe of the {@link ProbeSet} (see there for how Z and X are represented): what is compared and
 *       traced;</li>
 *   <li>{@code state.<name>} for every storage element of the simulator ({@link StateElement}, discovered through
 *       {@link com.darchan.physical.sim.Simulator#stateElements()}), as a HEX field of its width with
 *       {@code .Z}/{@code .X} masks like a probe;</li>
 *   <li>{@code tb.<name>} for testbench state registered with {@link #addState} (e.g. a control unit's program
 *       counter kept in Java) and {@code in.<name>} for input signals registered with {@link #addInputs};</li>
 *   <li>decimal {@code CYCLES} and {@code INSTRUCTIONS} (steps).</li>
 * </ol>
 * <b>Restore</b> ({@link #restore}) checks the snapshot fits this circuit (same machine name, a field for every
 * storage element, testbench state and input, no unknown {@code state.*} field) before changing anything, then
 * forces every storage element and input, sets the testbench state and counters, and settles the combinational
 * logic. It then re-reads every probe and throws {@link IllegalStateException} if any differs from the snapshot:
 * that means the circuit holds state the target does not know about (a latch built from gates, an input the
 * stimulus does not drive every cycle and that was not registered), so the restore is reported instead of being
 * silently lossy. Because of this check {@link #isSnapshotComplete()} is {@code true}; the remaining condition for
 * rewinding (lockstep narrowing) is that the {@link Stimulus} is deterministic: a function of the cycle number and
 * of registered testbench state only.
 *
 * <p>Tracing: with a listener attached, every step reports the state before the cycle as a {@link TraceEvent}
 * whose registers are the probes (or a chosen subset, {@link #setTraceRegisters}); a program counter and an
 * instruction (bytes + decoder) can be supplied by whatever sequences the circuit (a testbench "control unit") so
 * trace lines read like a CPU's. Without them the PC column is 0 and the disassembly is {@code CLK}. Z and X bits
 * of the traced probes show as {@code z}, {@code x} or {@code ?} digits (see {@code TraceFormatter}).
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

    /** name of the step counter field in snapshots */
    public static final String INSTRUCTIONS = "INSTRUCTIONS";

    /** prefix of storage-element fields in snapshots */
    public static final String STATE_PREFIX = "state.";

    /** prefix of testbench-state fields in snapshots */
    public static final String TESTBENCH_PREFIX = "tb.";

    /** prefix of input fields in snapshots */
    public static final String INPUT_PREFIX = "in.";

    private record TestbenchState(String field, int width, LongSupplier read, LongConsumer write) {
    }

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
    private final Map<String, TestbenchState> testbenchState = new LinkedHashMap<>();
    private final Map<String, List<SimpleSignal>> inputs = new LinkedHashMap<>();

    /**
     * @param machine {@link Snapshot#machine()} of this target's snapshots
     * @param clock   the clock driving the circuit (low initially)
     * @param probes  the state to expose
     */
    public ClockedTarget(String machine, IClock clock, ProbeSet probes) {
        this.machine = Objects.requireNonNull(machine);
        this.clock = Objects.requireNonNull(clock);
        this.probes = Objects.requireNonNull(probes);
        if (clock.getValue() != LogicValue.ZERO) {
            throw new IllegalArgumentException("the clock must start low (a step is a rising then a falling edge): "
                    + clock.getValue());
        }
        for (Probe p : probes.probes()) {
            checkNotReserved(p.name(), "a probe");
        }
    }

    private static void checkNotReserved(String name, String what) {
        if (name.equals(CYCLES) || name.equals(INSTRUCTIONS) || name.startsWith(STATE_PREFIX)
                || name.startsWith(TESTBENCH_PREFIX) || name.startsWith(INPUT_PREFIX)) {
            throw new IllegalArgumentException(what + " may not be called " + name + " (" + CYCLES + ", "
                    + INSTRUCTIONS + " and names starting with " + STATE_PREFIX + ", " + TESTBENCH_PREFIX + " or "
                    + INPUT_PREFIX + " are snapshot fields of the target)");
        }
    }

    /**
     * Registers state the testbench keeps outside the circuit (e.g. the program counter of a control unit written
     * in Java), so snapshots hold it as field {@code tb.<name>} and {@link #restore} puts it back.
     *
     * @param width bits, 1..64
     */
    public void addState(String name, int width, LongSupplier read, LongConsumer write) {
        String field = TESTBENCH_PREFIX + name;
        Probe.checkName(field);
        if (testbenchState.containsKey(field)) {
            throw new IllegalArgumentException("testbench state " + name + " is already registered");
        }
        if (width < 1 || width > 64) {
            throw new IllegalArgumentException("width must be 1..64: " + width);
        }
        testbenchState.put(field, new TestbenchState(field, width, Objects.requireNonNull(read),
                Objects.requireNonNull(write)));
    }

    /**
     * Registers input signals driven by the testbench ({@link SimpleSignal}s), so snapshots hold their levels as field
     * {@code in.<name>} (with {@code .Z}/{@code .X} masks) and {@link #restore} drives them back. Needed for inputs
     * whose level after a step matters (probed, or not re-driven by the stimulus every cycle).
     *
     * @param bus 1..64 {@link SimpleSignal}s, bit 0 first
     */
    public void addInputs(String name, IBus bus) {
        String field = INPUT_PREFIX + name;
        new Probe(field, bus.getSignals()); // validates the name and width
        if (inputs.containsKey(field)) {
            throw new IllegalArgumentException("inputs " + name + " are already registered");
        }
        List<SimpleSignal> signals = new ArrayList<>();
        for (ISignal sig : bus.getSignals()) {
            if (!(sig instanceof SimpleSignal simple)) {
                throw new IllegalArgumentException("inputs must be SimpleSignals so they can be driven back: " + sig);
            }
            signals.add(simple);
        }
        inputs.put(field, List.copyOf(signals));
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
            traceEvent = new TraceEvent(new TraceLayout(machine, pcWidth, maxInstructionLength, 14, specs, true),
                    decoder);
        }
        return traceEvent.layout();
    }

    private void emitTrace() {
        traceLayout();
        TraceEvent e = traceEvent;
        e.begin(cycles, steps, programCounter.getAsInt(), false);
        e.setBytes(instructionBytes.getAsInt(), instructionLength.getAsInt());
        for (int i = 0; i < traceProbes.length; i++) {
            ProbeValue v = traceProbes[i].read();
            e.setRegister(i, (int) v.value(), (int) v.z(), (int) v.x());
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

    /** see the class comment for the fields */
    @Override
    public Snapshot snapshot() {
        for (Probe p : probes.probes()) {
            checkNotReserved(p.name(), "a probe"); // probes may be added after construction
        }
        Snapshot.Builder b = probes.addTo(Snapshot.builder(machine));
        for (StateElement e : probes.simulator().stateElements()) {
            addLevels(b, STATE_PREFIX + e.name(), e.width(), e::get);
        }
        for (TestbenchState t : testbenchState.values()) {
            b.hex(t.field(), t.width(), t.read().getAsLong());
        }
        for (Map.Entry<String, List<SimpleSignal>> in : inputs.entrySet()) {
            List<SimpleSignal> sigs = in.getValue();
            addLevels(b, in.getKey(), sigs.size(), bit -> sigs.get(bit).getValue());
        }
        return b.dec(CYCLES, cycles).dec(INSTRUCTIONS, steps).build();
    }

    private interface Levels {
        LogicValue get(int bit);
    }

    private static void addLevels(Snapshot.Builder b, String field, int width, Levels levels) {
        long ones = 0;
        long z = 0;
        long x = 0;
        for (int i = 0; i < width; i++) {
            switch (levels.get(i)) {
                case ONE -> ones |= 1L << i;
                case Z -> z |= 1L << i;
                case X -> x |= 1L << i;
                case ZERO -> {
                }
            }
        }
        b.hex(field, width, ones);
        if (z != 0) {
            b.hex(field + ".Z", width, z);
        }
        if (x != 0) {
            b.hex(field + ".X", width, x);
        }
    }

    /**
     * The levels of {@code field} (plus its masks) in {@code s}.
     *
     * @throws IllegalArgumentException if it is missing, has the wrong width or inconsistent masks
     */
    private static LogicValue[] levels(Snapshot s, String field, int width) {
        Field f = s.field(field).orElseThrow(() -> new IllegalArgumentException("snapshot \"" + s.label()
                + "\" has no field " + field + ": it is not from this circuit"));
        if (f.width() != width) {
            throw new IllegalArgumentException(field + " is " + f.width() + " bits in the snapshot but " + width
                    + " in the circuit");
        }
        long z = mask(s, field + ".Z", width);
        long x = mask(s, field + ".X", width);
        long ones = f.value();
        if ((ones & z) != 0 || (ones & x) != 0 || (z & x) != 0) {
            throw new IllegalArgumentException(field + " has overlapping value/Z/X masks in the snapshot");
        }
        LogicValue[] out = new LogicValue[width];
        for (int i = 0; i < width; i++) {
            long m = 1L << i;
            out[i] = (ones & m) != 0 ? LogicValue.ONE : (z & m) != 0 ? LogicValue.Z : (x & m) != 0 ? LogicValue.X
                    : LogicValue.ZERO;
        }
        return out;
    }

    private static long mask(Snapshot s, String field, int width) {
        Optional<Field> f = s.field(field);
        if (f.isPresent() && f.get().width() != width) {
            throw new IllegalArgumentException(field + " is " + f.get().width() + " bits, not " + width);
        }
        return f.map(Field::value).orElse(0L);
    }

    /**
     * Puts the circuit back into the state of {@code snapshot} (see the class comment). Everything is checked before
     * anything changes; the probe check afterwards throws {@link IllegalStateException} if the circuit holds state
     * this target does not know about (the circuit is then left as restored so far).
     *
     * @throws IllegalArgumentException if the snapshot is not from this circuit
     */
    @Override
    public void restore(Snapshot snapshot) {
        if (!machine.equals(snapshot.machine())) {
            throw new IllegalArgumentException("not a " + machine + " snapshot: " + snapshot.machine());
        }
        List<StateElement> elements = probes.simulator().stateElements();
        List<LogicValue[]> stateLevels = new ArrayList<>();
        Set<String> known = new HashSet<>();
        for (StateElement e : elements) {
            String field = STATE_PREFIX + e.name();
            stateLevels.add(levels(snapshot, field, e.width()));
            known.add(field);
            known.add(field + ".Z");
            known.add(field + ".X");
        }
        for (String name : snapshot.fieldNames()) {
            if (name.startsWith(STATE_PREFIX) && !known.contains(name)) {
                throw new IllegalArgumentException("snapshot field " + name + " is not a storage element of this"
                        + " circuit: it is not from this circuit");
            }
        }
        List<Long> tbValues = new ArrayList<>();
        for (TestbenchState t : testbenchState.values()) {
            Field f = snapshot.field(t.field()).orElseThrow(() -> new IllegalArgumentException(
                    "snapshot has no testbench state " + t.field()));
            tbValues.add(f.value());
        }
        List<LogicValue[]> inputLevels = new ArrayList<>();
        for (Map.Entry<String, List<SimpleSignal>> in : inputs.entrySet()) {
            inputLevels.add(levels(snapshot, in.getKey(), in.getValue().size()));
        }
        long newCycles = snapshot.value(CYCLES);
        long newSteps = snapshot.value(INSTRUCTIONS);
        if (newCycles < 0 || newSteps < 0) {
            throw new IllegalArgumentException("negative " + CYCLES + " or " + INSTRUCTIONS + " in the snapshot");
        }

        for (int i = 0; i < elements.size(); i++) {
            LogicValue[] levels = stateLevels.get(i);
            for (int bit = 0; bit < levels.length; bit++) {
                elements.get(i).force(bit, levels[bit]);
            }
        }
        int k = 0;
        for (List<SimpleSignal> sigs : inputs.values()) {
            LogicValue[] levels = inputLevels.get(k++);
            for (int bit = 0; bit < levels.length; bit++) {
                sigs.get(bit).setValue(levels[bit]);
            }
        }
        k = 0;
        for (TestbenchState t : testbenchState.values()) {
            t.write().accept(tbValues.get(k++));
        }
        probes.simulator().settle();
        cycles = newCycles;
        steps = newSteps;

        List<String> differences = new ArrayList<>();
        Snapshot now = probes.addTo(Snapshot.builder(machine)).build();
        for (Probe p : probes.probes()) {
            for (String name : List.of(p.name(), p.name() + ".Z", p.name() + ".X")) {
                Optional<Long> expected = snapshot.field(name).map(Field::value);
                Optional<Long> actual = now.field(name).map(Field::value);
                if (!expected.equals(actual)) {
                    differences.add(name + " expected=" + expected.map(Long::toHexString).orElse("<none>")
                            + " actual=" + actual.map(Long::toHexString).orElse("<none>"));
                }
            }
        }
        if (!differences.isEmpty()) {
            throw new IllegalStateException("restore is not lossless: after restoring every storage element, input"
                    + " and testbench state, probe(s) differ from the snapshot: " + String.join(", ", differences)
                    + ". The circuit holds state this target does not know about (e.g. a latch built from gates, or"
                    + " an input that is neither registered with addInputs nor re-driven every cycle).");
        }
    }

    /**
     * {@code true}: a snapshot holds every storage element, registered input and testbench state, and
     * {@link #restore} verifies the probes afterwards. Rewinding also needs a deterministic {@link Stimulus}.
     */
    @Override
    public boolean isSnapshotComplete() {
        return true;
    }

    @Override
    public String toString() {
        return machine + " @ cycle " + cycles + ": " + probes;
    }
}
