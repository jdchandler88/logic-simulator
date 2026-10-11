package com.darchan.behavioral.i8080;

import com.darchan.behavioral.bus.IoBus;
import com.darchan.behavioral.bus.Ram;
import com.darchan.diag.Snapshot;
import com.darchan.diag.SnapshotDiff;
import com.darchan.diag.SnapshotJson;
import com.darchan.diag.run.Debugger;
import com.darchan.diag.run.StopReason;
import com.darchan.diag.run.Watchpoint;
import com.darchan.diag.trace.InMemoryTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.darchan.diag.run.StopReason.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/** The 8080 core driven through the CPU-agnostic diagnostics layer. */
class Debug8080Test {

    private final Ram ram = new Ram();
    private final Cpu8080 cpu = new Cpu8080(ram, IoBus.UNCONNECTED);

    /**
     * <pre>
     * 0100: 31 00 20  LXI SP,2000H
     * 0103: 3E 41     MVI A,41H
     * 0105: 32 00 30  STA 3000H
     * 0108: 3A 01 30  LDA 3001H
     * 010B: D3 07     OUT 07H
     * 010D: CD 20 01  CALL 0120H
     * 0110: 76        HLT
     * 0120: 3C        INR A
     * 0121: C9        RET
     * </pre>
     */
    private void loadProgram() {
        ram.load(0x100, bytes(0x31, 0x00, 0x20, 0x3E, 0x41, 0x32, 0x00, 0x30, 0x3A, 0x01, 0x30, 0xD3, 0x07,
                0xCD, 0x20, 0x01, 0x76));
        ram.load(0x120, bytes(0x3C, 0xC9));
        ram.write(0x3001, 0x7F);
        cpu.setPC(0x100);
    }

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            b[i] = (byte) values[i];
        }
        return b;
    }

    @Test
    void traceLinesHaveTheDocumentedFormat() {
        loadProgram();
        Debugger dbg = Debug8080.attach(cpu);
        InMemoryTrace trace = new InMemoryTrace();
        dbg.addTraceListener(trace);
        StopReason stop = dbg.run(1000);
        assertEquals(HALTED, stop.kind());
        assertEquals(List.of(
                "000000000000 000000000000 0100: 31 00 20  LXI SP,2000H   A=00 F=02 B=00 C=00 D=00 E=00 H=00 L=00 SP=0000 SZAPC=-----",
                "000000000010 000000000001 0103: 3E 41     MVI A,41H      A=00 F=02 B=00 C=00 D=00 E=00 H=00 L=00 SP=2000 SZAPC=-----",
                "000000000017 000000000002 0105: 32 00 30  STA 3000H      A=41 F=02 B=00 C=00 D=00 E=00 H=00 L=00 SP=2000 SZAPC=-----",
                "000000000030 000000000003 0108: 3A 01 30  LDA 3001H      A=41 F=02 B=00 C=00 D=00 E=00 H=00 L=00 SP=2000 SZAPC=-----",
                "000000000043 000000000004 010B: D3 07     OUT 07H        A=7F F=02 B=00 C=00 D=00 E=00 H=00 L=00 SP=2000 SZAPC=-----",
                "000000000053 000000000005 010D: CD 20 01  CALL 0120H     A=7F F=02 B=00 C=00 D=00 E=00 H=00 L=00 SP=2000 SZAPC=-----",
                "000000000070 000000000006 0120: 3C        INR A          A=7F F=02 B=00 C=00 D=00 E=00 H=00 L=00 SP=1FFE SZAPC=-----",
                "000000000075 000000000007 0121: C9        RET            A=80 F=92 B=00 C=00 D=00 E=00 H=00 L=00 SP=1FFE SZAPC=S-A--",
                "000000000085 000000000008 0110: 76        HLT            A=80 F=92 B=00 C=00 D=00 E=00 H=00 L=00 SP=2000 SZAPC=S-A--"),
                trace.lines());
        assertEquals(trace.lines(), dbg.ringBuffer().lines());
    }

    @Test
    void interruptInjectionIsMarkedInTheTrace() {
        ram.load(0, bytes(0xFB, 0x00, 0x00));
        Debugger dbg = Debug8080.attach(cpu);
        InMemoryTrace trace = new InMemoryTrace();
        dbg.addTraceListener(trace);
        cpu.setSP(0x2000);
        dbg.step(2);
        cpu.requestRst(1);
        dbg.step();
        assertTrue(trace.lines().get(2).startsWith("000000000008 000000000002 0002: CF        INT RST 1      A=00"),
                trace.lines().get(2));
    }

    @Test
    void breakpointsAndWatchpointsOnARealCore() {
        loadProgram();
        Debugger dbg = Debug8080.attach(cpu);
        dbg.addBreakpoint(0x0120);
        dbg.addWatchpoint(Watchpoint.memoryWrite(0x3000));
        dbg.addWatchpoint(Watchpoint.memoryRead(0x3001));
        dbg.addWatchpoint(Watchpoint.ioOut(0x07));

        StopReason r = dbg.run(1000);
        assertEquals(WATCHPOINT, r.kind());
        assertEquals(0x0108, r.pc());
        assertTrue(r.detail().contains("WRITE 3000 value 41"), r.detail());

        r = dbg.run(1000);
        assertEquals(WATCHPOINT, r.kind());
        assertEquals(0x010B, r.pc());
        assertTrue(r.detail().contains("READ 3001 value 7F"), r.detail());

        r = dbg.run(1000);
        assertEquals(WATCHPOINT, r.kind());
        assertTrue(r.detail().contains("IO_OUT 0007 value 7F"), r.detail());

        r = dbg.run(1000);
        assertEquals(BREAKPOINT, r.kind());
        assertEquals(0x0120, cpu.pc());

        assertEquals(HALTED, dbg.run(1000).kind());
    }

    @Test
    void stackWritesCanBeWatched() {
        loadProgram();
        Debugger dbg = Debug8080.attach(cpu);
        dbg.addWatchpoint(Watchpoint.memoryWrite(0x1FFE, 0x1FFF));
        StopReason r = dbg.run(1000);
        assertEquals(WATCHPOINT, r.kind());
        assertEquals(0x0120, r.pc(), "the CALL pushed the return address");
        assertTrue(r.detail().contains("WRITE 1FFF value 01"), "high byte is written first: " + r.detail());
    }

    @Test
    void stepNCyclesAndRunUntil() {
        loadProgram();
        Debugger dbg = Debug8080.attach(cpu);
        assertEquals(STEPPED, dbg.stepCycles(20).kind());
        assertEquals(30, cpu.cycleCount(), "LXI 10 + MVI 7 + STA 13 crosses 20");
        StopReason r = dbg.runUntil(() -> cpu.a() == 0x80, 1000);
        assertEquals(CONDITION, r.kind());
        assertEquals(0x0121, cpu.pc());
    }

    @Test
    void snapshotsDiffAndRoundTripThroughJson() {
        loadProgram();
        Debugger dbg = Debug8080.attach(cpu);
        Snapshot before = cpu.snapshot();
        assertEquals("i8080", before.machine());
        assertEquals(List.of("PC", "SP", "A", "F", "B", "C", "D", "E", "H", "L", "INTE", "HALT", "EI_PENDING",
                "CYCLES", "INSTRUCTIONS"), before.fieldNames());
        dbg.step(2);
        Snapshot after = cpu.snapshot().withLabel("after 2").withRegion("stack", ram::peek, 0x1FF0, 16);
        SnapshotDiff diff = before.diff(after, Set.of("CYCLES", "INSTRUCTIONS"));
        assertEquals("""
                3 field(s) and 0 memory byte(s) differ (expected vs actual "after 2")
                  region stack[1FF0+16] missing from expected
                  PC          expected=0100  actual=0105
                  SP          expected=0000  actual=2000
                  A           expected=00  actual=41""", diff.report());

        Snapshot back = SnapshotJson.fromJson(SnapshotJson.toJson(after));
        assertEquals(after, back);

        cpu.restore(before);
        assertTrue(before.diff(cpu.snapshot()).isEmpty());
        assertEquals(0x100, cpu.pc());
        cpu.restore(back);
        assertEquals(0x41, cpu.a());
        assertThrows(IllegalArgumentException.class, () -> cpu.restore(Snapshot.builder("sm83").build()));
    }

    @Test
    void failureReportContainsTraceAndRegisters() {
        loadProgram();
        Debugger dbg = Debug8080.attach(cpu);
        dbg.addDumpRange(0x0100, 0x20);
        dbg.run(1000);
        String report = dbg.failureReport();
        assertTrue(report.startsWith("--- last 9 of 9 traced instruction(s) (i8080) ---\n"), report);
        assertTrue(report.contains("registers: PC=0111 SP=2000 A=80 F=92"), report);
        assertTrue(report.contains("0100: 31 00 20 3E 41 32 00 30  3A 01 30 D3 07 CD 20 01  |1. >A2.0:.0... .|"), report);
    }

    @Test
    void noDebuggerMeansNoTracing() {
        loadProgram();
        Debugger dbg = Debug8080.attachWithoutWatchpoints(cpu);
        dbg.setRingBufferCapacity(0);
        dbg.run(1000);
        assertEquals(9, cpu.instructionCount());
        assertSame(ram, cpu.memory(), "no bus decorators");
    }
}
