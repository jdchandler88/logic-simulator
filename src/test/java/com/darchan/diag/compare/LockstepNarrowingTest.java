package com.darchan.diag.compare;

import com.darchan.diag.run.Debugger;
import com.darchan.diag.trace.InMemoryTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Narrowing a coarse lockstep divergence (every N steps / every N cycles) down to the first diverging step, by
 * restoring the last matching state and re-running the window, or by replaying fresh models from the start.
 */
class LockstepNarrowingTest {

    private final TinyMachine ref = new TinyMachine();
    private final TinyMachine cand = new TinyMachine();
    private final Debugger refDbg = new Debugger(ref, null);
    private final Debugger candDbg = new Debugger(cand, null);

    private Lockstep.Builder lockstep() {
        return Lockstep.builder("ref", refDbg, "cand", candDbg);
    }

    /** a factory of fresh, identically configured lockstep set-ups; {@code faults} configures the candidate */
    private static Supplier<Lockstep.Builder> fresh(Consumer<TinyMachine> faults, Granularity granularity) {
        return () -> {
            TinyMachine r = new TinyMachine();
            TinyMachine c = new TinyMachine();
            r.snapshotComplete = false;
            c.snapshotComplete = false;
            faults.accept(c);
            return Lockstep.builder("ref", new Debugger(r, null), "cand", new Debugger(c, null))
                    .granularity(granularity);
        };
    }

    @Test
    void everyStepNeedsNoNarrowing() {
        cand.faultAtStep = 41;
        cand.faultMask = 0x10;
        Divergence d = lockstep().build().run(1000).divergence();
        assertEquals(Narrowing.Status.NOT_NEEDED, d.narrowing().status());
        assertEquals(42, d.firstStep().orElseThrow());
        assertFalse(d.report().contains("narrowed"), d::report);
    }

    @Test
    void aStepWindowIsNarrowedByRestoringTheLastMatch() {
        cand.faultAtStep = 41;
        cand.faultMask = 0x10;
        Divergence d = lockstep().granularity(Granularity.everySteps(10)).build().run(1000).divergence();
        // the coarse comparison is kept as it was seen
        assertEquals(50, d.step());
        assertEquals(40, d.lastMatchingStep());
        Narrowing n = d.narrowing();
        assertEquals(Narrowing.Status.NARROWED, n.status(), d::report);
        assertEquals(Narrowing.Method.RESTORE, n.method());
        assertEquals(42, n.step());
        assertEquals(42, d.firstStep().orElseThrow());
        assertEquals(40, n.windowStart());
        assertEquals(50, n.windowEnd());
        assertEquals(1, n.diff().fieldDifferences().size());
        assertEquals("ACC", n.diff().fieldDifferences().get(0).name());
        assertEquals(42, n.reference().instructions());
        assertEquals(42, n.candidate().instructions());
        String report = d.report();
        assertTrue(report.startsWith("LOCKSTEP DIVERGENCE after step 42 (narrowed by restore from steps 41..50:"
                + " compared every 10 step(s), seen at comparison #5 after step 50, last match after step 40)"), report);
        assertTrue(report.contains("--- state diff after step 42 (expected = reference, actual = candidate) ---"),
                report);
        assertTrue(report.contains("ACC         expected=5D  actual=4D"), report);
        assertTrue(report.contains("--- coarse comparison #5 after step 50 (every 10 step(s)) ---"), report);
        // the targets are left at the narrowed point, so they can be inspected or debugged from there
        assertEquals(42, ref.steps);
        assertEquals(42, cand.steps);
        // the trace context ends with the diverging instruction and holds every instruction once
        List<String> trace = n.candidate().recentTrace();
        assertTrue(trace.get(trace.size() - 1).contains(" 29: 29  ADD PC"), report);
        for (int i = 1; i < trace.size(); i++) {
            assertTrue(trace.get(i - 1).compareTo(trace.get(i)) < 0, "trace in order without repeats: " + trace);
        }
        assertTrue(report.contains("> 000000000163 000000000041 29: 29  ADD PC ACC=34"), report);
    }

    @Test
    void aCycleWindowIsNarrowedByRestoringTheLastMatch() {
        cand.faultAtStep = 41;
        cand.faultMask = 1;
        Divergence d = lockstep().granularity(Granularity.everyCycles(100)).build().run(10_000).divergence();
        assertEquals(50, d.step());
        assertEquals(26, d.lastMatchingStep());
        Narrowing n = d.narrowing();
        assertEquals(Narrowing.Status.NARROWED, n.status(), d::report);
        assertEquals(42, n.step());
        assertEquals(163 + 5, n.reference().cycles());
        assertTrue(d.report().contains("narrowed by restore from steps 27..50: compared every 100 cycle(s)"),
                d::report);
    }

    @Test
    void reRunTracesOnlyReachTheRingBuffer() {
        InMemoryTrace listener = new InMemoryTrace();
        candDbg.addTraceListener(listener);
        cand.faultAtStep = 41;
        cand.faultMask = 1;
        lockstep().granularity(Granularity.everySteps(10)).build().run(1000);
        assertEquals(50, listener.lines().size(), "the re-run window is not traced to other listeners twice");
    }

    @Test
    void withoutRestoreOrReplayTheWindowIsReportedAsNotNarrowed() {
        ref.snapshotComplete = false;
        cand.snapshotComplete = false;
        cand.faultAtStep = 41;
        cand.faultMask = 1;
        Divergence d = lockstep().granularity(Granularity.everySteps(10)).build().run(1000).divergence();
        Narrowing n = d.narrowing();
        assertEquals(Narrowing.Status.NOT_NARROWED, n.status());
        assertTrue(d.firstStep().isEmpty());
        assertTrue(n.reason().contains("reference \"ref\" and candidate \"cand\" cannot be restored"), n.reason());
        assertTrue(n.reason().contains("no replay factory"), n.reason());
        String report = d.report();
        assertTrue(report.startsWith("LOCKSTEP DIVERGENCE after step 50 (comparison #5, last match after step 40)"),
                report);
        assertTrue(report.contains("NOT NARROWED: the first difference is somewhere in steps 41..50 (compared every"
                + " 10 step(s)): "), report);
    }

    @Test
    void oneSideWithoutCompleteSnapshotsIsNamed() {
        cand.snapshotComplete = false;
        cand.faultAtStep = 41;
        cand.faultMask = 1;
        Narrowing n = lockstep().granularity(Granularity.everySteps(10)).build().run(1000).divergence().narrowing();
        assertEquals(Narrowing.Status.NOT_NARROWED, n.status());
        assertTrue(n.reason().contains("candidate \"cand\" cannot be restored"), n.reason());
        assertFalse(n.reason().contains("reference \"ref\""), n.reason());
    }

    @Test
    void explicitStateSaversEnableRestore() {
        ref.snapshotComplete = false;
        cand.snapshotComplete = false;
        cand.faultAtStep = 41;
        cand.faultMask = 1;
        Narrowing n = lockstep().granularity(Granularity.everySteps(10))
                .restoreWith(StateSaver.of(ref), StateSaver.of(cand))
                .build().run(1000).divergence().narrowing();
        assertEquals(Narrowing.Status.NARROWED, n.status());
        assertEquals(42, n.step());
    }

    @Test
    void aSaverThatFailsIsReported() {
        cand.faultAtStep = 41;
        cand.faultMask = 1;
        StateSaver broken = new StateSaver() {
            @Override
            public com.darchan.diag.Snapshot save() {
                throw new UnsupportedOperationException("cannot save");
            }

            @Override
            public void restore(com.darchan.diag.Snapshot saved) {
            }
        };
        Narrowing n = lockstep().granularity(Granularity.everySteps(10)).restoreWith(StateSaver.of(ref), broken)
                .build().run(1000).divergence().narrowing();
        assertEquals(Narrowing.Status.NOT_NARROWED, n.status());
        assertTrue(n.reason().contains("restore: saving the state at the comparison after step 0 threw"
                + " java.lang.UnsupportedOperationException: cannot save"), n.reason());
    }

    @Test
    void anIncompleteSaverCannotProduceAWrongStep() {
        // the candidate's saver forgets ACC: the re-run starts from a state that never existed and differs at
        // once (step 41, one too early); continuing to the end of the window does not reproduce the coarse
        // comparison, so the step is not reported
        cand.faultAtStep = 41;
        cand.faultMask = 1;
        StateSaver leaky = new StateSaver() {
            @Override
            public com.darchan.diag.Snapshot save() {
                return cand.snapshot();
            }

            @Override
            public void restore(com.darchan.diag.Snapshot saved) {
                int acc = cand.acc;
                cand.restore(saved);
                cand.acc = acc;
            }
        };
        Divergence d = lockstep().granularity(Granularity.everySteps(10)).restoreWith(StateSaver.of(ref), leaky)
                .build().run(1000).divergence();
        Narrowing n = d.narrowing();
        assertEquals(Narrowing.Status.NOT_NARROWED, n.status(), d::report);
        assertTrue(n.reason().contains("restore: the re-run differed after step 41 but did not reproduce the coarse"
                + " comparison after step 50"), n.reason());
        assertTrue(n.reason().contains("candidate \"cand\": ACC"), n.reason());
    }

    @Test
    void aWindowIsNarrowedByReplayingFreshModels() {
        ref.snapshotComplete = false;
        cand.snapshotComplete = false;
        cand.faultAtStep = 41;
        cand.faultMask = 0x10;
        Granularity g = Granularity.everyCycles(100);
        Divergence d = lockstep().granularity(g)
                .replayWith(fresh(c -> {
                    c.faultAtStep = 41;
                    c.faultMask = 0x10;
                }, g))
                .build().run(10_000).divergence();
        Narrowing n = d.narrowing();
        assertEquals(Narrowing.Status.NARROWED, n.status(), d::report);
        assertEquals(Narrowing.Method.REPLAY, n.method());
        assertEquals(42, n.step());
        assertEquals("ACC", n.diff().fieldDifferences().get(0).name());
        assertTrue(n.candidate().lastInstruction().orElseThrow().contains(" 29: 29  ADD PC"), d::report);
        assertTrue(d.report().contains("narrowed by replay from steps 27..50"), d::report);
        // the original models stay where the coarse comparison stopped them
        assertEquals(50, ref.steps);
        assertEquals(50, cand.steps);
    }

    @Test
    void aReplayThatDoesNotReproduceTheDivergenceIsReported() {
        ref.snapshotComplete = false;
        cand.snapshotComplete = false;
        cand.faultAtStep = 41;
        cand.faultMask = 1;
        Divergence d = lockstep().granularity(Granularity.everySteps(10))
                .replayWith(fresh(c -> { }, Granularity.everySteps(10)))
                .build().run(1000).divergence();
        Narrowing n = d.narrowing();
        assertEquals(Narrowing.Status.NOT_NARROWED, n.status());
        assertTrue(n.reason().contains("replay did not reproduce the divergence"), n.reason());
        assertTrue(n.reason().contains("deterministic"), n.reason());
    }

    @Test
    void aReplayThatDivergesBeforeTheWindowIsReported() {
        ref.snapshotComplete = false;
        cand.snapshotComplete = false;
        cand.faultAtStep = 41;
        cand.faultMask = 1;
        Narrowing n = lockstep().granularity(Granularity.everySteps(10))
                .replayWith(fresh(c -> {
                    c.faultAtStep = 5;
                    c.faultMask = 1;
                }, Granularity.everySteps(10)))
                .build().run(1000).divergence().narrowing();
        assertEquals(Narrowing.Status.NOT_NARROWED, n.status());
        assertTrue(n.reason().contains("replay diverged after step 10, before the last matching comparison (after"
                + " step 40)"), n.reason());
    }

    @Test
    void aReplayConfiguredDifferentlyIsRejected() {
        ref.snapshotComplete = false;
        cand.snapshotComplete = false;
        cand.faultAtStep = 41;
        cand.faultMask = 1;
        Narrowing n = lockstep().granularity(Granularity.everySteps(10)).ignore("CYCLES")
                .replayWith(fresh(c -> c.faultAtStep = 41, Granularity.everySteps(10)))
                .build().run(1000).divergence().narrowing();
        assertEquals(Narrowing.Status.NOT_NARROWED, n.status());
        assertTrue(n.reason().contains("configured differently"), n.reason());
        assertTrue(n.reason().contains("ignored fields"), n.reason());
    }

    @Test
    void aNonReproducibleFaultIsNotNarrowedByRestore() {
        cand.faultAtStep = 41;
        cand.faultMask = 1;
        cand.faultOnce = true;
        Divergence d = lockstep().granularity(Granularity.everySteps(10)).build().run(1000).divergence();
        Narrowing n = d.narrowing();
        assertEquals(Narrowing.Status.NOT_NARROWED, n.status(), d::report);
        assertTrue(n.reason().contains("re-running steps 41..50 from the restored last match did not reproduce the"
                + " divergence"), n.reason());
    }

    @Test
    void replayIsTriedWhenRestoreDoesNotReproduce() {
        cand.faultAtStep = 41;
        cand.faultMask = 1;
        cand.faultOnce = true;
        Narrowing n = lockstep().granularity(Granularity.everySteps(10))
                .replayWith(fresh(c -> {
                    c.faultAtStep = 41;
                    c.faultMask = 1;
                    c.faultOnce = true;
                }, Granularity.everySteps(10)))
                .build().run(1000).divergence().narrowing();
        assertEquals(Narrowing.Status.NARROWED, n.status(), n::toString);
        assertEquals(Narrowing.Method.REPLAY, n.method());
        assertEquals(42, n.step());
        assertTrue(n.reason().contains("restore: "), "the failed restore attempt is still reported: " + n.reason());
    }

    @Test
    void aStateDifferenceBeforeAnExceptionIsFound() {
        cand.faultAtStep = 41;
        cand.faultMask = 1;
        cand.throwAtStep = 45;
        Divergence d = lockstep().granularity(Granularity.everySteps(10)).build().run(1000).divergence();
        assertNotNull(d.failure(), "the coarse run saw the exception");
        Narrowing n = d.narrowing();
        assertEquals(Narrowing.Status.NARROWED, n.status(), d::report);
        assertEquals(42, n.step());
        assertNull(n.failure());
        assertEquals("ACC", n.diff().fieldDifferences().get(0).name());
    }

    @Test
    void anExceptionInsideAWindowIsPinnedToItsStep() {
        cand.throwAtStep = 45;
        Divergence d = lockstep().granularity(Granularity.everySteps(10)).build().run(1000).divergence();
        Narrowing n = d.narrowing();
        assertEquals(Narrowing.Status.NARROWED, n.status(), d::report);
        assertEquals(46, n.step(), "the 46th step (index 45) threw");
        assertTrue(n.failure().contains("candidate \"cand\" threw java.lang.IllegalStateException: boom at step 45"),
                n.failure());
        assertNull(n.diff());
        assertTrue(d.report().contains("--- failure during step 46 ---"), d::report);
    }

    @Test
    void anInitialDifferenceNeedsNoNarrowing() {
        cand.acc = 9;
        Divergence d = lockstep().granularity(Granularity.everySteps(10)).build().run(100).divergence();
        assertEquals(Narrowing.Status.NOT_NEEDED, d.narrowing().status());
        assertEquals(0, d.firstStep().orElseThrow());
    }
}
