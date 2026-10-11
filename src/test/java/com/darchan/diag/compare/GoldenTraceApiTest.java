package com.darchan.diag.compare;

import com.darchan.diag.run.Debugger;
import com.darchan.diag.run.StopReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The golden trace workflow as a caller sees it: names, options, empty traces and cleanup after a run. */
class GoldenTraceApiTest {

    @TempDir
    Path dir;

    private static final String L0 = "000000000000 000000000000 00: 00  ADD PC ACC=00";
    private static final String L1 = "000000000003 000000000001 01: 01  ADD PC ACC=00";

    @Test
    void theSourceIsTheFileNameOrTheGivenName() {
        assertEquals("run.trace", GoldenTrace.of(dir.resolve("run.trace")).source());
        assertEquals("inline", GoldenTrace.ofText("inline", L0).source());
        assertEquals("reader", GoldenTrace.of("reader", new StringReader(L0)).source());
        try (GoldenTraceComparator c = GoldenTrace.ofText("inline", L0).comparator()) {
            assertEquals("inline", c.source());
        }
    }

    @Test
    void contextLinesMayBeZeroButNotNegative() {
        assertThrows(IllegalArgumentException.class, () -> GoldenTrace.ofText("x", L0).contextLines(-1));
        GoldenTrace.ofText("x", L0).contextLines(0);
        TinyMachine m = new TinyMachine();
        m.faultAtStep = 1; // the third line shows the wrong ACC
        m.faultMask = 1;
        String l2 = "000000000008 000000000002 02: 02  ADD PC ACC=01";
        GoldenTraceResult r = GoldenTrace.ofText("x", L0 + "\n" + L1 + "\n" + l2 + "\n").contextLines(0)
                .compare(new Debugger(m, null), 100);
        assertFalse(r.matched());
        assertTrue(r.mismatch().context().isEmpty(), "no context lines were asked for");
        GoldenTraceResult ok = GoldenTrace.ofText("x", L0 + "\n" + L1 + "\n").contextLines(0)
                .compare(new Debugger(new TinyMachine(), null), 100);
        assertTrue(ok.matched() && ok.complete(), ok::report);
    }

    @Test
    void anEmptyGoldenTraceIsTriviallyCompleteAndRunsNothing() {
        TinyMachine m = new TinyMachine();
        for (String text : new String[]{"", "# only a header\n", "\n\n"}) {
            GoldenTraceResult r = GoldenTrace.ofText("empty", text).compare(new Debugger(m, null), 100);
            assertTrue(r.matched() && r.complete() && r.verified(), r::report);
            assertEquals(0, r.linesCompared());
            assertEquals(StopReason.Kind.STEPPED, r.stop().kind());
            assertEquals("golden trace is empty", r.stop().detail());
            assertEquals("empty", r.source());
            assertNull(r.nextExpectedLine());
            assertNull(r.mismatch());
            assertEquals("golden trace empty: all 0 line(s) matched", r.report());
            assertEquals(r.report(), r.toString());
        }
        assertEquals(0, m.steps, "nothing was executed");
    }

    @Test
    void theComparatorIsDetachedAfterTheRunWhateverTheOutcome() {
        TinyMachine m = new TinyMachine();
        Debugger dbg = new Debugger(m, null);
        GoldenTraceResult r = GoldenTrace.ofText("x", L0 + "\n" + L1 + "\n").compare(dbg, 100);
        assertTrue(r.verified(), r::report);
        // running on must not feed a closed comparator (its reader is closed): nothing throws
        assertDoesNotThrow(() -> dbg.step(5));
        // a mismatching run also detaches
        TinyMachine bad = new TinyMachine();
        bad.faultAtStep = 0;
        bad.faultMask = 1;
        Debugger badDbg = new Debugger(bad, null);
        GoldenTraceResult mismatch = GoldenTrace.ofText("x", L0 + "\n" + L1 + "\n").compare(badDbg, 100);
        assertFalse(mismatch.matched());
        assertDoesNotThrow(() -> badDbg.step(5));
        // and a run that ends early
        TinyMachine early = new TinyMachine();
        Debugger earlyDbg = new Debugger(early, null);
        earlyDbg.addBreakpoint(1);
        GoldenTrace.ofText("x", L0 + "\n" + L1 + "\n" + L1 + "\n").compare(earlyDbg, 100);
        assertDoesNotThrow(() -> earlyDbg.step(5));
    }

    @Test
    void theNextExpectedLineIsOnlyReportedForARunThatStoppedEarlyAndMatched() {
        TinyMachine m = new TinyMachine();
        Debugger dbg = new Debugger(m, null);
        dbg.addBreakpoint(1);
        GoldenTraceResult early = GoldenTrace.ofText("x", L0 + "\n" + L1 + "\n").compare(dbg, 100);
        assertTrue(early.matched());
        assertFalse(early.complete());
        assertEquals(L1, early.nextExpectedLine());
        TinyMachine bad = new TinyMachine();
        bad.faultAtStep = 0;
        bad.faultMask = 1;
        GoldenTraceResult mismatch = GoldenTrace.ofText("x", L0 + "\n" + L1 + "\n").compare(new Debugger(bad, null), 100);
        assertNull(mismatch.nextExpectedLine(), "a mismatch carries its own expected line");
        GoldenTraceResult done = GoldenTrace.ofText("x", L0 + "\n").compare(new Debugger(new TinyMachine(), null), 100);
        assertNull(done.nextExpectedLine());
        assertEquals("golden trace x: all 1 line(s) matched", done.report());
    }

    @Test
    void recordingDetachesItsListenerSoALaterRunDoesNotWriteToTheClosedFile() throws IOException {
        TinyMachine m = new TinyMachine();
        Debugger dbg = new Debugger(m, null);
        dbg.addCycleBreakpoint(20);
        Path file = dir.resolve("r.trace");
        GoldenTrace.record(dbg, file, 1000);
        int lines = Files.readAllLines(file).size();
        assertDoesNotThrow(() -> dbg.step(3));
        assertEquals(lines, Files.readAllLines(file).size());
        assertTrue(Files.readAllLines(file).get(0).startsWith("# tiny trace:"));
    }

    @Test
    void ignoredColumnsAreCopiedIntoTheComparator() {
        try (GoldenTraceComparator c = GoldenTrace.ofText("x", L1).ignoreColumns("cycle", "instruction").comparator()) {
            c.onInstruction(new TinyMachineEvent().at(999, 77, 1, 0));
            assertFalse(c.mismatched());
        }
    }

    @Test
    void lineNumbersIgnoreBlankAndCommentLinesButCountThem() {
        try (GoldenTraceComparator c = new GoldenTraceComparator("t", new java.io.BufferedReader(
                new StringReader("#\n\n  \n" + L0 + "\n")), Set.of(), 0)) {
            assertEquals(4, c.nextExpectedLineNumber());
            assertEquals(L0, c.nextExpectedLine());
        }
        assertEquals(List.of(), List.of());
    }

    /** builds trace events like the tiny machine's */
    private static final class TinyMachineEvent {
        com.darchan.diag.trace.TraceEvent at(long cycle, long instruction, int pc, int acc) {
            com.darchan.diag.trace.TraceEvent e = new com.darchan.diag.trace.TraceEvent(TinyMachine.LAYOUT,
                    (a, b, l) -> "ADD PC");
            e.begin(cycle, instruction, pc, false);
            e.setBytes(pc, 1);
            e.setRegister(0, acc);
            return e;
        }
    }
}
