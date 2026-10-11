package com.darchan.diag.run;

import com.darchan.diag.HexDump;
import com.darchan.diag.MemoryView;
import com.darchan.diag.RegisterDump;
import com.darchan.diag.Snapshot;
import com.darchan.diag.trace.TraceListener;
import com.darchan.diag.trace.TraceRingBuffer;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Run control and diagnostics for any {@link DebugTarget}:
 * <ul>
 *   <li><b>run control</b>: {@link #step()}, {@link #step(long)} instructions, {@link #stepCycles(long)} (finishing
 *       the current instruction), {@link #run(long)} and {@link #runUntil(BooleanSupplier, long)};</li>
 *   <li><b>breakpoints</b> on the PC (stop <em>before</em> executing that address), on the cycle count, and
 *       <b>watchpoints</b> on memory/I/O accesses (stop <em>after</em> the instruction that made the access). For
 *       watchpoints the model's buses must report into {@link #busListener()} (e.g. the behavioral layer's
 *       observing bus decorators);</li>
 *   <li><b>tracing</b>: any number of {@link TraceListener}s plus a {@link TraceRingBuffer} of recent instructions
 *       (64 by default), shown in {@link #failureReport()};</li>
 *   <li><b>dumps</b> (registers + hex dumps of chosen memory ranges) on demand, at every stop, every N cycles, and
 *       automatically when the target throws (the exception is rethrown as {@link ExecutionFailure} carrying the
 *       report).</li>
 * </ul>
 * Lockstep comparison, a REPL and other front ends are meant to drive a target through this class.
 */
public final class Debugger {

    public static final int DEFAULT_RING_BUFFER = 64;

    private static final System.Logger LOG = System.getLogger(Debugger.class.getName());

    private final DebugTarget target;
    private final MemoryView memory;

    private final List<TraceListener> traceListeners = new ArrayList<>();
    private TraceRingBuffer ringBuffer;

    private final BitSet breakpoints = new BitSet();
    private final TreeSet<Long> cycleBreakpoints = new TreeSet<>();

    private final List<Watchpoint> watchpoints = new ArrayList<>();
    private int[] watchMask = new int[0];
    private int[] watchFrom = new int[0];
    private int[] watchTo = new int[0];
    private String watchHit;
    private final BusAccessListener busListener = this::onBusAccess;

    private final List<int[]> dumpRanges = new ArrayList<>();
    private boolean dumpOnStop;
    private long dumpEveryCycles;
    private long nextPeriodicDump = Long.MAX_VALUE;
    private Consumer<String> dumpSink = text -> LOG.log(Level.INFO, text);

    private boolean stopOnHalt = true;

    /**
     * @param target the model to control; this debugger takes over its trace listener slot
     * @param memory side-effect-free view of memory for dumps, or {@code null} if there is none
     */
    public Debugger(DebugTarget target, MemoryView memory) {
        this.target = Objects.requireNonNull(target, "target");
        this.memory = memory;
        this.ringBuffer = new TraceRingBuffer(DEFAULT_RING_BUFFER);
        installTrace();
    }

    public DebugTarget target() {
        return target;
    }

    // ---- tracing ---------------------------------------------------------------------------------------------

    public void addTraceListener(TraceListener listener) {
        traceListeners.add(Objects.requireNonNull(listener));
        installTrace();
    }

    public void removeTraceListener(TraceListener listener) {
        traceListeners.remove(listener);
        installTrace();
    }

    /** Sets how many recent instructions to keep; 0 disables the ring buffer (and its small per-step cost). */
    public void setRingBufferCapacity(int capacity) {
        ringBuffer = capacity == 0 ? null : new TraceRingBuffer(capacity);
        installTrace();
    }

    /** the ring buffer, or {@code null} if disabled */
    public TraceRingBuffer ringBuffer() {
        return ringBuffer;
    }

    private void installTrace() {
        List<TraceListener> all = new ArrayList<>(traceListeners);
        if (ringBuffer != null) {
            all.add(ringBuffer);
        }
        if (all.isEmpty()) {
            target.setTraceListener(null);
        } else if (all.size() == 1) {
            target.setTraceListener(all.get(0));
        } else {
            TraceListener[] array = all.toArray(new TraceListener[0]);
            target.setTraceListener(event -> {
                for (TraceListener l : array) {
                    l.onInstruction(event);
                }
            });
        }
    }

    // ---- breakpoints and watchpoints -------------------------------------------------------------------------

    /**
     * Stop before executing the instruction at {@code pc}. Checked only at instruction boundaries where the target
     * is not halted: after {@code HLT} (whose PC already points past it) and during the idle steps that follow,
     * the breakpoint does not fire; it does once execution actually resumes at {@code pc}, e.g. when an interrupt
     * handler returns there.
     */
    public void addBreakpoint(int pc) {
        breakpoints.set(pc);
    }

    public void removeBreakpoint(int pc) {
        breakpoints.clear(pc);
    }

    public boolean hasBreakpoint(int pc) {
        return breakpoints.get(pc);
    }

    /** the PC breakpoints, in ascending order */
    public List<Integer> breakpoints() {
        return breakpoints.stream().boxed().toList();
    }

    /** the cycle breakpoints, in ascending order */
    public List<Long> cycleBreakpoints() {
        return List.copyOf(cycleBreakpoints);
    }

    /** Stop after the instruction during which the cycle count reaches {@code cycle}. */
    public void addCycleBreakpoint(long cycle) {
        cycleBreakpoints.add(cycle);
    }

    public void removeCycleBreakpoint(long cycle) {
        cycleBreakpoints.remove(cycle);
    }

    public void addWatchpoint(Watchpoint w) {
        watchpoints.add(Objects.requireNonNull(w));
        rebuildWatchArrays();
    }

    public void removeWatchpoint(Watchpoint w) {
        watchpoints.remove(w);
        rebuildWatchArrays();
    }

    public List<Watchpoint> watchpoints() {
        return List.copyOf(watchpoints);
    }

    public void clearBreakpointsAndWatchpoints() {
        breakpoints.clear();
        cycleBreakpoints.clear();
        watchpoints.clear();
        rebuildWatchArrays();
    }

    /** The hook the model's (decorated) buses must call for watchpoints to work. */
    public BusAccessListener busListener() {
        return busListener;
    }

    private void rebuildWatchArrays() {
        int n = watchpoints.size();
        int[] mask = new int[n];
        int[] from = new int[n];
        int[] to = new int[n];
        for (int i = 0; i < n; i++) {
            Watchpoint w = watchpoints.get(i);
            for (AccessType t : w.types()) {
                mask[i] |= 1 << t.ordinal();
            }
            from[i] = w.from();
            to[i] = w.to();
        }
        watchMask = mask;
        watchFrom = from;
        watchTo = to;
    }

    private void onBusAccess(AccessType type, int address, int value) {
        int[] mask = watchMask;
        if (mask.length == 0 || watchHit != null) {
            return;
        }
        int bit = 1 << type.ordinal();
        for (int i = 0; i < mask.length; i++) {
            if ((mask[i] & bit) != 0 && address >= watchFrom[i] && address <= watchTo[i]) {
                watchHit = watchpoints.get(i) + ": " + type + " " + String.format("%04X", address)
                        + " value " + String.format("%02X", value & 0xFF);
                return;
            }
        }
    }

    // ---- dumps -----------------------------------------------------------------------------------------------

    /** Adds a memory range to every dump. */
    public void addDumpRange(int start, int length) {
        if (memory == null) {
            throw new IllegalStateException("this debugger has no memory view");
        }
        dumpRanges.add(new int[]{start, length});
    }

    public void clearDumpRanges() {
        dumpRanges.clear();
    }

    /** Dump to the sink whenever a run stops for a breakpoint, watchpoint, cycle breakpoint, condition or halt. */
    public void setDumpOnStop(boolean enabled) {
        this.dumpOnStop = enabled;
    }

    /** Dump to the sink each time another {@code cycles} cycles have passed; 0 disables. */
    public void setDumpEvery(long cycles) {
        if (cycles < 0) {
            throw new IllegalArgumentException("cycles must be >= 0");
        }
        dumpEveryCycles = cycles;
        nextPeriodicDump = cycles == 0 ? Long.MAX_VALUE : Budgets.saturatedAdd(target.cycleCount(), cycles);
    }

    /** Where automatic dumps go; the default logs them at INFO via {@link System.Logger}. */
    public void setDumpSink(Consumer<String> sink) {
        this.dumpSink = Objects.requireNonNull(sink);
    }

    /** Register dump plus a hex dump of every configured range. */
    public String dump() {
        Snapshot s = target.snapshot();
        StringBuilder sb = new StringBuilder();
        sb.append("--- dump at cycle ").append(target.cycleCount()).append(" ---\n");
        sb.append("registers: ").append(RegisterDump.format(s));
        for (int[] r : dumpRanges) {
            sb.append("\nmemory ").append(String.format("%04X", r[0])).append("+").append(r[1]).append(":\n");
            sb.append(HexDump.format(memory, r[0], r[1]));
        }
        return sb.toString();
    }

    /** A hex dump of any range of the target's memory. */
    public String hexDump(int start, int length) {
        if (memory == null) {
            throw new IllegalStateException("this debugger has no memory view");
        }
        return HexDump.format(memory, start, length);
    }

    /** Recent trace (if the ring buffer is enabled) followed by {@link #dump()}: what to print when things go wrong. */
    public String failureReport() {
        StringBuilder sb = new StringBuilder();
        if (ringBuffer != null) {
            sb.append(ringBuffer.dump()).append('\n');
        }
        return sb.append(dump()).toString();
    }

    // ---- run control -----------------------------------------------------------------------------------------

    /** Whether a run stops when the target enters the halted state (default true). */
    public void setStopOnHalt(boolean stopOnHalt) {
        this.stopOnHalt = stopOnHalt;
    }

    /** Executes one instruction (unless something stops it first, it returns {@link StopReason.Kind#STEPPED}). */
    public StopReason step() {
        return step(1);
    }

    /** Executes up to {@code count} steps (instructions, or idle periods while halted). */
    public StopReason step(long count) {
        return execute(count, Long.MAX_VALUE, null, null);
    }

    /** Executes until at least {@code cycles} more cycles have passed, finishing the instruction that crosses. */
    public StopReason stepCycles(long cycles) {
        return execute(Long.MAX_VALUE, Budgets.saturatedAdd(target.cycleCount(), cycles), StopReason.Kind.STEPPED,
                null);
    }

    /** Runs until a breakpoint, watchpoint, halt, or until {@code maxCycles} more cycles have passed. */
    public StopReason run(long maxCycles) {
        return execute(Long.MAX_VALUE, Budgets.saturatedAdd(target.cycleCount(), maxCycles),
                StopReason.Kind.CYCLE_LIMIT, null);
    }

    /** Like {@link #run(long)} but also stops after any instruction that leaves {@code condition} true. */
    public StopReason runUntil(BooleanSupplier condition, long maxCycles) {
        return execute(Long.MAX_VALUE, Budgets.saturatedAdd(target.cycleCount(), maxCycles),
                StopReason.Kind.CYCLE_LIMIT, Objects.requireNonNull(condition));
    }

    private StopReason execute(long maxSteps, long cycleTarget, StopReason.Kind cycleKind, BooleanSupplier condition) {
        long cycles = target.cycleCount();
        Long nextBreak = cycleBreakpoints.higher(cycles);
        long nextCycleBreak = nextBreak == null ? Long.MAX_VALUE : nextBreak;
        if (maxSteps <= 0) {
            return stop(StopReason.Kind.STEPPED, "0 steps requested");
        }
        if (cycles >= cycleTarget) {
            return stop(cycleKind, "cycle target " + cycleTarget + " already reached");
        }
        long steps = 0;
        while (true) {
            boolean wasHalted = target.isHalted();
            watchHit = null;
            try {
                target.step();
            } catch (RuntimeException e) {
                throw failure(e);
            }
            steps++;
            cycles = target.cycleCount();
            if (cycles >= nextPeriodicDump) {
                dumpSink.accept(dump());
                while (nextPeriodicDump <= cycles) {
                    nextPeriodicDump = Budgets.saturatedAdd(nextPeriodicDump, dumpEveryCycles);
                }
            }
            if (watchHit != null) {
                return stop(StopReason.Kind.WATCHPOINT, watchHit);
            }
            int pc = target.programCounter();
            // PC breakpoints only fire at real instruction boundaries: while the target is halted (after HLT and
            // during each idle step) the PC is not the address of an instruction about to execute
            boolean halted = target.isHalted();
            if (!halted && breakpoints.get(pc)) {
                return stop(StopReason.Kind.BREAKPOINT, "breakpoint at " + String.format("%04X", pc));
            }
            if (cycles >= nextCycleBreak) {
                return stop(StopReason.Kind.CYCLE_BREAKPOINT, "cycle breakpoint " + nextCycleBreak);
            }
            if (condition != null && condition.getAsBoolean()) {
                return stop(StopReason.Kind.CONDITION, "condition met");
            }
            if (stopOnHalt && !wasHalted && halted) {
                return stop(StopReason.Kind.HALTED, "halted");
            }
            if (steps >= maxSteps) {
                return new StopReason(StopReason.Kind.STEPPED, steps + " step(s)", cycles, pc);
            }
            if (cycles >= cycleTarget) {
                return new StopReason(cycleKind, "cycle target " + cycleTarget + " reached", cycles, pc);
            }
        }
    }

    private StopReason stop(StopReason.Kind kind, String detail) {
        StopReason reason = new StopReason(kind, detail, target.cycleCount(), target.programCounter());
        LOG.log(Level.DEBUG, () -> "stopped: " + reason);
        if (dumpOnStop && kind != StopReason.Kind.STEPPED) {
            dumpSink.accept("stopped: " + reason + "\n" + dump());
        }
        return reason;
    }

    private ExecutionFailure failure(RuntimeException e) {
        String report;
        try {
            report = failureReport();
        } catch (RuntimeException secondary) {
            e.addSuppressed(secondary);
            report = "(failure report unavailable: " + secondary + ")";
        }
        String summary = "target threw " + e + " at cycle " + target.cycleCount()
                + ", PC=" + String.format("%04X", target.programCounter());
        LOG.log(Level.ERROR, summary);
        dumpSink.accept(summary + "\n" + report);
        return new ExecutionFailure(summary, report, e);
    }
}
