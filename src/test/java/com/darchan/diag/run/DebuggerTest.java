package com.darchan.diag.run;

import com.darchan.diag.trace.InMemoryTrace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.darchan.diag.run.StopReason.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class DebuggerTest {

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

    @Test
    void breakpointsCanBeListed() {
        dbg.addBreakpoint(0x09);
        dbg.addBreakpoint(0x02);
        dbg.addCycleBreakpoint(100);
        assertEquals(List.of(0x02, 0x09), dbg.breakpoints());
        assertEquals(List.of(100L), dbg.cycleBreakpoints());
        dbg.removeBreakpoint(0x02);
        assertEquals(List.of(0x09), dbg.breakpoints());
    }

    @Test
    void stepExecutesOneInstruction() {
        StopReason r = dbg.step();
        assertEquals(STEPPED, r.kind());
        assertEquals(1, cpu.instructions);
        assertEquals(4, r.cycle());
        assertEquals(1, r.pc());
        assertEquals(STEPPED, dbg.step(3).kind());
        assertEquals(4, cpu.instructions);
        assertEquals(6, cpu.pc);
    }

    @Test
    void stepCyclesFinishesTheCurrentInstruction() {
        StopReason r = dbg.stepCycles(10); // NOP(4) INC(4) STA(7) -> 15 >= 10
        assertEquals(STEPPED, r.kind());
        assertEquals(15, cpu.cycles);
        assertEquals(STEPPED, dbg.stepCycles(0).kind());
        assertEquals(15, cpu.cycles);
    }

    @Test
    void pcBreakpointStopsBeforeTheInstruction() {
        dbg.addBreakpoint(0x06);
        StopReason r = dbg.run(1000);
        assertEquals(BREAKPOINT, r.kind());
        assertEquals(0x06, cpu.pc);
        assertEquals(4, cpu.instructions);
        // continuing steps over the breakpoint and comes back round the loop
        r = dbg.run(1000);
        assertEquals(BREAKPOINT, r.kind());
        assertEquals(0x06, cpu.pc);
        assertEquals(11, cpu.instructions);
        dbg.removeBreakpoint(0x06);
        assertEquals(CYCLE_LIMIT, dbg.run(100).kind());
    }

    @Test
    void memoryWriteWatchpoint() {
        dbg.addWatchpoint(Watchpoint.memoryWrite(0x80));
        StopReason r = dbg.run(1000);
        assertEquals(WATCHPOINT, r.kind());
        assertEquals(0x04, cpu.pc, "stops after the writing instruction");
        assertTrue(r.detail().contains("WRITE 0080 value 01"), r.detail());
    }

    @Test
    void memoryReadWatchpointIgnoresFetches() {
        dbg.addWatchpoint(Watchpoint.memoryRead(0x00, 0x7F)); // covers the code, but fetches are not reads
        assertEquals(CYCLE_LIMIT, dbg.run(200).kind());
        dbg.addWatchpoint(Watchpoint.memoryRead(0x81));
        StopReason r = dbg.run(1000);
        assertEquals(WATCHPOINT, r.kind());
        assertEquals(0x06, r.pc());
    }

    @Test
    void fetchAndIoWatchpoints() {
        dbg.addWatchpoint(Watchpoint.ioOut(0x07));
        assertEquals(0x08, dbg.run(1000).pc());
        dbg.clearBreakpointsAndWatchpoints();
        dbg.addWatchpoint(new Watchpoint(java.util.Set.of(AccessType.FETCH), 0x09, 0x09));
        StopReason r = dbg.run(1000);
        assertEquals(WATCHPOINT, r.kind());
        assertEquals(0x00, r.pc());
    }

    @Test
    void cycleBreakpoint() {
        dbg.addCycleBreakpoint(20);
        StopReason r = dbg.run(1000);
        assertEquals(CYCLE_BREAKPOINT, r.kind());
        assertEquals(22, r.cycle()); // 4+4+7+7
        assertEquals(CYCLE_LIMIT, dbg.run(100).kind(), "does not fire again");
    }

    @Test
    void runUntilCondition() {
        StopReason r = dbg.runUntil(() -> cpu.pc == 0x08 && cpu.instructions > 10, 10_000);
        assertEquals(CONDITION, r.kind());
        assertEquals(12, cpu.instructions);
    }

    @Test
    void stopsOnHaltTransitionAndIdles() {
        cpu.load(0x03, 0x04);
        StopReason r = dbg.run(1000);
        assertEquals(HALTED, r.kind());
        assertEquals(8, r.cycle());
        // already halted: a further run idles until the budget runs out
        r = dbg.run(40);
        assertEquals(CYCLE_LIMIT, r.kind());
        assertEquals(48, r.cycle());
        assertEquals(2, cpu.instructions);
        dbg.setStopOnHalt(false);
        cpu.halted = false;
        cpu.pc = 1;
        assertEquals(CYCLE_LIMIT, dbg.run(40).kind());
    }

    @Test
    void exceptionsAreWrappedWithAFailureReport() {
        cpu.load(0x03, 0x03, 0xFF);
        dbg.addDumpRange(0x00, 4);
        List<String> sink = new ArrayList<>();
        dbg.setDumpSink(sink::add);
        ExecutionFailure f = assertThrows(ExecutionFailure.class, () -> dbg.run(1000));
        assertInstanceOf(IllegalStateException.class, f.getCause());
        assertTrue(f.getMessage().contains("crash opcode FF"));
        String report = f.report();
        assertTrue(report.contains("--- last 3 of 3 traced instruction(s) (toy) ---"), report);
        assertTrue(report.contains("000000000004 000000000001 01: 03     INC      A=01 F=00 Z=-"), report);
        assertTrue(report.contains("000000000008 000000000002 02: FF     ???      A=02 F=00 Z=-"), report);
        assertTrue(report.contains("registers: PC=03 A=02 F=00 HALT=0 CYCLES=8 INSTRUCTIONS=2"), report);
        assertTrue(report.contains("0000: 03 03 FF 80"), report);
        assertEquals(1, sink.size());
    }

    @Test
    void dumpsOnStopAndPeriodically() {
        List<String> sink = new ArrayList<>();
        dbg.setDumpSink(sink::add);
        dbg.setDumpOnStop(true);
        dbg.addBreakpoint(0x04);
        dbg.run(1000);
        assertEquals(1, sink.size());
        assertTrue(sink.get(0).startsWith("stopped: BREAKPOINT"), sink.get(0));
        sink.clear();
        dbg.clearBreakpointsAndWatchpoints();
        dbg.setDumpOnStop(false);
        dbg.setDumpEvery(100);
        dbg.run(1000);
        assertEquals(10, sink.size(), "one dump per 100 cycles");
        assertTrue(sink.get(0).startsWith("--- dump at cycle 1"), sink.get(0));
    }

    @Test
    void traceListenersAndRingBuffer() {
        InMemoryTrace trace = new InMemoryTrace();
        dbg.addTraceListener(trace);
        dbg.step(3);
        assertEquals(3, trace.lines().size());
        assertEquals("000000000000 000000000000 00: 00     NOP      A=00 F=00 Z=-", trace.lines().get(0));
        assertEquals(trace.lines(), dbg.ringBuffer().lines());
        dbg.removeTraceListener(trace);
        dbg.setRingBufferCapacity(0);
        assertNull(dbg.ringBuffer());
        dbg.step();
        assertEquals(3, trace.lines().size());
        assertFalse(dbg.failureReport().contains("traced instruction"));
    }

    @Test
    void hexDumpAndSnapshotRestore() {
        assertEquals("0000: 00 03 02 80", dbg.hexDump(0, 4).substring(0, 17));
        var before = cpu.snapshot();
        dbg.step(5);
        assertFalse(before.diff(cpu.snapshot()).isEmpty());
        cpu.restore(before);
        assertTrue(before.diff(cpu.snapshot()).isEmpty());
    }
}
