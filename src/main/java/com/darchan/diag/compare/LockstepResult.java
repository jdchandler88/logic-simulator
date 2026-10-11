package com.darchan.diag.compare;

import java.util.Optional;

/**
 * Outcome of a {@link Lockstep} run.
 *
 * @param steps       reference steps executed during this run
 * @param comparisons snapshot comparisons made during this run (including the initial one on the first run)
 * @param ended       why the run ended when it did not diverge ("step limit reached", "stop condition met")
 * @param divergence  the first divergence, or {@code null}
 */
public record LockstepResult(long steps, long comparisons, String ended, Divergence divergence) {

    public boolean diverged() {
        return divergence != null;
    }

    public Optional<Divergence> firstDivergence() {
        return Optional.ofNullable(divergence);
    }

    /** the divergence report, or a one-line summary when the models agreed */
    public String report() {
        if (divergence != null) {
            return divergence.report();
        }
        return "lockstep: no divergence in " + steps + " step(s), " + comparisons + " comparison(s); " + ended;
    }

    @Override
    public String toString() {
        return report();
    }
}
