package com.darchan.diag.compare;

import com.darchan.diag.Snapshot;
import com.darchan.diag.SnapshotDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The divergence report is what a person reads when a comparison fails, so its text is pinned exactly for each shape:
 * initial state, per-step, window not narrowed, narrowed, with an exception, and with and without a trace.
 */
class DivergenceReportTest {

    private static Divergence.SideState side(String name, long instructions, long cycles, int pc, String... trace) {
        return new Divergence.SideState(name, instructions, cycles, pc, List.of(trace));
    }

    private static SnapshotDiff diff() {
        Snapshot a = Snapshot.builder("m").label("a").hex("A", 8, 1).build();
        Snapshot b = Snapshot.builder("m").label("b").hex("A", 8, 2).build();
        return a.diff(b);
    }

    private static final String DIFF_REPORT = diff().report();

    @Test
    void aPerStepDivergenceListsPositionDiffAndBothTraces() {
        Divergence d = new Divergence(7, 7, 6,
                side("ref", 7, 30, 0x0107, "line r1", "line r2"), side("cand", 7, 31, 0x0107, "line c1"),
                diff(), null);
        assertEquals("LOCKSTEP DIVERGENCE after step 7 (comparison #7, last match after step 6)\n"
                + "  reference \"ref\":         instruction 7, cycle 30, PC=0107\n"
                + "  candidate \"cand\":        instruction 7, cycle 31, PC=0107\n"
                + "--- state diff (expected = reference, actual = candidate) ---\n" + DIFF_REPORT + "\n"
                + "--- reference \"ref\": last 2 instruction(s), '>' = executed last ---\n"
                + "  line r1\n"
                + "> line r2\n"
                + "--- candidate \"cand\": last 1 instruction(s), '>' = executed last ---\n"
                + "> line c1", d.report());
        assertEquals(d.report(), d.toString());
        assertEquals(OptionalLong.of(7), d.firstStep());
        assertSame(d.diff(), d.snapshotDiff().orElseThrow());
        assertTrue(d.failureMessage().isEmpty());
    }

    @Test
    void noEarlierMatchAndNoTraceAreSpelledOut() {
        Divergence d = new Divergence(1, 1, -1, side("r", 1, 3, 1), side("c", 1, 3, 1), diff(), null);
        assertTrue(d.report().startsWith("LOCKSTEP DIVERGENCE after step 1 (comparison #1, no earlier match)\n"),
                d::report);
        assertTrue(d.report().contains("--- reference \"r\": no trace (ring buffer disabled) ---\n"), d::report);
        assertTrue(d.report().endsWith("--- candidate \"c\": no trace (ring buffer disabled) ---"), d::report);
    }

    @Test
    void theInitialStateAndAnExceptionHaveTheirOwnShape() {
        Divergence d = new Divergence(0, 0, -1, side("r", 0, 0, 0), side("c", 0, 0, 0), null,
                "candidate \"c\" threw java.lang.IllegalStateException: boom");
        assertEquals("LOCKSTEP DIVERGENCE in the initial state (before any step)\n"
                + "  reference \"r\":           instruction 0, cycle 0, PC=0000\n"
                + "  candidate \"c\":           instruction 0, cycle 0, PC=0000\n"
                + "--- failure ---\ncandidate \"c\" threw java.lang.IllegalStateException: boom\n"
                + "--- reference \"r\": no trace (ring buffer disabled) ---\n"
                + "--- candidate \"c\": no trace (ring buffer disabled) ---", d.report());
        assertEquals("candidate \"c\" threw java.lang.IllegalStateException: boom", d.failureMessage().orElseThrow());
        assertTrue(d.snapshotDiff().isEmpty());
    }

    @Test
    void aWindowThatCouldNotBeNarrowedSaysWhere() {
        Narrowing n = Narrowing.notNarrowed("every 10 step(s)", 40, 50, "restore: nothing to restore");
        Divergence d = new Divergence(50, 5, 40, side("r", 50, 200, 0x32), side("c", 50, 200, 0x32), diff(), null, n);
        assertEquals(Narrowing.Status.NOT_NARROWED, d.narrowing().status());
        assertEquals(OptionalLong.empty(), d.firstStep());
        assertEquals("LOCKSTEP DIVERGENCE after step 50 (comparison #5, last match after step 40)\n"
                + "  reference \"r\":           instruction 50, cycle 200, PC=0032\n"
                + "  candidate \"c\":           instruction 50, cycle 200, PC=0032\n"
                + "  NOT NARROWED: the first difference is somewhere in steps 41..50 (compared every 10 step(s)):"
                + " restore: nothing to restore\n"
                + "--- state diff (expected = reference, actual = candidate) ---\n" + DIFF_REPORT + "\n"
                + "--- reference \"r\": no trace (ring buffer disabled) ---\n"
                + "--- candidate \"c\": no trace (ring buffer disabled) ---", d.report());
    }

    @Test
    void aNarrowedDivergenceLeadsWithTheFirstDivergingStep() {
        Divergence.SideState rAt = side("r", 42, 168, 0x2A, "r trace");
        Divergence.SideState cAt = side("c", 42, 168, 0x2A, "c trace");
        Narrowing n = new Narrowing(Narrowing.Status.NARROWED, Narrowing.Method.RESTORE, "every 10 step(s)", 40, 50, 42,
                rAt, cAt, diff(), null, "restore: a note");
        Divergence d = new Divergence(50, 5, 40, side("r", 50, 200, 0x32, "r late"), side("c", 50, 200, 0x32, "c late"),
                diff(), null, n);
        assertEquals(OptionalLong.of(42), d.firstStep());
        assertEquals("LOCKSTEP DIVERGENCE after step 42 (narrowed by restore from steps 41..50: compared every 10"
                + " step(s), seen at comparison #5 after step 50, last match after step 40)\n"
                + "  reference \"r\":           instruction 42, cycle 168, PC=002A\n"
                + "  candidate \"c\":           instruction 42, cycle 168, PC=002A\n"
                + "  note: restore: a note\n"
                + "--- state diff after step 42 (expected = reference, actual = candidate) ---\n" + DIFF_REPORT + "\n"
                + "--- coarse comparison #5 after step 50 (every 10 step(s)) ---\n"
                + "  reference \"r\":           instruction 50, cycle 200, PC=0032\n"
                + "  candidate \"c\":           instruction 50, cycle 200, PC=0032\n" + DIFF_REPORT + "\n"
                + "--- reference \"r\": last 1 instruction(s), '>' = executed last ---\n"
                + "> r trace\n"
                + "--- candidate \"c\": last 1 instruction(s), '>' = executed last ---\n"
                + "> c trace", d.report());
    }

    @Test
    void aNarrowedExceptionReportsTheFailureOfThatStepAndTheCoarseFailure() {
        Divergence.SideState rAt = side("r", 46, 180, 0x2E);
        Divergence.SideState cAt = side("c", 45, 177, 0x2D);
        Narrowing n = new Narrowing(Narrowing.Status.NARROWED, Narrowing.Method.REPLAY, "every 10 cycle(s)", 26, 50, 46,
                rAt, cAt, null, "candidate \"c\" threw X", "");
        Divergence d = new Divergence(50, 2, 26, side("r", 50, 200, 0x32), side("c", 49, 197, 0x31), null,
                "candidate \"c\" threw X", n);
        String report = d.report();
        assertTrue(report.startsWith("LOCKSTEP DIVERGENCE after step 46 (narrowed by replay from steps 27..50: compared"
                + " every 10 cycle(s), seen at comparison #2 after step 50, last match after step 26)\n"), report);
        assertFalse(report.contains("  note:"), "no note when there is none");
        assertTrue(report.contains("--- failure during step 46 ---\ncandidate \"c\" threw X\n"
                + "--- coarse comparison #2 after step 50 (every 10 cycle(s)) ---\n"), report);
        assertTrue(report.contains("  failure: candidate \"c\" threw X\n--- reference \"r\""), report);
        assertFalse(report.contains("--- state diff"), report);
    }

    @Test
    void sideStateReportsItsLastInstructionAndCopiesItsTrace() {
        List<String> lines = new java.util.ArrayList<>(List.of("a", "b"));
        Divergence.SideState s = new Divergence.SideState("x", 1, 2, 3, lines);
        lines.add("c");
        assertEquals(List.of("a", "b"), s.recentTrace(), "the trace is copied");
        assertEquals("b", s.lastInstruction().orElseThrow());
        assertTrue(side("x", 0, 0, 0).lastInstruction().isEmpty());
    }

    @Test
    void narrowingValidatesItsParts() {
        Divergence.SideState s = side("r", 1, 1, 1);
        // narrowed needs a method, both sides, a step, and exactly one of diff / failure
        assertThrows(IllegalArgumentException.class, () -> new Narrowing(Narrowing.Status.NARROWED, null, "g", 0, 1, 1,
                s, s, diff(), null, ""));
        assertThrows(IllegalArgumentException.class, () -> new Narrowing(Narrowing.Status.NARROWED,
                Narrowing.Method.RESTORE, "g", 0, 1, 1, null, s, diff(), null, ""));
        assertThrows(IllegalArgumentException.class, () -> new Narrowing(Narrowing.Status.NARROWED,
                Narrowing.Method.RESTORE, "g", 0, 1, 1, s, null, diff(), null, ""));
        assertThrows(IllegalArgumentException.class, () -> new Narrowing(Narrowing.Status.NARROWED,
                Narrowing.Method.RESTORE, "g", 0, 1, -1, s, s, diff(), null, ""));
        assertThrows(IllegalArgumentException.class, () -> new Narrowing(Narrowing.Status.NARROWED,
                Narrowing.Method.RESTORE, "g", 0, 1, 1, s, s, null, null, ""), "neither diff nor failure");
        assertThrows(IllegalArgumentException.class, () -> new Narrowing(Narrowing.Status.NARROWED,
                Narrowing.Method.RESTORE, "g", 0, 1, 1, s, s, diff(), "both", ""), "both diff and failure");
        assertThrows(NullPointerException.class, () -> new Narrowing(null, null, "g", 0, 1, 1, s, s, diff(), null, ""));
        assertThrows(NullPointerException.class, () -> new Narrowing(Narrowing.Status.NOT_NEEDED, null, "g", 0, 1, 1,
                null, null, null, null, null));
        Narrowing ok = new Narrowing(Narrowing.Status.NARROWED, Narrowing.Method.RESTORE, "g", 40, 50, 42, s, s, null,
                "x", "");
        assertEquals("41..50", ok.window());
        assertEquals("restore", Narrowing.Method.RESTORE.text());
        assertEquals("replay", Narrowing.Method.REPLAY.text());
        assertEquals(Narrowing.Status.NOT_NEEDED, new Divergence(1, 1, 0, s, s, diff(), null).narrowing().status());
    }

    @Test
    void granularityValidatesAndDescribesItself() {
        assertEquals("every 1 step(s)", Granularity.everyStep().toString());
        assertEquals(Granularity.everyStep(), Granularity.everyInstruction());
        assertEquals("every 5 step(s)", Granularity.everySteps(5).toString());
        assertEquals("every 7 cycle(s)", Granularity.everyCycles(7).toString());
        assertEquals(1, Granularity.everySteps(1).every());
        assertThrows(IllegalArgumentException.class, () -> Granularity.everySteps(0));
        assertThrows(IllegalArgumentException.class, () -> Granularity.everyCycles(-1));
        assertThrows(IllegalArgumentException.class, () -> new Granularity(null, 5));
    }
}
