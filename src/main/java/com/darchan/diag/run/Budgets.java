package com.darchan.diag.run;

/**
 * Arithmetic for cycle and step budgets. Run-control APIs take "how many more" budgets such as
 * {@code run(Long.MAX_VALUE)} and turn them into absolute targets by adding them to a counter; plain {@code +} would
 * overflow to a negative target and end the run immediately. Every place that adds a budget to a counter uses
 * {@link #saturatedAdd} instead.
 */
public final class Budgets {

    private Budgets() {
    }

    /**
     * {@code counter + amount}, clamped to {@link Long#MAX_VALUE} on overflow and to {@link Long#MIN_VALUE} on
     * underflow, so "the counter plus a huge budget" means "no limit" rather than "already past the limit".
     */
    public static long saturatedAdd(long counter, long amount) {
        long sum = counter + amount;
        // overflow iff both operands have the same sign and the sum's sign differs
        if (((counter ^ sum) & (amount ^ sum)) < 0) {
            return counter < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return sum;
    }
}
