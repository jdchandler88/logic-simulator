package com.darchan.diag;

/**
 * A model whose state can be captured as a {@link Snapshot} and put back. Implemented by CPU cores (and later by
 * gate-level circuits and other devices); lockstep comparison, snapshot files and the debugger all work through it.
 */
public interface Snapshottable {

    /** Captures the current state (registers, flags, counters; no memory unless the model owns it). */
    Snapshot snapshot();

    /**
     * Restores the state from {@code snapshot}.
     *
     * @throws IllegalArgumentException if the snapshot is from a different kind of machine or lacks a field
     */
    void restore(Snapshot snapshot);

    /**
     * Whether {@link #snapshot()} holds <em>everything</em> that influences the model's later behaviour, so that
     * {@code restore(snapshot())} followed by running is indistinguishable from never having stopped. That is what
     * rewinding (e.g. narrowing a lockstep divergence by restoring the last matching state and re-running) needs.
     *
     * <p>The default is {@code false}: be conservative. A CPU whose memory lives on an external bus is not complete
     * on its own (restore its memory too, e.g. with {@code diag.compare.StateSaver#withMemory}).
     */
    default boolean isSnapshotComplete() {
        return false;
    }
}
