package com.darchan.diag.compare;

import com.darchan.diag.run.Debugger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Counting, repeated runs, exceptions, trace context and replay configuration checks of {@link Lockstep}. */
class LockstepEdgeCasesTest {

    private final TinyMachine ref = new TinyMachine();
    private final TinyMachine cand = new TinyMachine();
    private final Debugger refDbg = new Debugger(ref, null);
    private final Debugger candDbg = new Debugger(cand, null);

    private Lockstep.Builder lockstep() {
        return Lockstep.builder("ref", refDbg, "cand", candDbg);
    }

    // ---- results of later calls ------------------------------------------------------------------------------------

    @Test
    void aSecondCallReportsOnlyItsOwnStepsAndComparisons() {
        cand.faultAtStep = 14;
        cand.faultMask = 1;
        Lockstep ls = lockstep().build();
        LockstepResult first = ls.run(10);
        assertFalse(first.diverged());
        assertEquals(10, first.steps());
        assertEquals(11, first.comparisons(), "the initial comparison and one per step");
        LockstepResult second = ls.run(100);
        assertTrue(second.diverged());
        assertEquals(5, second.steps(), "steps 11..15 of the run, not the running total");
        assertEquals(5, second.comparisons());
        assertEquals(15, second.divergence().step());
        assertEquals(15, ls.totalSteps());
    }

    @Test
    void afterADivergenceFurtherRunsReturnTheSameDivergence() {
        cand.faultAtStep = 3;
        cand.faultMask = 1;
        Lockstep ls = lockstep().build();
        LockstepResult first = ls.run(100);
        Divergence d = first.divergence();
        LockstepResult again = ls.run(100);
        assertSame(d, again.divergence());
        assertEquals(0, again.steps());
        assertEquals(0, again.comparisons());
        assertEquals("already diverged", again.ended());
        assertTrue(again.firstDivergence().isPresent());
        assertSame(d, ls.runUntil(() -> true, 5).divergence());
        assertEquals(4, ref.steps, "nothing ran");
    }

    @Test
    void aDifferenceInTheInitialStateIsReportedWithoutRunningAnything() {
        cand.pc = 9;
        LockstepResult r = lockstep().build().run(100);
        assertTrue(r.diverged());
        assertEquals(0, r.steps());
        assertEquals(1, r.comparisons());
        assertEquals(0, ref.steps);
        assertEquals(0, cand.steps);
    }

    @Test
    void theResultTextSaysWhyARunEndedOrWhatDiverged() {
        LockstepResult ok = lockstep().build().run(3);
        assertEquals("lockstep: no divergence in 3 step(s), 4 comparison(s); step limit 3 reached", ok.report());
        assertEquals(ok.report(), ok.toString());
        assertTrue(ok.firstDivergence().isEmpty());
        cand.faultAtStep = 0;
        cand.faultMask = 1;
        LockstepResult bad = Lockstep.builder("a", new Debugger(new TinyMachine(), null), "b",
                new Debugger(cand, null)).build().run(3);
        assertEquals(bad.divergence().report(), bad.report());
        assertEquals(bad.report(), bad.toString());
        assertSame(bad.divergence(), bad.firstDivergence().orElseThrow());
    }

    @Test
    void runUntilChecksTheStopConditionBeforeEachWindowAndTheLimitFirst() {
        Lockstep ls = lockstep().build();
        assertThrows(NullPointerException.class, () -> ls.runUntil(null, 5));
        LockstepResult r = ls.runUntil(() -> true, 100);
        assertEquals("stop condition met", r.ended());
        assertEquals(0, r.steps());
        assertEquals(1, r.comparisons(), "the initial comparison still happens");
        r = ls.runUntil(() -> true, 0);
        assertEquals("step limit 0 reached", r.ended(), "the step limit is checked before the stop condition");
    }

    // ---- exceptions ---------------------------------------------------------------------------------------------------

    @Test
    void theReferenceThrowingIsADivergenceAtTheStepThatThrew() {
        ref.throwAtStep = 7;
        LockstepResult r = lockstep().build().run(100);
        Divergence d = r.divergence();
        assertEquals(7, r.steps(), "seven steps completed before the eighth threw");
        assertEquals(8, d.step());
        assertEquals(8, d.comparison(), "eight comparisons came before it: the initial one and seven matches");
        assertEquals(7, d.lastMatchingStep());
        assertEquals(9, r.comparisons(), "the failing step counts as a comparison");
        assertTrue(d.failure().contains("reference \"ref\" threw java.lang.IllegalStateException: boom at step 7"),
                d.failure());
        assertNull(d.diff());
        assertTrue(d.snapshotDiff().isEmpty());
    }

    @Test
    void theCandidateThrowingCountsTheStepTheReferenceCompleted() {
        cand.throwAtStep = 7;
        LockstepResult r = lockstep().build().run(100);
        Divergence d = r.divergence();
        assertEquals(8, r.steps(), "the reference finished step 8 before the candidate threw in it");
        assertEquals(8, d.step());
        assertEquals(8, d.comparison());
        assertEquals(9, r.comparisons());
        assertTrue(d.failure().contains("candidate \"cand\" threw"), d.failure());
        assertEquals(8, d.reference().instructions());
        assertEquals(7, d.candidate().instructions());
    }

    @Test
    void anExceptionOnALaterCallIsCountedFromThatCall() {
        ref.throwAtStep = 12;
        Lockstep ls = lockstep().build();
        ls.run(10);
        LockstepResult r = ls.run(100);
        assertEquals(2, r.steps());
        assertEquals(3, r.comparisons(), "two matches and the failing step");
        cand.throwAtStep = -1;
    }

    @Test
    void aThrowingStopConditionOrSnapshotPropagates() {
        Lockstep ls = lockstep().referenceSnapshot(() -> {
            throw new IllegalStateException("snapshot failed");
        }).build();
        assertThrows(IllegalStateException.class, () -> ls.run(5));
    }

    // ---- trace context --------------------------------------------------------------------------------------------------

    @Test
    void contextLinesLimitTheTraceInTheDivergence() {
        cand.faultAtStep = 20;
        cand.faultMask = 1;
        Divergence d = lockstep().contextLines(3).build().run(100).divergence();
        assertEquals(3, d.reference().recentTrace().size());
        assertEquals(3, d.candidate().recentTrace().size());
        assertTrue(d.reference().lastInstruction().orElseThrow().contains(" 14: 14  ADD PC"), d::report);
        assertThrows(IllegalArgumentException.class, () -> lockstep().contextLines(-1));
        // zero lines is allowed and leaves no trace
        TinyMachine r2 = new TinyMachine();
        TinyMachine c2 = new TinyMachine();
        c2.faultAtStep = 2;
        c2.faultMask = 1;
        Divergence none = Lockstep.builder("r", new Debugger(r2, null), "c", new Debugger(c2, null)).contextLines(0)
                .build().run(10).divergence();
        assertTrue(none.reference().recentTrace().isEmpty());
        assertEquals(Lockstep.DEFAULT_CONTEXT_LINES, 16);
    }

    @Test
    void aSideWithoutARingBufferHasNoTraceButStillDiverges() {
        refDbg.setRingBufferCapacity(0);
        cand.faultAtStep = 4;
        cand.faultMask = 1;
        Divergence d = lockstep().build().run(100).divergence();
        assertTrue(d.reference().recentTrace().isEmpty());
        assertFalse(d.candidate().recentTrace().isEmpty());
        assertTrue(d.report().contains("--- reference \"ref\": no trace (ring buffer disabled) ---"), d::report);
    }

    @Test
    void narrowingWorksWithoutRingBuffersToo() {
        refDbg.setRingBufferCapacity(0);
        candDbg.setRingBufferCapacity(0);
        cand.faultAtStep = 14;
        cand.faultMask = 1;
        Divergence d = lockstep().granularity(Granularity.everySteps(10)).build().run(100).divergence();
        assertEquals(Narrowing.Status.NARROWED, d.narrowing().status(), d::report);
        assertEquals(15, d.narrowing().step());
        assertTrue(d.narrowing().reference().recentTrace().isEmpty());
    }

    // ---- narrowing boundaries -----------------------------------------------------------------------------------------

    @Test
    void aFaultInTheVeryFirstWindowIsStillNarrowed() {
        cand.faultAtStep = 3; // the first coarse comparison (after step 10) is the first to differ
        cand.faultMask = 1;
        Divergence d = lockstep().granularity(Granularity.everySteps(10)).build().run(100).divergence();
        assertEquals(10, d.step());
        assertEquals(0, d.lastMatchingStep(), "the initial state matched");
        assertEquals(Narrowing.Status.NARROWED, d.narrowing().status(), d::report);
        assertEquals(4, d.narrowing().step());
        assertEquals(1, d.narrowing().windowStart() + 1);
    }

    @Test
    void aNarrowedRunLeavesTheRingBuffersWithEachInstructionOnce() {
        cand.faultAtStep = 14;
        cand.faultMask = 1;
        Divergence d = lockstep().granularity(Granularity.everySteps(10)).build().run(100).divergence();
        assertEquals(Narrowing.Status.NARROWED, d.narrowing().status(), d::report);
        List<String> trace = d.narrowing().candidate().recentTrace();
        assertEquals(15, trace.size());
        for (int i = 0; i < trace.size(); i++) {
            assertTrue(trace.get(i).contains(String.format("%012d ", i)) || trace.get(i).contains(" " + String.format("%012d", i) + " "),
                    "line " + i + " is instruction " + i + ": " + trace.get(i));
        }
        // the ring buffer of the debugger itself ends at the narrowed step too
        List<String> ring = candDbg.ringBuffer().lines();
        assertTrue(ring.get(ring.size() - 1).contains("000000000014 "), ring.get(ring.size() - 1));
        assertEquals(15, cand.steps);
    }

    @Test
    void severalWindowsMatchBeforeTheDifferenceAndOnlyTheLastIsReRun() {
        cand.faultAtStep = 34;
        cand.faultMask = 1;
        Divergence d = lockstep().granularity(Granularity.everySteps(10)).build().run(100).divergence();
        assertEquals(40, d.step());
        assertEquals(30, d.lastMatchingStep());
        assertEquals(35, d.narrowing().step());
        assertEquals("31..40", d.narrowing().window());
        assertEquals(35, ref.steps);
        assertEquals(35, cand.steps);
    }

    // ---- replay configuration -------------------------------------------------------------------------------------------

    private static Supplier<Lockstep.Builder> replaySetup(java.util.function.UnaryOperator<Lockstep.Builder> configure,
                                                         String refName, String candName) {
        return () -> {
            TinyMachine r = new TinyMachine();
            TinyMachine c = new TinyMachine();
            r.snapshotComplete = false;
            c.snapshotComplete = false;
            c.faultAtStep = 14;
            c.faultMask = 1;
            return configure.apply(Lockstep.builder(refName, new Debugger(r, null), candName, new Debugger(c, null))
                    .granularity(Granularity.everySteps(10)));
        };
    }

    private Narrowing narrowWithReplay(Supplier<Lockstep.Builder> fresh, java.util.function.UnaryOperator<Lockstep.Builder> mine) {
        TinyMachine r = new TinyMachine();
        TinyMachine c = new TinyMachine();
        r.snapshotComplete = false;
        c.snapshotComplete = false;
        c.faultAtStep = 14;
        c.faultMask = 1;
        return mine.apply(Lockstep.builder("ref", new Debugger(r, null), "cand", new Debugger(c, null))
                .granularity(Granularity.everySteps(10))).replayWith(fresh).build().run(100).divergence().narrowing();
    }

    @Test
    void aReplayWithTheSameConfigurationWorks() {
        Narrowing n = narrowWithReplay(replaySetup(b -> b, "ref", "cand"), b -> b);
        assertEquals(Narrowing.Status.NARROWED, n.status(), n::toString);
        assertEquals(Narrowing.Method.REPLAY, n.method());
        assertEquals(15, n.step());
    }

    @Test
    void aReplayWithDifferentCompareOnlyFieldsIsRejected() {
        Narrowing n = narrowWithReplay(replaySetup(b -> b.compareOnly("PC"), "ref", "cand"), b -> b);
        assertEquals(Narrowing.Status.NOT_NARROWED, n.status());
        assertTrue(n.reason().contains("configured differently (compareOnly fields [PC] instead of [])"), n.reason());
    }

    @Test
    void aReplayWithDifferentMemoryRegionsIsRejected() {
        Narrowing n = narrowWithReplay(replaySetup(b -> b.compareMemory("ram", a -> 0, a -> 0, 0x10, 4), "ref", "cand"),
                b -> b);
        assertEquals(Narrowing.Status.NOT_NARROWED, n.status());
        assertTrue(n.reason().contains("memory regions [ram[10+4]] instead of []"), n.reason());
    }

    @Test
    void aReplayWithDifferentSideNamesIsRejected() {
        Narrowing n = narrowWithReplay(replaySetup(b -> b, "golden", "cand"), b -> b);
        assertEquals(Narrowing.Status.NOT_NARROWED, n.status());
        assertTrue(n.reason().contains("side names golden/cand instead of ref/cand"), n.reason());
        n = narrowWithReplay(replaySetup(b -> b, "ref", "other"), b -> b);
        assertTrue(n.reason().contains("side names ref/other instead of ref/cand"), n.reason());
    }

    @Test
    void aReplayFactoryMustBuildFreshTargets() {
        ref.snapshotComplete = false;
        cand.snapshotComplete = false;
        cand.faultAtStep = 14;
        cand.faultMask = 1;
        Narrowing n = lockstep().granularity(Granularity.everySteps(10))
                .replayWith(() -> Lockstep.builder("ref", refDbg, "cand", candDbg)).build().run(100).divergence()
                .narrowing();
        assertEquals(Narrowing.Status.NOT_NARROWED, n.status());
        assertTrue(n.reason().contains("must build fresh targets"), n.reason());
    }

    @Test
    void reusingJustOneOriginalTargetInAReplayIsRejectedToo() {
        for (int which = 0; which < 4; which++) {
            TinyMachine r = new TinyMachine();
            TinyMachine c = new TinyMachine();
            r.snapshotComplete = false;
            c.snapshotComplete = false;
            c.faultAtStep = 14;
            c.faultMask = 1;
            Debugger rd = new Debugger(r, null);
            Debugger cd = new Debugger(c, null);
            int w = which;
            Narrowing n = Lockstep.builder("ref", rd, "cand", cd).granularity(Granularity.everySteps(10))
                    .replayWith(() -> {
                        TinyMachine other = new TinyMachine();
                        other.faultAtStep = 14;
                        other.faultMask = 1;
                        Debugger fresh = new Debugger(other, null);
                        Debugger freshRef = new Debugger(new TinyMachine(), null);
                        return switch (w) {
                            case 0 -> Lockstep.builder("ref", rd, "cand", fresh);          // original reference
                            case 1 -> Lockstep.builder("ref", freshRef, "cand", cd);       // original candidate
                            case 2 -> Lockstep.builder("ref", cd, "cand", freshRef);       // candidate as reference
                            default -> Lockstep.builder("ref", freshRef, "cand", rd);      // reference as candidate
                        };
                    }).build().run(100).divergence().narrowing();
            assertEquals(Narrowing.Status.NOT_NARROWED, n.status(), "case " + which);
            assertTrue(n.reason().contains("must build fresh targets"), "case " + which + ": " + n.reason());
        }
    }

    @Test
    void aReplayFactoryThatThrowsOrReturnsNullIsReported() {
        ref.snapshotComplete = false;
        cand.snapshotComplete = false;
        cand.faultAtStep = 14;
        cand.faultMask = 1;
        Narrowing thrown = lockstep().granularity(Granularity.everySteps(10)).replayWith(() -> {
            throw new IllegalStateException("no factory today");
        }).build().run(100).divergence().narrowing();
        assertTrue(thrown.reason().contains("replay: the replay factory threw java.lang.IllegalStateException: no factory"
                + " today"), thrown.reason());

        TinyMachine r = new TinyMachine();
        TinyMachine c = new TinyMachine();
        r.snapshotComplete = false;
        c.snapshotComplete = false;
        c.faultAtStep = 14;
        c.faultMask = 1;
        Narrowing nul = Lockstep.builder("ref", new Debugger(r, null), "cand", new Debugger(c, null))
                .granularity(Granularity.everySteps(10)).replayWith(() -> null).build().run(100).divergence()
                .narrowing();
        assertTrue(nul.reason().contains("the replay factory returned null"), nul.reason());
    }
}
