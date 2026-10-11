package com.darchan.diag.compare;

import com.darchan.diag.Snapshot;
import com.darchan.diag.SnapshotDiff;
import com.darchan.diag.SnapshotJson;
import com.darchan.diag.run.Budgets;
import com.darchan.diag.run.DebugTarget;
import com.darchan.diag.run.Debugger;
import com.darchan.diag.run.StopReason;
import com.darchan.diag.trace.TraceRingBuffer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A set of {@link Checkpoint}s with one {@link SnapshotJson} file each ({@code <directory>/<name>.json}): record
 * them once from a trusted run, then verify later runs against them.
 *
 * <pre>{@code
 * SnapshotCheckpoints cps = SnapshotCheckpoints.in(Path.of("checkpoints"))
 *         .add(Checkpoint.atInstruction("i1000", 1000))
 *         .add(Checkpoint.atCycle("c1M", 1_000_000))
 *         .add(Checkpoint.atPc("bdos3", 0x0005, 3))           // third time the PC reaches 0005
 *         .snapshot(() -> cpu.snapshot().withRegion("ram", ram::peek, 0, 0x10000))
 *         .ignore("CYCLES");
 * cps.record(goodDebugger, 50_000_000);                      // writes checkpoints/i1000.json, ...
 * CheckpointReport r = cps.verify(debugger, 50_000_000);     // later
 * if (!r.allPassed()) System.out.println(r.report());
 * }</pre>
 *
 * The run uses {@link Debugger#runUntil} and ends when every checkpoint was handled, at the first mismatch (unless
 * {@link #stopAtFirstMismatch(boolean) disabled}), or at any other debugger stop (breakpoint, watchpoint, halt,
 * cycle limit); checkpoints not reached by then are reported as {@link CheckpointResult.Status#NOT_REACHED}.
 */
public final class SnapshotCheckpoints {

    /** trace lines included with a mismatch */
    public static final int CONTEXT_LINES = 8;

    private final Path directory;
    private final List<Checkpoint> checkpoints = new ArrayList<>();
    private final Set<String> ignored = new LinkedHashSet<>();
    private Supplier<Snapshot> snapshot;
    private boolean stopAtFirstMismatch = true;

    private SnapshotCheckpoints(Path directory) {
        this.directory = Objects.requireNonNull(directory);
    }

    /** A checkpoint set whose snapshot files live in {@code directory}. */
    public static SnapshotCheckpoints in(Path directory) {
        return new SnapshotCheckpoints(directory);
    }

    public SnapshotCheckpoints add(Checkpoint checkpoint) {
        for (Checkpoint c : checkpoints) {
            if (c.name().equals(checkpoint.name())) {
                throw new IllegalArgumentException("duplicate checkpoint " + checkpoint.name());
            }
        }
        checkpoints.add(checkpoint);
        return this;
    }

    /** How to capture the state at a checkpoint (default: the target's {@code snapshot()}); include memory here. */
    public SnapshotCheckpoints snapshot(Supplier<Snapshot> snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot);
        return this;
    }

    /** fields left out when verifying */
    public SnapshotCheckpoints ignore(String... fields) {
        ignored.addAll(List.of(fields));
        return this;
    }

    /** whether {@link #verify} stops at the first mismatch (default true) */
    public SnapshotCheckpoints stopAtFirstMismatch(boolean stop) {
        this.stopAtFirstMismatch = stop;
        return this;
    }

    public List<Checkpoint> checkpoints() {
        return List.copyOf(checkpoints);
    }

    /** {@code <directory>/<name>.json} */
    public Path file(Checkpoint checkpoint) {
        return directory.resolve(checkpoint.name() + ".json");
    }

    /** Runs the target and saves a snapshot at every checkpoint (creating the directory if needed). */
    public CheckpointReport record(Debugger debugger, long maxCycles) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + directory, e);
        }
        return run(debugger, maxCycles, true);
    }

    /** Runs the target and compares the state at every checkpoint with its saved snapshot. */
    public CheckpointReport verify(Debugger debugger, long maxCycles) {
        return run(debugger, maxCycles, false);
    }

    private CheckpointReport run(Debugger debugger, long maxCycles, boolean record) {
        DebugTarget target = debugger.target();
        Supplier<Snapshot> capture = snapshot != null ? snapshot : target::snapshot;
        List<Checkpoint> pending = new ArrayList<>(checkpoints);
        Evaluator triggers = new Evaluator(target, checkpoints);
        List<CheckpointResult> results = new ArrayList<>();
        long budgetEnd = Budgets.saturatedAdd(target.cycleCount(), maxCycles);
        StopReason stop;
        triggers.evaluate(); // the initial state is a boundary too
        while (true) {
            if (handleFired(pending, triggers, target, capture, debugger, record, results)) {
                stop = stopHere(target, "stopped at the first mismatch");
                break;
            }
            if (pending.stream().allMatch(Objects::isNull)) {
                stop = stopHere(target, "all checkpoints handled");
                break;
            }
            long remaining = budgetEnd - target.cycleCount();
            if (remaining <= 0) {
                stop = new StopReason(StopReason.Kind.CYCLE_LIMIT, "cycle budget used up", target.cycleCount(),
                        target.programCounter());
                break;
            }
            stop = debugger.runUntil(triggers::evaluate, remaining);
            if (stop.kind() != StopReason.Kind.CONDITION) {
                // another stop came first, possibly before the triggers saw this boundary
                triggers.evaluate();
                handleFired(pending, triggers, target, capture, debugger, record, results);
                break;
            }
        }
        boolean failed = results.stream().anyMatch(r -> r.status() == CheckpointResult.Status.MISMATCH);
        for (Checkpoint c : pending) {
            if (c != null) {
                results.add(new CheckpointResult(c, CheckpointResult.Status.NOT_REACHED, -1, -1, -1, null,
                        failed && stopAtFirstMismatch ? "not run: an earlier checkpoint mismatched" : "run ended first",
                        List.of()));
            }
        }
        return new CheckpointReport(results, stop);
    }

    private static StopReason stopHere(DebugTarget target, String detail) {
        return new StopReason(StopReason.Kind.CONDITION, detail, target.cycleCount(), target.programCounter());
    }

    /** handles every fired, pending checkpoint; returns true if the run should stop at a mismatch */
    private boolean handleFired(List<Checkpoint> pending, Evaluator triggers, DebugTarget target,
                                Supplier<Snapshot> capture, Debugger debugger, boolean record,
                                List<CheckpointResult> results) {
        boolean stop = false;
        for (int i = 0; i < pending.size(); i++) {
            if (pending.get(i) != null && triggers.fired[i]) {
                CheckpointResult r = handle(pending.get(i), target, capture, debugger, record);
                results.add(r);
                pending.set(i, null);
                stop |= r.status() == CheckpointResult.Status.MISMATCH && stopAtFirstMismatch;
            }
        }
        return stop;
    }

    /** Evaluates the triggers once per instruction boundary (so PC occurrence counts stay exact). */
    private static final class Evaluator {
        private final DebugTarget target;
        private final List<Checkpoint.Trigger> triggers = new ArrayList<>();
        final boolean[] fired;
        private long lastCycle = -1;
        private long lastInstruction = -1;

        Evaluator(DebugTarget target, List<Checkpoint> checkpoints) {
            this.target = target;
            checkpoints.forEach(c -> triggers.add(c.trigger().fresh()));
            this.fired = new boolean[checkpoints.size()];
        }

        /** @return true if a trigger fired at this boundary */
        boolean evaluate() {
            long cycle = target.cycleCount();
            long instr = target.instructionCount();
            if (cycle == lastCycle && instr == lastInstruction) {
                return false;
            }
            lastCycle = cycle;
            lastInstruction = instr;
            boolean any = false;
            for (int i = 0; i < fired.length; i++) {
                if (!fired[i] && triggers.get(i).reached(target)) {
                    fired[i] = true;
                    any = true;
                }
            }
            return any;
        }
    }

    private CheckpointResult handle(Checkpoint c, DebugTarget t, Supplier<Snapshot> capture, Debugger debugger,
                                    boolean record) {
        Snapshot live = capture.get();
        long instr = t.instructionCount();
        long cycle = t.cycleCount();
        int pc = t.programCounter();
        Path file = file(c);
        if (record) {
            SnapshotJson.save(live.withLabel(c.name()), file);
            return new CheckpointResult(c, CheckpointResult.Status.RECORDED, instr, cycle, pc, null,
                    "saved " + file.getFileName(), List.of());
        }
        Snapshot expected;
        try {
            expected = SnapshotJson.load(file);
        } catch (RuntimeException e) {
            return new CheckpointResult(c, CheckpointResult.Status.NO_FILE, instr, cycle, pc, null,
                    "cannot load " + file + ": " + e.getMessage(), List.of());
        }
        SnapshotDiff diff = expected.diff(live.withLabel("live"), ignored);
        if (diff.isEmpty()) {
            return new CheckpointResult(c, CheckpointResult.Status.MATCH, instr, cycle, pc, diff, null, List.of());
        }
        List<String> trace = List.of();
        TraceRingBuffer ring = debugger.ringBuffer();
        if (ring != null) {
            List<String> all = ring.lines();
            trace = all.subList(Math.max(0, all.size() - CONTEXT_LINES), all.size());
        }
        String detail = diff.fieldDifferences().size() + " field(s) and " + diff.byteDifferenceCount()
                + " memory byte(s) differ";
        return new CheckpointResult(c, CheckpointResult.Status.MISMATCH, instr, cycle, pc, diff, detail, trace);
    }
}
