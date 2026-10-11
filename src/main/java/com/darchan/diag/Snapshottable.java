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
}
