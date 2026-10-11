package com.darchan.diag.compare;

import com.darchan.diag.SnapshotDiff;

import java.util.Objects;

/**
 * The result of narrowing a coarse {@link Lockstep} divergence (seen at a comparison every N steps or N cycles) to
 * the first step whose state differs. See {@link Lockstep} for how it is done.
 *
 * @param status      whether narrowing was needed and whether it succeeded
 * @param method      how the window was re-run ({@code null} unless {@link Status#NARROWED})
 * @param granularity the coarse comparison interval, e.g. {@code "every 50 step(s)"}
 * @param windowStart reference steps at the last matching comparison (the window is steps
 *                    {@code windowStart + 1 .. windowEnd})
 * @param windowEnd   reference steps when the difference was seen (or the step that threw)
 * @param step        the first diverging step (reference steps executed, so the culprit is instruction
 *                    {@code step - 1} counting from 0), or -1 unless {@link Status#NARROWED}
 * @param reference   the reference side right after that step ({@code null} unless narrowed)
 * @param candidate   the candidate side right after that step ({@code null} unless narrowed)
 * @param diff        the state differences after that step (expected = reference), or {@code null} if a side threw
 * @param failure     what a side threw during that step, or {@code null}
 * @param reason      why narrowing was not needed or failed; for a success, notes on attempts that failed first
 *                    (empty if none)
 */
public record Narrowing(Status status, Method method, String granularity, long windowStart, long windowEnd, long step,
                        Divergence.SideState reference, Divergence.SideState candidate, SnapshotDiff diff,
                        String failure, String reason) {

    public enum Status {
        /** the comparison was already per step (or the initial state differed): the divergence is exact */
        NOT_NEEDED,
        /** the first diverging step was found */
        NARROWED,
        /** the divergence is only known to lie in the window; {@link #reason()} says why */
        NOT_NARROWED
    }

    public enum Method {
        /** both sides were restored to their last matching state and the window re-run step by step */
        RESTORE("restore"),
        /** fresh, identically configured models were replayed from the start and compared step by step */
        REPLAY("replay");

        private final String text;

        Method(String text) {
            this.text = text;
        }

        /** {@code "restore"} or {@code "replay"} */
        public String text() {
            return text;
        }
    }

    public Narrowing {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reason, "reason");
        if (status == Status.NARROWED && (method == null || reference == null || candidate == null || step < 0
                || (diff == null) == (failure == null))) {
            throw new IllegalArgumentException("a narrowed divergence needs a method, both sides, a step and either a"
                    + " diff or a failure");
        }
    }

    static Narrowing notNeeded() {
        return new Narrowing(Status.NOT_NEEDED, null, "", -1, -1, -1, null, null, null, null,
                "the comparison was already exact");
    }

    static Narrowing notNarrowed(String granularity, long windowStart, long windowEnd, String reason) {
        return new Narrowing(Status.NOT_NARROWED, null, granularity, windowStart, windowEnd, -1, null, null, null, null,
                reason);
    }

    /** the window as {@code "41..50"} */
    public String window() {
        return (windowStart + 1) + ".." + windowEnd;
    }
}
