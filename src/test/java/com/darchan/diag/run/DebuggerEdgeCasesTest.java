package com.darchan.diag.run;

import com.darchan.diag.Snapshot;
import com.darchan.diag.trace.InMemoryTrace;
import com.darchan.diag.trace.TraceListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.darchan.diag.run.StopReason.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/** Bookkeeping of the debugger: trace wiring, breakpoint and watchpoint lists, dump settings, failure reports. */
class DebuggerEdgeCasesTest {

    private ToyCpu cpu;
    private Debugger dbg;

    @BeforeEach
    void setUp() {
        cpu = new ToyCpu();
        dbg = new Debugger(cpu, a -> cpu.mem[a & 0xFF]);
        cpu.bus = dbg.busListener();
        // 00: NOP; 01: INC; 02: STA 80; 04: LDA 81; 06: OUT 07; 08: INC; 09: JMP 00
        cpu.load(0x00, 0x03, 0x02, 0x80, 0x01, 0x81, 0x05, 0x07, 0x03, 0x06, 0x00);
    }

    // ---- trace listener wiring ---------------------------------------------------------------------------------

    /** a target that records the trace listener it was last given */
    private static class Spy implements DebugTarget {
        TraceListener installed;
        int installs;

        @Override
        public int step() {
            return 1;
        }

        @Override
        public long cycleCount() {
            return 0;
        }

        @Override
        public long instructionCount() {
            return 0;
        }

        @Override
        public int programCounter() {
            return 0;
        }

        @Override
        public void setTraceListener(TraceListener listener) {
            installed = listener;
            installs++;
        }

        @Override
        public Snapshot snapshot() {
            return Snapshot.builder("spy").build();
        }

        @Override
        public void restore(Snapshot s) {
        }
    }

    @Test
    void theTargetsTraceSlotFollowsTheListenersAndTheRingBuffer() {
        Spy spy = new Spy();
        Debugger d = new Debugger(spy, null);
        assertSame(d.ringBuffer(), spy.installed, "just the ring buffer: installed directly");

        InMemoryTrace listener = new InMemoryTrace();
        d.addTraceListener(listener);
        assertNotNull(spy.installed);
        assertNotSame(d.ringBuffer(), spy.installed, "two receivers: a fan-out");
        assertNotSame(listener, spy.installed);

        d.removeTraceListener(listener);
        assertSame(d.ringBuffer(), spy.installed, "back to the ring buffer alone");

        d.setRingBufferCapacity(0);
        assertNull(d.ringBuffer());
        assertNull(spy.installed, "nothing to trace: the target does no tracing work");

        d.addTraceListener(listener);
        assertSame(listener, spy.installed, "a single listener is installed directly");
        d.setRingBufferCapacity(8);
        assertEquals(8, d.ringBuffer().capacity());
        assertNotSame(listener, spy.installed);
        d.removeTraceListener(listener);
        assertSame(d.ringBuffer(), spy.installed);
    }

    @Test
    void withRingBufferOnlyHidesInstructionsFromOtherListenersAndRestoresThem() {
        Spy spy = new Spy();
        Debugger d = new Debugger(spy, null);
        InMemoryTrace listener = new InMemoryTrace();
        d.addTraceListener(listener);
        TraceListener outside = spy.installed;
        String result = d.withRingBufferOnly(() -> {
            assertSame(d.ringBuffer(), spy.installed);
            return "done";
        });
        assertEquals("done", result);
        assertNotNull(spy.installed);
        assertNotSame(d.ringBuffer(), spy.installed, "the listener is attached again");
        assertThrows(IllegalStateException.class, () -> d.withRingBufferOnly(() -> {
            throw new IllegalStateException("inside");
        }));
        assertNotSame(d.ringBuffer(), spy.installed, "restored even when the action throws");
        assertNotNull(outside);
    }

    @Test
    void everyListenerAndTheRingBufferSeeEachInstruction() {
        InMemoryTrace a = new InMemoryTrace();
        InMemoryTrace b = new InMemoryTrace();
        dbg.addTraceListener(a);
        dbg.addTraceListener(b);
        dbg.step(3);
        assertEquals(3, a.lines().size());
        assertEquals(3, b.lines().size());
        assertEquals(3, dbg.ringBuffer().size());
        dbg.removeTraceListener(a);
        dbg.step(2);
        assertEquals(3, a.lines().size(), "a removed listener sees nothing more");
        assertEquals(5, b.lines().size());
        assertEquals(5, dbg.ringBuffer().size());
        dbg.setRingBufferCapacity(0);
        dbg.step(1);
        assertEquals(6, b.lines().size());
        assertNull(dbg.ringBuffer());
        assertThrows(NullPointerException.class, () -> dbg.addTraceListener(null));
    }

    // ---- breakpoints and watchpoints ---------------------------------------------------------------------------------

    @Test
    void breakpointQueriesReflectAddAndRemove() {
        assertFalse(dbg.hasBreakpoint(5));
        dbg.addBreakpoint(5);
        assertTrue(dbg.hasBreakpoint(5));
        assertFalse(dbg.hasBreakpoint(6));
        dbg.removeBreakpoint(5);
        assertFalse(dbg.hasBreakpoint(5));
    }

    @Test
    void cycleBreakpointsCanBeRemoved() {
        dbg.addCycleBreakpoint(10);
        dbg.addCycleBreakpoint(20);
        dbg.removeCycleBreakpoint(10);
        assertEquals(List.of(20L), dbg.cycleBreakpoints());
        assertEquals(CYCLE_BREAKPOINT, dbg.run(1000).kind());
        assertTrue(cpu.cycles >= 20, "the removed breakpoint at 10 did not fire: " + cpu.cycles);
    }

    @Test
    void watchpointsAreListedAndRemovedWatchpointsStopFiring() {
        Watchpoint w = Watchpoint.memoryWrite(0x80);
        Watchpoint other = Watchpoint.ioOut(0x07);
        dbg.addWatchpoint(w);
        dbg.addWatchpoint(other);
        assertEquals(List.of(w, other), dbg.watchpoints());
        dbg.removeWatchpoint(w);
        assertEquals(List.of(other), dbg.watchpoints());
        // the write to 0x80 no longer stops the run; the OUT to port 7 still does
        StopReason r = dbg.run(1000);
        assertEquals(WATCHPOINT, r.kind());
        assertTrue(r.detail().startsWith("watch [IO_OUT] 0007: IO_OUT 0007 value "), r.detail());
        assertThrows(NullPointerException.class, () -> dbg.addWatchpoint(null));
    }

    @Test
    void clearingEverythingRemovesBreakpointsCycleBreakpointsAndWatchpoints() {
        dbg.addBreakpoint(0x04);
        dbg.addCycleBreakpoint(5);
        dbg.addWatchpoint(Watchpoint.memoryWrite(0x80));
        dbg.clearBreakpointsAndWatchpoints();
        assertTrue(dbg.breakpoints().isEmpty());
        assertTrue(dbg.cycleBreakpoints().isEmpty());
        assertTrue(dbg.watchpoints().isEmpty());
        StopReason r = dbg.run(60);
        assertEquals(CYCLE_LIMIT, r.kind(), "nothing left to stop for: " + r);
    }

    // ---- dumps --------------------------------------------------------------------------------------------------------

    @Test
    void dumpRangesNeedAMemoryViewAndCanBeCleared() {
        Debugger noMemory = new Debugger(new ToyCpu(), null);
        assertThrows(IllegalStateException.class, () -> noMemory.addDumpRange(0, 16));
        assertThrows(IllegalStateException.class, () -> noMemory.hexDump(0, 16));
        assertTrue(noMemory.dump().startsWith("--- dump at cycle 0 ---\nregisters: "), noMemory.dump());

        dbg.addDumpRange(0, 16);
        assertTrue(dbg.dump().contains("\nmemory 0000+16:\n0000: 00 03 02 80"), dbg.dump());
        dbg.clearDumpRanges();
        assertFalse(dbg.dump().contains("memory"), dbg.dump());
        assertEquals(dbg.hexDump(0, 4), "0000: 00 03 02 80" + " ".repeat(3 * 12 + 1) + "  |....|");
    }

    @Test
    void theDumpIntervalMustNotBeNegativeAndZeroDisablesIt() {
        assertThrows(IllegalArgumentException.class, () -> dbg.setDumpEvery(-1));
        List<String> dumps = new ArrayList<>();
        dbg.setDumpSink(dumps::add);
        dbg.setDumpEvery(8);
        dbg.run(40);
        assertEquals(5, dumps.size(), "one dump per 8 cycles over 40: " + dumps.size());
        dumps.clear();
        dbg.setDumpEvery(0);
        dbg.run(40);
        assertTrue(dumps.isEmpty());
        assertThrows(NullPointerException.class, () -> dbg.setDumpSink(null));
    }

    @Test
    void aDumpOnStopHappensForRealStopsButNotForPlainSteps() {
        List<String> dumps = new ArrayList<>();
        dbg.setDumpSink(dumps::add);
        dbg.setDumpOnStop(true);
        dbg.step(2);
        assertTrue(dumps.isEmpty());
        dbg.addBreakpoint(0x06);
        dbg.run(1000);
        assertEquals(1, dumps.size());
        assertTrue(dumps.get(0).startsWith("stopped: BREAKPOINT"), dumps.get(0));
        dbg.setDumpOnStop(false);
        dbg.run(5);
        assertEquals(1, dumps.size());
    }

    // ---- run control ----------------------------------------------------------------------------------------------------

    @Test
    void zeroOrNegativeStepCountsExecuteNothing() {
        assertEquals(STEPPED, dbg.step(0).kind());
        assertEquals(STEPPED, dbg.step(-3).kind());
        assertEquals(0, cpu.instructions);
        StopReason one = dbg.step(1);
        assertEquals(STEPPED, one.kind());
        assertEquals(1, cpu.instructions);
    }

    @Test
    void aRunStartingAtItsCycleTargetStopsAtOnce() {
        StopReason r = dbg.run(0);
        assertEquals(CYCLE_LIMIT, r.kind());
        assertEquals(0, cpu.instructions);
        assertEquals("cycle target 0 already reached", r.detail());
        assertEquals(STEPPED, dbg.stepCycles(0).kind());
        assertEquals(CONDITION, dbg.runUntil(() -> true, 1000).kind());
        assertThrows(NullPointerException.class, () -> dbg.runUntil(null, 5));
    }

    @Test
    void aCrashingTargetReportsWithTheSecondaryFailureAttached() {
        cpu.load(0x03, 0xFF);
        List<String> sink = new ArrayList<>();
        dbg.setDumpSink(sink::add);
        ExecutionFailure f = assertThrows(ExecutionFailure.class, () -> dbg.run(1000));
        assertTrue(f.getMessage().contains("target threw java.lang.IllegalStateException: crash opcode FF"),
                f.getMessage());
        assertEquals(1, sink.size());
        assertTrue(f.report().contains("--- last"), f.report());
        assertTrue(f.getCause() instanceof IllegalStateException);
        assertEquals(0, f.getCause().getSuppressed().length);

        // a snapshot that fails while the report is built is attached to the original failure
        Spy bad = new Spy() {
            @Override
            public int step() {
                throw new IllegalStateException("crash");
            }

            @Override
            public Snapshot snapshot() {
                throw new UnsupportedOperationException("no snapshot");
            }
        };
        Debugger d = new Debugger(bad, null);
        d.setDumpSink(s -> { });
        ExecutionFailure g = assertThrows(ExecutionFailure.class, () -> d.run(100));
        assertEquals(1, g.getCause().getSuppressed().length);
        assertTrue(g.report().contains("failure report unavailable: java.lang.UnsupportedOperationException: no snapshot"),
                g.report());
    }

    @Test
    void theFirstWatchedAccessOfAnInstructionIsTheOneReported() {
        dbg.addWatchpoint(Watchpoint.memoryRead(0x81));
        dbg.addWatchpoint(new Watchpoint(java.util.Set.of(AccessType.FETCH), 0x04, 0x05));
        // LDA 81 at 04 fetches its opcode and operand (both in 04..05) before it reads 81
        StopReason r = dbg.run(1000);
        assertEquals(WATCHPOINT, r.kind());
        assertTrue(r.detail().contains("FETCH 0004"), r.detail());
        assertFalse(r.detail().contains("READ"), r.detail());
    }

    @Test
    void aDumpOnStopCanBeSwitchedOff() {
        java.util.List<String> dumps = new java.util.ArrayList<>();
        dbg.setDumpSink(dumps::add);
        dbg.setDumpOnStop(true);
        dbg.setDumpOnStop(false);
        dbg.addBreakpoint(0x04);
        assertEquals(BREAKPOINT, dbg.run(1000).kind());
        assertTrue(dumps.isEmpty());
    }
}
