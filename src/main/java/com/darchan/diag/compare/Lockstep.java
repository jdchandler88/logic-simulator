package com.darchan.diag.compare;

import com.darchan.diag.Field;
import com.darchan.diag.MemoryView;
import com.darchan.diag.Snapshot;
import com.darchan.diag.SnapshotDiff;
import com.darchan.diag.run.Budgets;
import com.darchan.diag.run.DebugTarget;
import com.darchan.diag.run.Debugger;
import com.darchan.diag.trace.TraceRingBuffer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Runs two models side by side and stops at the first point where their states differ: live-versus-live
 * comparison of a <em>reference</em> (trusted) and a <em>candidate</em> model.
 *
 * <p>Each side is a {@link Debugger} (which owns the target's trace slot and keeps a {@link TraceRingBuffer} of
 * recent instructions for the report). Both targets are stepped directly, so breakpoints and watchpoints on the
 * debuggers are not consulted; an exception from either side ends the run as a divergence.
 *
 * <p>At every comparison point (see {@link Granularity}) each side's {@link Snapshot} is taken, optionally
 * extended with memory regions ({@link Builder#compareMemory}), reduced to the chosen fields
 * ({@link Builder#compareOnly}) and diffed with {@link SnapshotDiff}, skipping ignored fields
 * ({@link Builder#ignore}). The initial state is compared before the first step.
 *
 * <pre>{@code
 * Lockstep ls = Lockstep.builder("golden", goldenDbg, "new", newDbg)
 *         .granularity(Granularity.everyStep())
 *         .ignore("CYCLES")
 *         .compareMemory("ram", golden::peek, candidate::peek, 0x2000, 0x400)
 *         .build();
 * LockstepResult r = ls.run(1_000_000);
 * if (r.diverged()) System.out.println(r.report());
 * }</pre>
 *
 * <p>Models of different machines (e.g. a gate-level datapath against the behavioral CPU) are compared with
 * {@link Builder#compareOnly}: both snapshots are then reduced to the named fields and treated as the same
 * machine.
 *
 * <h2>Narrowing a coarse divergence</h2>
 * With a granularity coarser than every step, a difference is first seen at the end of a <em>window</em> (the steps
 * since the last matching comparison). The lockstep then tries to find the first step of the window whose state
 * differs, and reports it in {@link Divergence#narrowing()} (the coarse comparison stays in the report too):
 * <ol>
 *   <li><b>Restore</b>: if both sides can be rewound, the lockstep saves both complete states at every matching
 *       comparison; on a divergence it restores the last saved pair and re-runs the window one step at a time,
 *       comparing after each step. A side can be rewound if a {@link StateSaver} was given
 *       ({@link Builder#restoreWith}) or its target's snapshot is complete
 *       ({@link com.darchan.diag.Snapshottable#isSnapshotComplete()}). After a successful narrowing the targets are
 *       left right after the first diverging step.</li>
 *   <li><b>Replay</b>: otherwise (or if the restored re-run does not reproduce the divergence), if a factory of
 *       fresh, identically configured set-ups was given ({@link Builder#replayWith}), a new pair is built and run
 *       from the start: with the same coarse comparisons up to the last matching one (which must match again), then
 *       one step at a time through the window. The original targets stay where the coarse comparison stopped.</li>
 *   <li>Otherwise the report says {@code NOT NARROWED}, gives the window and the reason.</li>
 * </ol>
 * <b>Determinism requirement</b>: both mechanisms re-execute steps and assume the same inputs give the same
 * results. Input stimulus (I/O devices, interrupts, a gate-level testbench) must be a function of the saved or
 * replayed state, not of wall-clock time or call order; anything a step changes outside the saved state (a console
 * buffer, a file) sees the re-executed steps again. A re-run that finds no difference is reported as not
 * reproduced, never guessed. The window is re-run with both sides taking equal numbers of steps, so with
 * cycle granularity and models whose instruction timings differ the per-step re-run may not reproduce a difference
 * that is only one of timing. While re-running, only each debugger's ring buffer is traced (other trace listeners
 * do not see instructions twice), and the ring buffer is first rolled back to the restored point.
 */
public final class Lockstep {

    /** default number of trace lines per side in a divergence report */
    public static final int DEFAULT_CONTEXT_LINES = 16;

    private record MemorySpec(String name, MemoryView reference, MemoryView candidate, int start, int length) {
        String describe() {
            return name + "[" + Integer.toHexString(start).toUpperCase() + "+" + length + "]";
        }
    }

    private final Side reference;
    private final Side candidate;
    private Granularity granularity;
    private final Set<String> ignored;
    private final List<String> only;
    private final List<MemorySpec> memory;
    private final int contextLines;
    private final Supplier<Builder> replay;
    private final boolean narrowingEnabled;

    private long comparisons;
    private long lastMatchingStep = -1;
    /** why states could not be saved for narrowing by restore, or null */
    private String saveProblem;
    private Divergence divergence;

    private static final class Side {
        final String name;
        final Debugger debugger;
        final DebugTarget target;
        final Supplier<Snapshot> snapshot;
        /** saves/restores the complete state for narrowing, or null if this side cannot be rewound */
        final StateSaver saver;
        /** in cycle mode: the cycle count that ends the current comparison window */
        long boundary;
        /** steps completed since the lockstep was built */
        long steps;
        /** complete state at the last matching comparison (narrowing by restore) */
        Snapshot saved;
        /** the ring buffer and its event count at the last matching comparison */
        TraceRingBuffer savedRing;
        long savedRingMark;

        Side(String name, Debugger debugger, Supplier<Snapshot> snapshot, StateSaver saver) {
            this.name = name;
            this.debugger = debugger;
            this.target = debugger.target();
            this.snapshot = snapshot != null ? snapshot : target::snapshot;
            this.saver = saver != null ? saver : target.isSnapshotComplete() ? StateSaver.of(target) : null;
        }

        String quoted(String role) {
            return role + " \"" + name + "\"";
        }
    }

    private Lockstep(Builder b, boolean narrowingEnabled) {
        this.reference = new Side(b.referenceName, b.reference, b.referenceSnapshot, b.referenceSaver);
        this.candidate = new Side(b.candidateName, b.candidate, b.candidateSnapshot, b.candidateSaver);
        this.granularity = b.granularity;
        this.ignored = Set.copyOf(b.ignored);
        this.only = List.copyOf(b.only);
        this.memory = List.copyOf(b.memory);
        this.contextLines = b.contextLines;
        this.replay = b.replay;
        this.narrowingEnabled = narrowingEnabled;
        reference.boundary = Budgets.saturatedAdd(reference.target.cycleCount(), granularity.every());
        candidate.boundary = Budgets.saturatedAdd(candidate.target.cycleCount(), granularity.every());
    }

    public static Builder builder(String referenceName, Debugger reference, String candidateName, Debugger candidate) {
        return new Builder(referenceName, reference, candidateName, candidate);
    }

    /**
     * Runs until the first divergence or until {@code maxSteps} reference steps have been executed (in either
     * granularity the reference never executes more than {@code maxSteps} steps per call).
     */
    public LockstepResult run(long maxSteps) {
        return runUntil(() -> false, maxSteps);
    }

    /**
     * Like {@link #run(long)} but also ends (without divergence) at the first comparison point where {@code stop}
     * is true, e.g. {@code () -> cpu.pc() == 0} for "the program exited". Can be called again to continue after a
     * run that did not diverge.
     */
    public LockstepResult runUntil(BooleanSupplier stop, long maxSteps) {
        Objects.requireNonNull(stop, "stop");
        if (divergence != null) {
            return new LockstepResult(0, 0, "already diverged", divergence);
        }
        long startSteps = reference.steps;
        long comparisonsBefore = comparisons;
        if (comparisons == 0 && compare()) {
            return new LockstepResult(0, comparisons - comparisonsBefore, null, divergence);
        }
        while (true) {
            long steps = reference.steps - startSteps;
            if (steps >= maxSteps) {
                return new LockstepResult(steps, comparisons - comparisonsBefore, "step limit " + maxSteps + " reached", null);
            }
            if (stop.getAsBoolean()) {
                return new LockstepResult(steps, comparisons - comparisonsBefore, "stop condition met", null);
            }
            // in step mode both sides execute the same number of steps; in cycle mode each runs to its boundary.
            // The reference never executes more than the steps left in this call, in either mode.
            long quota = maxSteps - steps;
            if (granularity.unit() == Granularity.Unit.STEPS) {
                quota = Math.min(granularity.every(), quota);
            }
            long executed;
            try {
                executed = advance(reference, quota);
            } catch (RuntimeException e) {
                // the reference threw during its next step
                failed(reference.steps + 1, reference.quoted("reference") + " threw " + e);
                return new LockstepResult(reference.steps - startSteps, comparisons - comparisonsBefore, null,
                        divergence);
            }
            if (granularity.unit() == Granularity.Unit.CYCLES && reference.target.cycleCount() < reference.boundary) {
                // the step limit ran out inside a cycle window: leave the candidate where it is and compare once
                // the reference reaches the boundary (a later call continues the same window)
                return new LockstepResult(reference.steps - startSteps, comparisons - comparisonsBefore,
                        "step limit " + maxSteps + " reached", null);
            }
            try {
                advance(candidate, granularity.unit() == Granularity.Unit.STEPS ? executed : Long.MAX_VALUE);
            } catch (RuntimeException e) {
                failed(reference.steps, candidate.quoted("candidate") + " threw " + e);
                return new LockstepResult(reference.steps - startSteps, comparisons - comparisonsBefore, null,
                        divergence);
            }
            if (granularity.unit() == Granularity.Unit.CYCLES) {
                reference.boundary = Budgets.saturatedAdd(reference.boundary, granularity.every());
                candidate.boundary = Budgets.saturatedAdd(candidate.boundary, granularity.every());
            }
            if (compare()) {
                return new LockstepResult(reference.steps - startSteps, comparisons - comparisonsBefore, null,
                        divergence);
            }
        }
    }

    /**
     * Reference steps executed by {@link #run}/{@link #runUntil} since the lockstep was built (steps re-executed
     * while narrowing a divergence are not counted).
     */
    public long totalSteps() {
        return reference.steps;
    }

    /**
     * Steps one side through (part of) the next comparison window: {@code quota} steps in step mode, or up to the
     * side's next cycle boundary but at most {@code quota} steps in cycle mode. Returns the steps executed.
     */
    private long advance(Side side, long quota) {
        DebugTarget t = side.target;
        if (granularity.unit() == Granularity.Unit.STEPS) {
            for (long i = 0; i < quota; i++) {
                t.step();
                side.steps++;
            }
            return quota;
        }
        long n = 0;
        while (n < quota && t.cycleCount() < side.boundary) {
            t.step();
            side.steps++;
            n++;
        }
        return n;
    }

    /** @return true if the sides differ (and records the divergence) */
    private boolean compare() {
        long step = reference.steps;
        comparisons++;
        SnapshotDiff diff = diffNow(step);
        if (diff.isEmpty()) {
            lastMatchingStep = step;
            saveForNarrowing();
            return false;
        }
        divergence = new Divergence(step, comparisons - 1, lastMatchingStep, state(reference), state(candidate), diff,
                null);
        narrow();
        return true;
    }

    /** the comparison of both sides' current states (empty if they match) */
    private SnapshotDiff diffNow(long step) {
        Snapshot expected = prepare(reference, true, step);
        Snapshot actual = prepare(candidate, false, step);
        if (!only.isEmpty()) {
            for (String name : only) {
                if (expected.field(name).isEmpty() && actual.field(name).isEmpty()) {
                    throw new IllegalArgumentException("compareOnly field " + name + " is in neither snapshot ("
                            + reference.name + ": " + expected + "; " + candidate.name + ": " + actual + ")");
                }
            }
            String machine = expected.machine();
            expected = project(expected, machine);
            actual = project(actual, machine);
        }
        return expected.diff(actual, ignored);
    }

    /** @param step reference steps executed when (or, for the reference, during which) a side threw */
    private void failed(long step, String failure) {
        comparisons++;
        divergence = new Divergence(step, comparisons - 1, lastMatchingStep, state(reference), state(candidate), null,
                failure);
        narrow();
    }

    // ---- narrowing -------------------------------------------------------------------------------------------

    private boolean canRestore() {
        return reference.saver != null && candidate.saver != null;
    }

    private boolean narrowingNeeded() {
        return !(granularity.unit() == Granularity.Unit.STEPS && granularity.every() == 1);
    }

    /** at a matching comparison: keep both complete states for narrowing by restore */
    private void saveForNarrowing() {
        if (!narrowingEnabled || !narrowingNeeded() || !canRestore() || saveProblem != null) {
            return;
        }
        try {
            save(reference);
            save(candidate);
        } catch (RuntimeException e) {
            saveProblem = "restore: saving the state at the comparison after step " + reference.steps + " threw " + e;
            reference.saved = null;
            candidate.saved = null;
        }
    }

    private static void save(Side side) {
        side.saved = side.saver.save();
        side.savedRing = side.debugger.ringBuffer();
        side.savedRingMark = side.savedRing == null ? 0 : side.savedRing.totalSeen();
    }

    private void narrow() {
        if (!narrowingEnabled) {
            return;
        }
        Divergence d = divergence;
        if (!narrowingNeeded() || d.comparison() == 0 || d.lastMatchingStep() < 0) {
            return;
        }
        String coarse = granularity.toString();
        long windowStart = d.lastMatchingStep();
        long windowEnd = d.step();
        List<String> problems = new ArrayList<>();
        if (saveProblem != null) {
            problems.add(saveProblem);
        } else if (canRestore() && reference.saved != null && candidate.saved != null) {
            Narrowing n = narrowByRestore(coarse, windowStart, windowEnd, problems);
            if (n != null) {
                divergence = d.withNarrowing(n);
                return;
            }
        } else {
            List<String> sides = new ArrayList<>();
            if (reference.saver == null) {
                sides.add(reference.quoted("reference"));
            }
            if (candidate.saver == null) {
                sides.add(candidate.quoted("candidate"));
            }
            problems.add("restore: " + String.join(" and ", sides) + " cannot be restored (snapshot not complete"
                    + " and no StateSaver given with Builder.restoreWith)");
        }
        if (replay != null) {
            Narrowing n = narrowByReplay(coarse, windowStart, windowEnd, problems);
            if (n != null) {
                divergence = d.withNarrowing(n);
                return;
            }
        } else {
            problems.add("replay: no replay factory given (Builder.replayWith)");
        }
        divergence = d.withNarrowing(Narrowing.notNarrowed(coarse, windowStart, windowEnd, String.join("; ", problems)));
    }

    /** @return the narrowing, or null after adding the reason it failed to {@code problems} */
    private Narrowing narrowByRestore(String coarse, long windowStart, long windowEnd, List<String> problems) {
        try {
            reference.saver.restore(reference.saved);
            candidate.saver.restore(candidate.saved);
        } catch (RuntimeException e) {
            problems.add("restore: restoring the last matching state threw " + e);
            return null;
        }
        rollbackRing(reference);
        rollbackRing(candidate);
        Narrowing found = reference.debugger.withRingBufferOnly(() -> candidate.debugger.withRingBufferOnly(
                () -> reRun(this, Narrowing.Method.RESTORE, coarse, windowStart, windowEnd, problems)));
        if (found == null) {
            problems.add("restore: re-running steps " + (windowStart + 1) + ".." + windowEnd + " from the restored"
                    + " last match did not reproduce the divergence (a side is not deterministic, its saved state is"
                    + " incomplete, or the sides differ only in timing)");
        }
        return found;
    }

    private static void rollbackRing(Side side) {
        TraceRingBuffer ring = side.debugger.ringBuffer();
        if (ring == null) {
            return;
        }
        if (ring == side.savedRing && side.savedRingMark <= ring.totalSeen()) {
            ring.rollback(side.savedRingMark);
        } else {
            ring.clear();
        }
    }

    /**
     * Steps both sides of {@code ls} one step at a time from step {@code windowStart} through {@code windowEnd},
     * comparing after each.
     *
     * @return the narrowing at the first difference or exception, or null if there was none
     */
    private static Narrowing reRun(Lockstep ls, Narrowing.Method method, String coarse, long windowStart,
                                   long windowEnd, List<String> notes) {
        for (long s = windowStart + 1; s <= windowEnd; s++) {
            String failure = null;
            try {
                ls.reference.target.step();
            } catch (RuntimeException e) {
                failure = ls.reference.quoted("reference") + " threw " + e;
            }
            if (failure == null) {
                try {
                    ls.candidate.target.step();
                } catch (RuntimeException e) {
                    failure = ls.candidate.quoted("candidate") + " threw " + e;
                }
            }
            SnapshotDiff diff = failure == null ? ls.diffNow(s) : null;
            if (failure != null || !diff.isEmpty()) {
                return new Narrowing(Narrowing.Status.NARROWED, method, coarse, windowStart, windowEnd, s,
                        ls.state(ls.reference), ls.state(ls.candidate), diff, failure, String.join("; ", notes));
            }
        }
        return null;
    }

    /** @return the narrowing, or null after adding the reason it failed to {@code problems} */
    private Narrowing narrowByReplay(String coarse, long windowStart, long windowEnd, List<String> problems) {
        Builder fresh;
        try {
            fresh = Objects.requireNonNull(replay.get(), "the replay factory returned null");
        } catch (RuntimeException e) {
            problems.add("replay: the replay factory threw " + e);
            return null;
        }
        String difference = configurationDifference(fresh);
        if (difference != null) {
            problems.add("replay: the replay set-up is configured differently (" + difference + ")");
            return null;
        }
        if (fresh.reference.target() == reference.target || fresh.candidate.target() == candidate.target
                || fresh.reference.target() == candidate.target || fresh.candidate.target() == reference.target) {
            problems.add("replay: the replay factory must build fresh targets, not reuse the original ones");
            return null;
        }
        Lockstep replica = new Lockstep(fresh.granularity(granularity), false);
        try {
            LockstepResult before = replica.run(windowStart);
            if (before.diverged()) {
                problems.add("replay: the replay diverged after step " + before.divergence().step() + ", before the"
                        + " last matching comparison (after step " + windowStart + "): the models or their input"
                        + " stimulus are not deterministic, or the replay is not set up identically");
                return null;
            }
            if (replica.lastMatchingStep != windowStart) {
                problems.add("replay: the replay's last comparison before the window was after step "
                        + replica.lastMatchingStep + ", not " + windowStart + " (the models are not deterministic)");
                return null;
            }
            replica.granularity = Granularity.everyStep();
            LockstepResult window = replica.run(windowEnd - windowStart);
            if (!window.diverged()) {
                problems.add("replay: the replay did not reproduce the divergence in steps " + (windowStart + 1) + ".."
                        + windowEnd + " (the models or their input stimulus are not deterministic, or the sides"
                        + " differ only in timing)");
                return null;
            }
            Divergence d = window.divergence();
            return new Narrowing(Narrowing.Status.NARROWED, Narrowing.Method.REPLAY, coarse, windowStart, windowEnd,
                    d.step(), d.reference(), d.candidate(), d.diff(), d.failure(), String.join("; ", problems));
        } catch (RuntimeException e) {
            problems.add("replay: the replay threw " + e);
            return null;
        }
    }

    /** null if {@code b} compares the same way as this lockstep, else what differs */
    private String configurationDifference(Builder b) {
        if (!Set.copyOf(b.ignored).equals(ignored)) {
            return "ignored fields " + b.ignored + " instead of " + ignored;
        }
        if (!b.only.equals(only)) {
            return "compareOnly fields " + b.only + " instead of " + only;
        }
        List<String> mine = memory.stream().map(MemorySpec::describe).toList();
        List<String> theirs = b.memory.stream().map(MemorySpec::describe).toList();
        if (!mine.equals(theirs)) {
            return "memory regions " + theirs + " instead of " + mine;
        }
        if (!b.referenceName.equals(reference.name) || !b.candidateName.equals(candidate.name)) {
            return "side names " + b.referenceName + "/" + b.candidateName + " instead of " + reference.name + "/"
                    + candidate.name;
        }
        return null;
    }

    private Snapshot prepare(Side side, boolean isReference, long step) {
        Snapshot s = side.snapshot.get();
        for (MemorySpec m : memory) {
            s = s.withRegion(m.name, isReference ? m.reference : m.candidate, m.start, m.length);
        }
        return s.withLabel(side.name + " after step " + step);
    }

    private Snapshot project(Snapshot s, String machine) {
        Snapshot.Builder b = Snapshot.builder(machine).label(s.label());
        // the Z/X masks of a gate-level probe (see ProbeSet) travel with it: a floating or contended bus reads as 0
        // in NAME itself, so dropping NAME.Z / NAME.X would make it compare equal to 00
        Set<String> names = new LinkedHashSet<>();
        for (String name : only) {
            names.add(name);
            names.add(name + ".Z");
            names.add(name + ".X");
        }
        for (String name : names) {
            s.field(name).ifPresent(b::field);
        }
        s.regions().forEach(b::region);
        return b.build();
    }

    private Divergence.SideState state(Side side) {
        DebugTarget t = side.target;
        TraceRingBuffer ring = side.debugger.ringBuffer();
        List<String> trace = new ArrayList<>();
        if (ring != null) {
            List<String> all = ring.lines();
            trace.addAll(all.subList(Math.max(0, all.size() - contextLines), all.size()));
        }
        return new Divergence.SideState(side.name, t.instructionCount(), t.cycleCount(), t.programCounter(), trace);
    }

    /** Configures a {@link Lockstep}. */
    public static final class Builder {
        private final String referenceName;
        private final Debugger reference;
        private final String candidateName;
        private final Debugger candidate;
        private Supplier<Snapshot> referenceSnapshot;
        private Supplier<Snapshot> candidateSnapshot;
        private Granularity granularity = Granularity.everyStep();
        private final Set<String> ignored = new LinkedHashSet<>();
        private final List<String> only = new ArrayList<>();
        private final List<MemorySpec> memory = new ArrayList<>();
        private int contextLines = DEFAULT_CONTEXT_LINES;
        private StateSaver referenceSaver;
        private StateSaver candidateSaver;
        private Supplier<Builder> replay;

        private Builder(String referenceName, Debugger reference, String candidateName, Debugger candidate) {
            this.referenceName = Objects.requireNonNull(referenceName, "referenceName");
            this.reference = Objects.requireNonNull(reference, "reference");
            this.candidateName = Objects.requireNonNull(candidateName, "candidateName");
            this.candidate = Objects.requireNonNull(candidate, "candidate");
            if (reference.target() == candidate.target()) {
                throw new IllegalArgumentException("reference and candidate must be different targets");
            }
        }

        /** how often to compare (default {@link Granularity#everyStep()}) */
        public Builder granularity(Granularity granularity) {
            this.granularity = Objects.requireNonNull(granularity);
            return this;
        }

        /** fields left out of the comparison, e.g. {@code "CYCLES"} when the models' timings legitimately differ */
        public Builder ignore(String... fields) {
            ignored.addAll(List.of(fields));
            return this;
        }

        /**
         * Compare only these fields (plus memory regions); both snapshots are reduced to them and treated as the
         * same machine. Use this for models of different machines. A field's {@code NAME.Z} / {@code NAME.X} mask
         * fields (see {@code ProbeSet}) are kept with it, so a floating or contended bus diverges from a driven
         * value even where its bits read as 0; a mask present on one side only is a divergence. A field that is in
         * neither snapshot is a configuration error: the run throws {@link IllegalArgumentException} at the first
         * comparison instead of silently comparing nothing.
         */
        public Builder compareOnly(String... fields) {
            for (String f : fields) {
                new Field(f, 1, 0, Field.Format.HEX); // validates the name
                only.add(f);
            }
            return this;
        }

        /** Adds memory region {@code [start, start + length)} of both sides to every comparison. */
        public Builder compareMemory(String region, MemoryView referenceMemory, MemoryView candidateMemory, int start,
                                     int length) {
            memory.add(new MemorySpec(Objects.requireNonNull(region), Objects.requireNonNull(referenceMemory),
                    Objects.requireNonNull(candidateMemory), start, length));
            return this;
        }

        /** How the reference's state is captured (default: its target's {@link DebugTarget#snapshot()}). */
        public Builder referenceSnapshot(Supplier<Snapshot> snapshot) {
            this.referenceSnapshot = Objects.requireNonNull(snapshot);
            return this;
        }

        /** How the candidate's state is captured (default: its target's {@link DebugTarget#snapshot()}). */
        public Builder candidateSnapshot(Supplier<Snapshot> snapshot) {
            this.candidateSnapshot = Objects.requireNonNull(snapshot);
            return this;
        }

        /** trace lines per side in the report (taken from each debugger's ring buffer), default 16 */
        public Builder contextLines(int lines) {
            if (lines < 0) {
                throw new IllegalArgumentException("lines must be >= 0");
            }
            this.contextLines = lines;
            return this;
        }

        /**
         * How to save and restore each side's complete state, enabling narrowing by restore (see
         * {@link Lockstep}). Not needed for a target whose snapshot is complete
         * ({@link com.darchan.diag.Snapshottable#isSnapshotComplete()}); needed, for example, for a CPU whose memory
         * is on an external bus ({@code StateSaver.of(cpu).withMemory(...)}). Both sides' states are then saved at
         * every matching comparison.
         */
        public Builder restoreWith(StateSaver reference, StateSaver candidate) {
            this.referenceSaver = Objects.requireNonNull(reference, "reference");
            this.candidateSaver = Objects.requireNonNull(candidate, "candidate");
            return this;
        }

        /**
         * Enables narrowing by replay (see {@link Lockstep}): {@code freshSetup} must build a new reference and
         * candidate, configured exactly like these (same program, same faults, same input stimulus, same side
         * names, ignored fields, compareOnly fields and memory regions; its granularity is overridden). The usual
         * way is to write the set-up once as a method and use it for both:
         * {@code setup().granularity(g).replayWith(() -> setup()).build()}.
         */
        public Builder replayWith(Supplier<Builder> freshSetup) {
            this.replay = Objects.requireNonNull(freshSetup);
            return this;
        }

        public Lockstep build() {
            return new Lockstep(this, true);
        }
    }
}
