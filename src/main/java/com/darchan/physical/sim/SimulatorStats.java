package com.darchan.physical.sim;

/**
 * Activity counters of a {@link Simulator} since it was created or {@link Simulator#resetStats() reset}. A
 * <em>run</em> is one {@link Simulator#settle()} or {@link Simulator#runUntil(long)} call (so one
 * {@code Clock.tick()}); an <em>event</em> is a net whose value actually changed in a delta cycle; an
 * <em>update</em> is a scheduled commit, whether or not it changed the value (updates minus events is wasted
 * work: writes of the value a net already had).
 *
 * <p>Min/max per run are over completed runs (a run that threw {@link OscillationException} still counts in the
 * totals). Use them to spot deep combinational paths (many deltas per settle) or glitchy logic (many events).
 *
 * @param runs                completed settle/runUntil calls
 * @param deltaCycles         delta cycles executed
 * @param minDeltasPerRun     fewest delta cycles in one run (0 when there were no runs)
 * @param maxDeltasPerRun     most delta cycles in one run
 * @param events              net value changes
 * @param minEventsPerRun     fewest events in one run
 * @param maxEventsPerRun     most events in one run
 * @param updates             scheduled commits
 * @param timeSteps           times simulated time advanced
 */
public record SimulatorStats(long runs, long deltaCycles, long minDeltasPerRun, long maxDeltasPerRun, long events,
                             long minEventsPerRun, long maxEventsPerRun, long updates, long timeSteps) {

    public double meanDeltasPerRun() {
        return runs == 0 ? 0 : (double) deltaCycles / runs;
    }

    public double meanEventsPerRun() {
        return runs == 0 ? 0 : (double) events / runs;
    }

    /** a few lines of text */
    public String report() {
        return String.format("simulator: %d run(s) (settle/runUntil), %d time step(s)%n"
                        + "  delta cycles: %d total, per run min %d / mean %.1f / max %d%n"
                        + "  events:       %d total, per run min %d / mean %.1f / max %d%n"
                        + "  updates:      %d (%d without a value change)",
                runs, timeSteps, deltaCycles, minDeltasPerRun, meanDeltasPerRun(), maxDeltasPerRun,
                events, minEventsPerRun, meanEventsPerRun(), maxEventsPerRun, updates, updates - events);
    }

    @Override
    public String toString() {
        return report();
    }
}
