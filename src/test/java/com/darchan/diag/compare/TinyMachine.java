package com.darchan.diag.compare;

import com.darchan.diag.Snapshot;
import com.darchan.diag.run.DebugTarget;
import com.darchan.diag.trace.RegisterSpec;
import com.darchan.diag.trace.TraceEvent;
import com.darchan.diag.trace.TraceLayout;
import com.darchan.diag.trace.TraceListener;

import java.util.List;

/**
 * A deterministic test double for comparison tests: every step adds the PC to ACC and moves to the next PC.
 * Steps at even PCs take 3 cycles, odd ones 5. Faults can be injected at a given step (0-based).
 */
final class TinyMachine implements DebugTarget {

    static final TraceLayout LAYOUT = new TraceLayout("tiny", 8, 1, 6, List.of(RegisterSpec.of("ACC", 8)));

    private final String machine;
    int pc;
    int acc;
    long cycles;
    long steps;
    /** at this step, XOR the result into ACC with {@link #faultMask} */
    long faultAtStep = -1;
    int faultMask;
    /** at this step, throw */
    long throwAtStep = -1;
    /** extra cycles added to every step (a timing difference) */
    int extraCycles;
    private TraceListener trace;
    private final TraceEvent event = new TraceEvent(LAYOUT, (address, bytes, length) -> "ADD PC");

    TinyMachine() {
        this("tiny");
    }

    TinyMachine(String machine) {
        this.machine = machine;
    }

    @Override
    public int step() {
        if (trace != null) {
            event.begin(cycles, steps, pc, false);
            event.setBytes(pc, 1);
            event.setRegister(0, acc);
            trace.onInstruction(event);
        }
        if (steps == throwAtStep) {
            throw new IllegalStateException("boom at step " + steps);
        }
        acc = (acc + pc) & 0xFF;
        if (steps == faultAtStep) {
            acc ^= faultMask;
        }
        int n = (pc % 2 == 0 ? 3 : 5) + extraCycles;
        pc = (pc + 1) & 0xFF;
        cycles += n;
        steps++;
        return n;
    }

    @Override
    public long cycleCount() {
        return cycles;
    }

    @Override
    public long instructionCount() {
        return steps;
    }

    @Override
    public int programCounter() {
        return pc;
    }

    @Override
    public void setTraceListener(TraceListener listener) {
        trace = listener;
    }

    @Override
    public Snapshot snapshot() {
        return Snapshot.builder(machine).hex("PC", 8, pc).hex("ACC", 8, acc).dec("CYCLES", cycles)
                .dec("INSTRUCTIONS", steps).build();
    }

    @Override
    public void restore(Snapshot s) {
        pc = (int) s.value("PC");
        acc = (int) s.value("ACC");
        cycles = s.value("CYCLES");
        steps = s.value("INSTRUCTIONS");
    }
}
