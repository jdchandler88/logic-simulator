package com.darchan.diag.compare;

import com.darchan.diag.Snapshot;
import com.darchan.diag.run.Debugger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LockstepTest {

    private final TinyMachine ref = new TinyMachine();
    private final TinyMachine cand = new TinyMachine();
    private final Debugger refDbg = new Debugger(ref, null);
    private final Debugger candDbg = new Debugger(cand, null);

    private Lockstep.Builder lockstep() {
        return Lockstep.builder("ref", refDbg, "cand", candDbg);
    }

    @Test
    void identicalModelsNeverDiverge() {
        LockstepResult r = lockstep().build().run(500);
        assertFalse(r.diverged(), r::report);
        assertEquals(500, r.steps());
        assertEquals(501, r.comparisons()); // initial state + one per step
        assertTrue(r.report().contains("no divergence in 500 step(s)"), r::report);
        assertEquals(500, ref.steps);
        assertEquals(500, cand.steps);
    }

    @Test
    void stepGranularityPinsTheFaultToTheExactInstruction() {
        cand.faultAtStep = 41;          // the 42nd instruction computes a wrong ACC
        cand.faultMask = 0x10;
        LockstepResult r = lockstep().build().run(1000);
        assertTrue(r.diverged());
        Divergence d = r.divergence();
        assertEquals(42, d.step());
        assertEquals(41, d.lastMatchingStep());
        assertEquals(42, d.comparison());
        assertNull(d.failure());
        assertEquals(1, d.diff().fieldDifferences().size());
        assertEquals("ACC", d.diff().fieldDifferences().get(0).name());
        // the last traced instruction on each side is the one that diverged: PC 41 (29H)
        assertTrue(d.reference().lastInstruction().orElseThrow().contains(" 29: 29  ADD PC"), d::report);
        assertTrue(d.candidate().lastInstruction().orElseThrow().contains(" 29: 29  ADD PC"), d::report);
        String report = d.report();
        assertTrue(report.contains("LOCKSTEP DIVERGENCE after step 42 (comparison #42, last match after step 41)"), report);
        assertTrue(report.contains("ACC         expected=5D  actual=4D"), report);
        assertTrue(report.contains("> 000000000163 000000000041 29: 29  ADD PC ACC=34"), report);
        assertEquals(16, d.reference().recentTrace().size());
        // both models stopped right after the faulty instruction
        assertEquals(42, ref.steps);
        assertEquals(42, cand.steps);
    }

    @Test
    void coarserStepGranularityFindsTheWindow() {
        cand.faultAtStep = 41;
        cand.faultMask = 1;
        Divergence d = lockstep().granularity(Granularity.everySteps(10)).build().run(1000).divergence();
        assertEquals(50, d.step());
        assertEquals(40, d.lastMatchingStep());
        assertEquals(5, d.comparison());
    }

    @Test
    void cycleGranularityComparesAtInstructionBoundaries() {
        cand.faultAtStep = 41;
        cand.faultMask = 1;
        // 3 + 5 cycles per pair of steps: the first boundary is crossed after step 26 (104 cycles), the second
        // exactly at step 50 (200 cycles); the fault in step 41 is seen there
        Divergence d = lockstep().granularity(Granularity.everyCycles(100)).build().run(10_000).divergence();
        assertEquals(50, d.step());
        assertEquals(26, d.lastMatchingStep());
        assertEquals(2, d.comparison());
        assertEquals(200, d.reference().cycles());
    }

    @Test
    void ignoredFieldsAreNotCompared() {
        cand.extraCycles = 1;
        Divergence d = lockstep().build().run(100).divergence();
        assertNotNull(d);
        assertEquals(1, d.step());
        assertEquals("CYCLES", d.diff().fieldDifferences().get(0).name());

        TinyMachine a = new TinyMachine();
        TinyMachine b = new TinyMachine();
        b.extraCycles = 1;
        LockstepResult r = Lockstep.builder("a", new Debugger(a, null), "b", new Debugger(b, null))
                .ignore("CYCLES").build().run(100);
        assertFalse(r.diverged(), r::report);
    }

    @Test
    void anExceptionOnEitherSideIsADivergence() {
        cand.throwAtStep = 7;
        Divergence d = lockstep().build().run(100).divergence();
        assertNotNull(d);
        assertNull(d.diff());
        assertTrue(d.failure().contains("candidate \"cand\" threw java.lang.IllegalStateException: boom at step 7"), d::report);
        assertTrue(d.report().contains("--- failure ---"), d::report);
    }

    @Test
    void initialStatesAreComparedFirst() {
        cand.acc = 9;
        Divergence d = lockstep().build().run(100).divergence();
        assertEquals(0, d.comparison());
        assertEquals(0, d.step());
        assertEquals(0, ref.steps);
        assertTrue(d.report().contains("in the initial state"), d::report);
    }

    @Test
    void compareOnlyBridgesDifferentMachines() {
        TinyMachine other = new TinyMachine("other");
        other.extraCycles = 2;
        Lockstep.Builder b = Lockstep.builder("ref", refDbg, "other", new Debugger(other, null));
        // different machine names are a difference on their own...
        assertTrue(Lockstep.builder("ref", new Debugger(new TinyMachine(), null), "other",
                new Debugger(new TinyMachine("other"), null)).ignore("CYCLES").build().run(5).diverged());
        // ...unless the comparison is reduced to the shared fields
        LockstepResult r = b.compareOnly("PC", "ACC").build().run(300);
        assertFalse(r.diverged(), r::report);
    }

    @Test
    void runUntilEndsCleanlyAndCanContinue() {
        Lockstep ls = lockstep().build();
        LockstepResult r = ls.runUntil(() -> ref.pc == 20, 1000);
        assertFalse(r.diverged());
        assertEquals(20, r.steps());
        assertEquals("stop condition met", r.ended());
        r = ls.run(10);
        assertEquals(10, r.steps());
        assertEquals(10, r.comparisons());
        assertEquals(30, ls.totalSteps());
    }

    @Test
    void memoryRegionsAreCompared() {
        int[] refMem = new int[16];
        int[] candMem = new int[16];
        candMem[5] = 0xEE;
        Divergence d = lockstep().compareMemory("ram", a -> refMem[a], a -> candMem[a], 0, 16).build().run(10)
                .divergence();
        assertEquals(1, d.diff().byteDifferenceCount());
        assertTrue(d.report().contains("ram[0005]   expected=00  actual=EE"), d::report);
    }

    @Test
    void sameTargetTwiceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Lockstep.builder("a", refDbg, "b", refDbg));
    }
    @Test
    void cycleGranularityNeverExceedsMaxSteps() {
        Lockstep ls = lockstep().granularity(Granularity.everyCycles(1000)).build();
        LockstepResult r = ls.run(1);
        assertFalse(r.diverged(), r::report);
        assertEquals(1, r.steps());
        assertEquals(1, ref.steps);
        assertEquals("step limit 1 reached", r.ended());
        // a later call finishes the window (1000 cycles = 250 steps) and compares at its boundary
        r = ls.run(300);
        assertFalse(r.diverged(), r::report);
        assertEquals(300, r.steps());
        assertEquals(301, ref.steps);
        assertEquals(1, r.comparisons(), "one boundary (cycle 1000) within the first 301 steps");
        assertEquals(250, cand.steps, "the candidate waits at the boundary the reference has not finished");
    }

    @Test
    void cycleGranularityWithAHugeIntervalRespectsMaxSteps() {
        Lockstep ls = lockstep().granularity(Granularity.everyCycles(Long.MAX_VALUE)).build();
        LockstepResult r = assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> ls.run(20));
        assertFalse(r.diverged(), r::report);
        assertEquals(20, ref.steps);
    }

    @Test
    void compareOnlyKeepsZAndXMasks() {
        // a gate-level probe on a floating bus reads 00 with A.Z = FF (see ProbeSet): not equal to a driven 00
        Lockstep.Builder b = lockstep()
                .referenceSnapshot(() -> Snapshot.builder("cpu").hex("A", 8, 0).hex("B", 8, 1)
                        .build())
                .candidateSnapshot(() -> Snapshot.builder("gates").hex("A", 8, 0)
                        .hex("A.Z", 8, 0xFF).hex("B", 8, 1).build());
        LockstepResult r = b.compareOnly("A", "B").build().run(5);
        assertTrue(r.diverged(), r::report);
        assertEquals("A.Z", r.divergence().diff().fieldDifferences().get(0).name());
        assertTrue(r.report().contains("expected=<missing>  actual=FF"), r::report);

        LockstepResult x = Lockstep.builder("ref", new Debugger(new TinyMachine(), null), "cand",
                        new Debugger(new TinyMachine(), null))
                .referenceSnapshot(() -> Snapshot.builder("a").hex("A", 8, 0).hex("A.X", 8, 1)
                        .build())
                .candidateSnapshot(() -> Snapshot.builder("b").hex("A", 8, 0).hex("A.X", 8, 1)
                        .build())
                .compareOnly("A").build().run(5);
        assertFalse(x.diverged(), "identical masks on both sides match: " + x.report());

        LockstepResult named = lockstep()
                .candidateSnapshot(() -> Snapshot.builder("tiny").hex("PC", 8, cand.pc).hex("ACC", 8, cand.acc)
                        .hex("ACC.Z", 8, 0x80).build())
                .compareOnly("PC", "ACC", "ACC.Z").build().run(5);
        assertEquals("ACC.Z", named.divergence().diff().fieldDifferences().get(0).name(),
                "naming a mask explicitly is allowed too");
    }

    @Test
    void compareOnlyRejectsAFieldNeitherSideHas() {
        Lockstep ls = lockstep().compareOnly("PC", "ACCUMULATOR").build();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ls.run(5));
        assertTrue(e.getMessage().contains("ACCUMULATOR"), e.getMessage());
        assertEquals(0, ref.steps, "rejected at the first (initial) comparison");
    }

    @Test
    void compareOnlyFieldOnOneSideOnlyIsADivergence() {
        Lockstep.Builder b = lockstep()
                .candidateSnapshot(() -> Snapshot.builder("tiny").hex("PC", 8, cand.pc).build());
        LockstepResult r = b.compareOnly("PC", "ACC").build().run(5);
        assertTrue(r.diverged(), r::report);
        assertEquals("ACC", r.divergence().diff().fieldDifferences().get(0).name());
    }
}
