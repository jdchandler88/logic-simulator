package com.darchan.diag.trace;

/**
 * Receives one event per executed instruction, before the instruction executes. Attach one to a CPU with
 * {@code setTraceListener}; with none attached the CPU skips tracing entirely.
 *
 * <p><b>The event object is reused</b> by the CPU for the next instruction. A listener that keeps events must
 * {@link TraceEvent#copy() copy} them (or, like {@link TraceRingBuffer}, copy out the values it needs).
 */
@FunctionalInterface
public interface TraceListener {

    void onInstruction(TraceEvent event);
}
