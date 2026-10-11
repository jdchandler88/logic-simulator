package com.darchan.diag.run;

import com.darchan.diag.Snapshottable;
import com.darchan.diag.trace.TraceListener;

/**
 * What a {@link Debugger} needs from a model: single-stepping, counters, the PC, state snapshots and a trace hook.
 * Implemented by CPU cores (8080 now, SM83 later) and meant to be implementable by a gate-level machine too, where
 * a "step" is one clocked instruction or one clock cycle.
 */
public interface DebugTarget extends Snapshottable {

    /**
     * Executes one instruction (or, while halted, idles for a few cycles).
     *
     * @return the cycles it took
     */
    int step();

    /** total cycles executed since reset */
    long cycleCount();

    /** total instructions executed since reset (idle cycles while halted do not count) */
    long instructionCount();

    /** address of the next instruction */
    int programCounter();

    /** true while the core is stopped waiting for an interrupt (8080 {@code HLT}) */
    default boolean isHalted() {
        return false;
    }

    /**
     * Installs the per-instruction trace listener, or removes it with {@code null}. With no listener the core must
     * not do any tracing work. A {@link Debugger} owns this slot once attached.
     */
    void setTraceListener(TraceListener listener);
}
