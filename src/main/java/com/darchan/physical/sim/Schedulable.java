package com.darchan.physical.sim;

/**
 * A net whose pending value is committed by a {@link Simulator}. This is the simulator's service-provider
 * interface: signal implementations call {@link Simulator#scheduleCommit(Schedulable)} when they are written,
 * and the simulator calls back {@link #commit()} and {@link #fireEvent()} during the next delta cycle.
 * Circuit code never calls these methods directly.
 */
public interface Schedulable {

    /**
     * Update phase: make the most recently written pending value the current value.
     *
     * @return {@code true} if the current value changed (an <em>event</em>), so listeners must be notified
     */
    boolean commit();

    /**
     * Evaluation phase: notify listeners of the event committed in this delta cycle. Called only after every net
     * in the delta has been committed, so all listeners observe the same, consistent set of values.
     */
    void fireEvent();
}
