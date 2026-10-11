package com.darchan.diag.compare;

import com.darchan.diag.run.Debugger;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The cross-check that keeps a narrowed step honest: after a re-run finds a difference, both sides continue to where
 * the coarse comparison saw the divergence and must be in exactly the states it compared (or throw the same
 * exception). Each test makes one side behave differently the second time through.
 */
class LockstepCrossCheckTest {

    private final TinyMachine ref = new TinyMachine();
    private final TinyMachine cand = new TinyMachine();
    private final Debugger refDbg = new Debugger(ref, null);
    private final Debugger candDbg = new Debugger(cand, null);

    private Lockstep.Builder lockstep() {
        return Lockstep.builder("ref", refDbg, "cand", candDbg).granularity(Granularity.everySteps(10));
    }

    private Narrowing narrowing() {
        return lockstep().build().run(1000).divergence().narrowing();
    }

    // ---- a window whose difference is the last step ------------------------------------------------------------

    @Test
    void aFaultInTheLastStepOfTheWindowIsFound() {
        cand.faultAtStep = 19; // visible after step 20, the end of the window 11..20
        cand.faultMask = 1;
        Divergence d = lockstep().build().run(1000).divergence();
        assertEquals(20, d.step());
        assertEquals(Narrowing.Status.NARROWED, d.narrowing().status(), d::report);
        assertEquals(20, d.narrowing().step());
        // the rings hold each instruction once up to the step found, on both sides
        for (Debugger dbg : new Debugger[]{refDbg, candDbg}) {
            var lines = dbg.ringBuffer().lines();
            assertEquals(20, lines.size(), "the ring buffer after the narrowing");
            assertTrue(lines.get(19).contains("000000000019 "), lines.get(19));
        }
        assertEquals(Lockstep.DEFAULT_CONTEXT_LINES, d.narrowing().reference().recentTrace().size());
    }

    @Test
    void theReferenceRingIsRolledBackToo() {
        cand.faultAtStep = 14;
        cand.faultMask = 1;
        Divergence d = lockstep().build().run(1000).divergence();
        assertEquals(15, d.narrowing().step());
        var refTrace = d.narrowing().reference().recentTrace();
        assertEquals(15, refTrace.size(), "each instruction once on the reference side: " + refTrace);
        for (int i = 0; i < refTrace.size(); i++) {
            assertTrue(refTrace.get(i).startsWith(String.format("%012d %012d ", cyclesBefore(i), i)),
                    "line " + i + ": " + refTrace.get(i));
        }
        var ring = refDbg.ringBuffer().lines();
        assertEquals(15, ring.size());
        assertEquals(15, ref.steps);
    }

    private static long cyclesBefore(int steps) {
        TinyMachine m = new TinyMachine();
        for (int i = 0; i < steps; i++) {
            m.step();
        }
        return m.cycles;
    }

    // ---- exceptions ---------------------------------------------------------------------------------------------------

    @Test
    void aReRunThatThrowsWhereTheCoarseRunComparedStatesIsNotReported() {
        cand.faultAtStep = 14;
        cand.faultMask = 1;
        cand.reexecuteThrowAtStep = 14; // the faulty step throws when executed again
        Narrowing n = narrowing();
        assertEquals(Narrowing.Status.NOT_NARROWED, n.status());
        assertTrue(n.reason().contains("the re-run threw (candidate \"cand\" threw java.lang.IllegalStateException: re-run"
                + " boom at step 14) where the coarse run compared states"), n.reason());
        assertTrue(n.reason().contains("did not reproduce the coarse comparison after step 20"), n.reason());
    }

    @Test
    void twoDifferentExceptionsAreNotTheSameFailure() {
        cand.throwAtStep = 14;
        cand.varyingMessage = true; // "boom #1" in the coarse run, "boom #2" when re-run
        Narrowing n = narrowing();
        assertEquals(Narrowing.Status.NOT_NARROWED, n.status());
        assertTrue(n.reason().contains("the re-run threw (candidate \"cand\" threw java.lang.IllegalStateException: boom #2)"
                + " but the coarse run saw: candidate \"cand\" threw java.lang.IllegalStateException: boom #1"),
                n.reason());
    }

    @Test
    void theSameExceptionTwiceIsPinnedToItsStep() {
        cand.throwAtStep = 14;
        Narrowing n = narrowing();
        assertEquals(Narrowing.Status.NARROWED, n.status());
        assertEquals(15, n.step());
        assertTrue(n.failure().contains("boom at step 14"));
        assertEquals("", n.reason());
    }

    @Test
    void aCoarseExceptionThatDoesNotRecurIsNotTrusted() {
        cand.faultAtStep = 14; // reproducible difference found in the re-run
        cand.faultMask = 1;
        cand.oneShotThrowAtStep = 17; // but the coarse run's exception happens only once
        Narrowing n = narrowing();
        assertEquals(Narrowing.Status.NOT_NARROWED, n.status());
        assertTrue(n.reason().contains("the coarse run's exception did not recur; the coarse run saw: candidate \"cand\""
                + " threw java.lang.IllegalStateException: boom at step 17"), n.reason());
    }

    @Test
    void whenTheReferenceThrewInTheCoarseRunTheCandidateHadNotMovedSoThereIsNothingToCrossCheck() {
        cand.faultAtStep = 14;
        cand.faultMask = 1;
        ref.throwAtStep = 17;
        Divergence d = lockstep().build().run(1000).divergence();
        assertTrue(d.failure().contains("reference \"ref\" threw"), d::report);
        Narrowing n = d.narrowing();
        assertEquals(Narrowing.Status.NARROWED, n.status(), d::report);
        assertEquals(15, n.step());
        assertTrue(n.reason().contains("not cross-checked against the coarse comparison"), n.reason());
        assertTrue(d.report().contains("  note: not cross-checked"), d::report);
    }

    @Test
    void aContinuationThatThrowsAfterTheStepFoundIsNotTrusted() {
        cand.faultAtStep = 14;
        cand.faultMask = 1;
        cand.reexecuteThrowAtStep = 17; // only when re-run, and only after the step that was found
        Narrowing n = narrowing();
        assertEquals(Narrowing.Status.NOT_NARROWED, n.status());
        assertTrue(n.reason().contains("(candidate \"cand\" threw java.lang.IllegalStateException: re-run boom at step 17)"),
                n.reason());
    }

    // ---- states that differ from the coarse comparison ---------------------------------------------------------------------

    @Test
    void aCandidateThatEndsInAnotherStateThanTheCoarseRunIsNotTrusted() {
        cand.faultAtStep = 14;
        cand.faultMask = 1;
        cand.oneShotFaultAtStep = 17; // part of the coarse state only
        cand.oneShotFaultMask = 0x40;
        Narrowing n = narrowing();
        assertEquals(Narrowing.Status.NOT_NARROWED, n.status());
        assertTrue(n.reason().contains("candidate \"cand\": ACC was"), n.reason());
        assertTrue(n.reason().contains(", is now "), n.reason());
    }

    @Test
    void aReferenceThatEndsInAnotherStateThanTheCoarseRunIsNotTrusted() {
        cand.faultAtStep = 14;
        cand.faultMask = 1;
        ref.oneShotFaultAtStep = 17;
        ref.oneShotFaultMask = 0x40;
        Narrowing n = narrowing();
        assertEquals(Narrowing.Status.NOT_NARROWED, n.status());
        assertTrue(n.reason().contains("reference \"ref\": ACC was"), n.reason());
    }

    @Test
    void aMemoryByteThatDiffersFromTheCoarseRunIsNamedByAddress() {
        cand.faultAtStep = 14;
        cand.faultMask = 1;
        cand.oneShotFaultAtStep = 17; // sets the marker that the candidate's memory view shows
        cand.oneShotFaultMask = 0;
        Narrowing n = lockstep().compareMemory("ram", a -> 0, a -> a == 0 && cand.oneShotMarker != 0 ? 0xEE : 0, 0, 4)
                .build().run(1000).divergence().narrowing();
        assertEquals(Narrowing.Status.NOT_NARROWED, n.status());
        assertTrue(n.reason().contains("candidate \"cand\": ram[0000] was EE, is now 00"), n.reason());
    }

    @Test
    void sidesWithDifferentStepCountsPerWindowCannotAlwaysBeCrossChecked() {
        // the candidate is slower per step, so in a window of 100 cycles it executes fewer steps than the reference
        TinyMachine r = new TinyMachine();
        TinyMachine c = new TinyMachine();
        c.extraCycles = 2;
        r.faultAtStep = 19; // found at re-run step 20, past the 17 steps the candidate took
        r.faultMask = 1;
        Narrowing n = Lockstep.builder("ref", new Debugger(r, null), "cand", new Debugger(c, null))
                .granularity(Granularity.everyCycles(100)).ignore("CYCLES").build().run(1000).divergence().narrowing();
        assertEquals(Narrowing.Status.NARROWED, n.status(), n::toString);
        assertEquals(20, n.step());
        assertTrue(n.reason().contains("not cross-checked against the coarse comparison"), n.reason());
    }

    // ---- replay -----------------------------------------------------------------------------------------------------------------

    @Test
    void aReplayWhoseWindowStartDriftsIsReported() {
        // a fresh reference that is slower per step has a different cycle boundary, so its last comparison before the
        // window is not where the original's was
        ref.snapshotComplete = false;
        cand.snapshotComplete = false;
        cand.faultAtStep = 40;
        cand.faultMask = 1;
        Supplier<Lockstep.Builder> fresh = () -> {
            TinyMachine r = new TinyMachine();
            TinyMachine c = new TinyMachine();
            r.snapshotComplete = false;
            c.snapshotComplete = false;
            r.extraCycles = 1;
            c.faultAtStep = 40;
            c.faultMask = 1;
            return Lockstep.builder("ref", new Debugger(r, null), "cand", new Debugger(c, null))
                    .granularity(Granularity.everyCycles(100)).ignore("CYCLES");
        };
        Narrowing n = lockstep().granularity(Granularity.everyCycles(100)).ignore("CYCLES").replayWith(fresh).build()
                .run(1000).divergence().narrowing();
        assertEquals(Narrowing.Status.NOT_NARROWED, n.status());
        assertTrue(n.reason().contains("replay: ") && (n.reason().contains("the replay's last comparison before the window was")
                || n.reason().contains("the replay diverged after step")), n.reason());
    }

    // ---- compareOnly keeps memory ---------------------------------------------------------------------------------------------

    @Test
    void compareOnlyStillComparesMemoryRegions() {
        int[] mem = new int[8];
        int[] other = new int[8];
        other[3] = 0x77;
        Divergence d = Lockstep.builder("ref", refDbg, "cand", candDbg).compareOnly("PC", "ACC")
                .compareMemory("ram", a -> mem[a], a -> other[a], 0, 8).build().run(5).divergence();
        assertNotNull(d, "the byte difference is a divergence even though only PC and ACC are compared");
        assertEquals(1, d.diff().byteDifferenceCount());
        assertEquals(3, d.diff().byteDifferences().get(0).address());
        assertEquals(0, d.diff().fieldDifferences().size());
    }

    // ---- later calls ---------------------------------------------------------------------------------------------------------------

    @Test
    void aStopConditionOnALaterCallCountsFromThatCall() {
        Lockstep ls = Lockstep.builder("ref", refDbg, "cand", candDbg).build();
        ls.run(5);
        LockstepResult r = ls.runUntil(() -> ref.steps >= 12, 100);
        assertEquals("stop condition met", r.ended());
        assertEquals(7, r.steps());
        assertEquals(7, r.comparisons());
    }

    @Test
    void aCandidateExceptionOnALaterCallCountsFromThatCall() {
        cand.throwAtStep = 12;
        Lockstep ls = Lockstep.builder("ref", refDbg, "cand", candDbg).build();
        ls.run(10);
        LockstepResult r = ls.run(100);
        assertEquals(3, r.steps(), "the reference completed steps 11, 12 and 13 before the candidate threw in 13");
        assertEquals(3, r.comparisons());
        assertEquals(13, r.divergence().step());
    }
}
