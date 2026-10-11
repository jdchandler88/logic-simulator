package com.darchan.diag.compare;

import com.darchan.diag.run.StopReason;

/**
 * Outcome of {@link GoldenTrace#compare}.
 *
 * @param source            golden trace name
 * @param linesCompared     live lines that matched
 * @param complete          true if every golden line was matched
 * @param stop              why the live run stopped
 * @param mismatch          the first mismatching line, or {@code null}
 * @param nextExpectedLine  when not complete and no mismatch: the first golden line not reached, else {@code null}
 */
public record GoldenTraceResult(String source, long linesCompared, boolean complete, StopReason stop,
                                TraceMismatch mismatch, String nextExpectedLine) {

    /** no line differed (the golden trace may still have lines the run did not reach, see {@link #complete()}) */
    public boolean matched() {
        return mismatch == null;
    }

    public String report() {
        if (mismatch != null) {
            return mismatch.report() + "\n(live run stopped: " + stop + ")";
        }
        if (complete) {
            return "golden trace " + source + ": all " + linesCompared + " line(s) matched";
        }
        return "golden trace " + source + ": " + linesCompared + " line(s) matched, but the live run stopped ("
                + stop + ") before the golden trace ended; next expected line:\n  " + nextExpectedLine;
    }

    @Override
    public String toString() {
        return report();
    }
}
