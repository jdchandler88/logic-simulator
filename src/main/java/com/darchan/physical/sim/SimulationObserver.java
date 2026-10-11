package com.darchan.physical.sim;

/**
 * Hooks into a {@link Simulator}'s scheduling, for tools such as waveform writers. All methods are called on the
 * simulation thread while the simulator is running; they must not write signals or call {@link Simulator#settle()}.
 */
public interface SimulationObserver {

    /** After the evaluate phase of a delta cycle: the values committed in it are current. */
    default void deltaCompleted(Simulator sim) {
    }

    /**
     * Simulated time is about to advance from {@code from} to {@code to}; every delta cycle at {@code from} has run
     * and nothing at {@code to} has happened yet.
     */
    default void timeAdvancing(Simulator sim, long from, long to) {
    }

    /** A {@link Simulator#settle()} or {@link Simulator#runUntil(long)} call has finished normally. */
    default void runCompleted(Simulator sim) {
    }
}
