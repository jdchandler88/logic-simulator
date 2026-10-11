package com.darchan.diag.compare;

import com.darchan.diag.run.Debugger;
import com.darchan.diag.run.StopReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GoldenTraceTest {

    @TempDir
    Path dir;

    private Path recordTiny(int steps) {
        Path file = dir.resolve("tiny.trace");
        TinyMachine m = new TinyMachine();
        Debugger dbg = new Debugger(m, null);
        dbg.addCycleBreakpoint(stepsToCycles(steps));
        StopReason stop = GoldenTrace.record(dbg, file, 1_000_000);
        assertEquals(StopReason.Kind.CYCLE_BREAKPOINT, stop.kind());
        return file;
    }

    private static long stepsToCycles(int steps) {
        TinyMachine m = new TinyMachine();
        for (int i = 0; i < steps; i++) {
            m.step();
        }
        return m.cycles;
    }

    @Test
    void recordWritesAHeaderAndOneLinePerInstruction() throws IOException {
        List<String> lines = Files.readAllLines(recordTiny(100));
        assertEquals(101, lines.size());
        assertEquals("# tiny trace: cycle instruction pc: bytes  disassembly ACC", lines.get(0));
        assertEquals("000000000000 000000000000 00: 00  ADD PC ACC=00", lines.get(1));
    }

    @Test
    void aFaithfulRunMatchesEveryLine() {
        Path golden = recordTiny(100);
        TinyMachine m = new TinyMachine();
        GoldenTraceResult r = GoldenTrace.of(golden).compare(new Debugger(m, null), 1_000_000);
        assertTrue(r.matched(), r::report);
        assertTrue(r.complete(), r::report);
        assertEquals(100, r.linesCompared());
        assertEquals(100, m.steps, "stops once the golden trace is used up");
        assertEquals("golden trace tiny.trace: all 100 line(s) matched", r.report());
    }

    @Test
    void aFaultIsReportedWithLineNumberColumnsMarkersAndContext() {
        Path golden = recordTiny(100);
        TinyMachine m = new TinyMachine();
        m.faultAtStep = 41;
        m.faultMask = 0x10;
        GoldenTraceResult r = GoldenTrace.of(golden).contextLines(4).compare(new Debugger(m, null), 1_000_000);
        assertFalse(r.matched());
        TraceMismatch mm = r.mismatch();
        // instruction 42's line shows the bad ACC produced by instruction 41; header is line 1
        assertEquals(44, mm.lineNumber());
        assertEquals(42, mm.instruction());
        assertEquals(List.of("ACC: expected 5D, actual 4D"), mm.differingColumns());
        assertEquals("000000000168 000000000042 2A: 2A  ADD PC ACC=5D", mm.expected());
        assertEquals("000000000168 000000000042 2A: 2A  ADD PC ACC=4D", mm.actual());
        assertEquals(" ".repeat(mm.actual().length() - 2) + "^", mm.markers());
        assertEquals(4, mm.context().size());
        assertTrue(mm.context().get(3).startsWith("000000000163 000000000041 29:"), mm::report);
        assertEquals(3, mm.following().size());
        assertTrue(mm.following().get(0).startsWith("000000000171 000000000043 2B:"), mm::report);
        assertEquals(43, m.steps, "the run stops right after the mismatching line's instruction");
        String report = r.report();
        assertTrue(report.contains("GOLDEN TRACE MISMATCH at line 44 of tiny.trace (instruction 42)"), report);
    }

    @Test
    void ignoredColumnsAreNotCompared() {
        Path golden = recordTiny(50);
        TinyMachine slow = new TinyMachine();
        slow.extraCycles = 2;
        GoldenTraceResult r = GoldenTrace.of(golden).compare(new Debugger(slow, null), 1_000_000);
        assertFalse(r.matched());
        assertEquals(List.of("cycle: expected 000000000003, actual 000000000005"), r.mismatch().differingColumns());
        assertEquals("           ^", r.mismatch().markers());

        TinyMachine slow2 = new TinyMachine();
        slow2.extraCycles = 2;
        r = GoldenTrace.of(golden).ignoreColumns("cycle").compare(new Debugger(slow2, null), 1_000_000);
        assertTrue(r.matched(), r::report);
        assertTrue(r.complete());
    }

    @Test
    void aLiveRunThatStopsEarlyIsIncomplete() {
        Path golden = recordTiny(30);
        TinyMachine m = new TinyMachine();
        Debugger dbg = new Debugger(m, null);
        dbg.addBreakpoint(10);
        GoldenTraceResult r = GoldenTrace.of(golden).compare(dbg, 1_000_000);
        assertTrue(r.matched());
        assertFalse(r.complete());
        assertEquals(10, r.linesCompared());
        assertEquals(StopReason.Kind.BREAKPOINT, r.stop().kind());
        assertTrue(r.nextExpectedLine().startsWith("000000000040 000000000010 0A:"), r::report);
        assertTrue(r.report().contains("before the golden trace ended"), r::report);
    }

    @Test
    void commentsAndBlankLinesInTheGoldenFileAreSkipped() {
        String text = "# header\n\n000000000000 000000000000 00: 00  ADD PC ACC=00\n# note\n"
                + "000000000003 000000000001 01: 01  ADD PC ACC=00\n";
        TinyMachine m = new TinyMachine();
        GoldenTraceResult r = GoldenTrace.ofText("inline", text).compare(new Debugger(m, null), 1000);
        assertTrue(r.matched() && r.complete(), r::report);
        assertEquals(2, r.linesCompared());
    }
}
