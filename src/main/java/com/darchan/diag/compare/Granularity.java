package com.darchan.diag.compare;

/**
 * How often a {@link Lockstep} compares the two models.
 *
 * <ul>
 *   <li>{@link Unit#STEPS}: after every {@code n} {@link com.darchan.diag.run.DebugTarget#step() steps} of each
 *       side. A step is one instruction for a CPU core (or one idle period while halted) and one clock cycle for a
 *       gate-level {@code ClockedTarget}. With {@code n = 1} a divergence is pinned to the exact instruction.</li>
 *   <li>{@link Unit#CYCLES}: at the first instruction boundary at or after every {@code n} cycles (counted from
 *       where each side started). Much cheaper for long runs; a divergence is first seen at the end of a window of
 *       {@code n} cycles. Two models whose instruction timings differ will (correctly) diverge at the first
 *       boundary.</li>
 * </ul>
 * With either unit coarser than every step, {@link Lockstep} narrows a divergence to the first diverging step of the
 * window when it can rewind or replay the models (see {@link Lockstep}), and otherwise reports the window as not
 * narrowed.
 *
 * @param unit  what {@code every} counts
 * @param every comparison interval, at least 1
 */
public record Granularity(Unit unit, long every) {

    public enum Unit {
        STEPS, CYCLES
    }

    public Granularity {
        if (unit == null) {
            throw new IllegalArgumentException("unit must not be null");
        }
        if (every < 1) {
            throw new IllegalArgumentException("interval must be at least 1: " + every);
        }
    }

    /** compare after every instruction (step) */
    public static Granularity everyStep() {
        return new Granularity(Unit.STEPS, 1);
    }

    /** alias of {@link #everyStep()} */
    public static Granularity everyInstruction() {
        return everyStep();
    }

    /** compare after every {@code n} instructions (steps) */
    public static Granularity everySteps(long n) {
        return new Granularity(Unit.STEPS, n);
    }

    /** compare at the first instruction boundary after every {@code n} cycles */
    public static Granularity everyCycles(long n) {
        return new Granularity(Unit.CYCLES, n);
    }

    @Override
    public String toString() {
        return "every " + every + (unit == Unit.STEPS ? " step(s)" : " cycle(s)");
    }
}
