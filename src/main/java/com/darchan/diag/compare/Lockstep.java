package com.darchan.diag.compare;

import com.darchan.diag.Field;
import com.darchan.diag.MemoryView;
import com.darchan.diag.Snapshot;
import com.darchan.diag.SnapshotDiff;
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
 */
public final class Lockstep {

    /** default number of trace lines per side in a divergence report */
    public static final int DEFAULT_CONTEXT_LINES = 16;

    private record MemorySpec(String name, MemoryView reference, MemoryView candidate, int start, int length) {
    }

    private final Side reference;
    private final Side candidate;
    private final Granularity granularity;
    private final Set<String> ignored;
    private final List<String> only;
    private final List<MemorySpec> memory;
    private final int contextLines;

    private long totalSteps;
    private long comparisons;
    private long lastMatchingStep = -1;
    private long windows;
    private Divergence divergence;

    private static final class Side {
        final String name;
        final Debugger debugger;
        final DebugTarget target;
        final Supplier<Snapshot> snapshot;
        long startCycles;

        Side(String name, Debugger debugger, Supplier<Snapshot> snapshot) {
            this.name = name;
            this.debugger = debugger;
            this.target = debugger.target();
            this.snapshot = snapshot != null ? snapshot : target::snapshot;
            this.startCycles = target.cycleCount();
        }
    }

    private Lockstep(Builder b) {
        this.reference = new Side(b.referenceName, b.reference, b.referenceSnapshot);
        this.candidate = new Side(b.candidateName, b.candidate, b.candidateSnapshot);
        this.granularity = b.granularity;
        this.ignored = Set.copyOf(b.ignored);
        this.only = List.copyOf(b.only);
        this.memory = List.copyOf(b.memory);
        this.contextLines = b.contextLines;
    }

    public static Builder builder(String referenceName, Debugger reference, String candidateName, Debugger candidate) {
        return new Builder(referenceName, reference, candidateName, candidate);
    }

    /** Runs until the first divergence or until {@code maxSteps} reference steps have been executed. */
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
        long steps = 0;
        long comparisonsBefore = comparisons;
        if (comparisons == 0 && compare(0)) {
            return new LockstepResult(0, comparisons - comparisonsBefore, null, divergence);
        }
        while (true) {
            if (steps >= maxSteps) {
                return new LockstepResult(steps, comparisons - comparisonsBefore, "step limit " + maxSteps + " reached", null);
            }
            if (stop.getAsBoolean()) {
                return new LockstepResult(steps, comparisons - comparisonsBefore, "stop condition met", null);
            }
            // in step mode both sides execute the same number of steps; in cycle mode each runs to the boundary
            long quota = granularity.unit() == Granularity.Unit.STEPS
                    ? Math.min(granularity.every(), maxSteps - steps) : Long.MAX_VALUE;
            long executed;
            try {
                executed = advance(reference, quota);
            } catch (RuntimeException e) {
                failed("reference \"" + reference.name + "\" threw " + e);
                return new LockstepResult(steps, comparisons - comparisonsBefore, null, divergence);
            }
            steps += executed;
            totalSteps += executed;
            try {
                advance(candidate, granularity.unit() == Granularity.Unit.STEPS ? executed : Long.MAX_VALUE);
            } catch (RuntimeException e) {
                failed("candidate \"" + candidate.name + "\" threw " + e);
                return new LockstepResult(steps, comparisons - comparisonsBefore, null, divergence);
            }
            windows++;
            if (compare(totalSteps)) {
                return new LockstepResult(steps, comparisons - comparisonsBefore, null, divergence);
            }
        }
    }

    /** reference steps executed since the lockstep was built */
    public long totalSteps() {
        return totalSteps;
    }

    /**
     * Steps one side through the next comparison window: {@code quota} steps in step mode, or up to the next cycle
     * boundary in cycle mode. Returns the steps executed.
     */
    private long advance(Side side, long quota) {
        DebugTarget t = side.target;
        if (granularity.unit() == Granularity.Unit.STEPS) {
            for (long i = 0; i < quota; i++) {
                t.step();
            }
            return quota;
        }
        long boundary = side.startCycles + (windows + 1) * granularity.every();
        long n = 0;
        while (t.cycleCount() < boundary) {
            t.step();
            n++;
        }
        return n;
    }

    /** @return true if the sides differ (and records the divergence) */
    private boolean compare(long step) {
        comparisons++;
        Snapshot expected = prepare(reference, true, step);
        Snapshot actual = prepare(candidate, false, step);
        if (!only.isEmpty()) {
            expected = project(expected, expected.machine());
            actual = project(actual, expected.machine());
        }
        SnapshotDiff diff = expected.diff(actual, ignored);
        if (diff.isEmpty()) {
            lastMatchingStep = step;
            return false;
        }
        divergence = new Divergence(step, comparisons - 1, lastMatchingStep, state(reference), state(candidate), diff, null);
        return true;
    }

    private void failed(String failure) {
        comparisons++;
        divergence = new Divergence(totalSteps, comparisons - 1, lastMatchingStep, state(reference),
                state(candidate), null, failure);
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
        for (String name : only) {
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
         * same machine. Use this for models of different machines.
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

        public Lockstep build() {
            return new Lockstep(this);
        }
    }
}
