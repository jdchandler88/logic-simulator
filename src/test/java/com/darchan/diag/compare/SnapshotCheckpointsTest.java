package com.darchan.diag.compare;

import com.darchan.diag.run.Debugger;
import com.darchan.diag.run.StopReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.darchan.diag.compare.CheckpointResult.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotCheckpointsTest {

    @TempDir
    Path dir;

    private SnapshotCheckpoints set() {
        return SnapshotCheckpoints.in(dir)
                .add(Checkpoint.atInstruction("start", 0))
                .add(Checkpoint.atInstruction("i10", 10))
                .add(Checkpoint.atCycle("c100", 100))
                .add(Checkpoint.atPc("pc5", 5))
                .add(Checkpoint.atPc("pc5-again", 5, 2));
    }

    private static CheckpointReport run(SnapshotCheckpoints cps, TinyMachine m, boolean record) {
        Debugger dbg = new Debugger(m, null);
        return record ? cps.record(dbg, 100_000) : cps.verify(dbg, 100_000);
    }

    @Test
    void recordSavesOneFilePerCheckpointWhereItIsReached() {
        CheckpointReport r = run(set(), new TinyMachine(), true);
        assertTrue(r.allPassed(), r::report);
        assertEquals(List.of("start", "pc5", "i10", "c100", "pc5-again"),
                r.results().stream().map(x -> x.checkpoint().name()).toList(), "in the order reached");
        assertEquals(0, r.result("start").orElseThrow().instruction());
        assertEquals(5, r.result("pc5").orElseThrow().instruction());
        assertEquals(10, r.result("i10").orElseThrow().instruction());
        assertEquals(104, r.result("c100").orElseThrow().cycle(), "first boundary at or after cycle 100");
        assertEquals(261, r.result("pc5-again").orElseThrow().instruction(), "PC wraps after 256 steps");
        for (Checkpoint c : set().checkpoints()) {
            assertTrue(Files.exists(dir.resolve(c.name() + ".json")), c.name());
        }
    }

    @Test
    void aFaithfulRunMatchesEveryCheckpoint() {
        run(set(), new TinyMachine(), true);
        CheckpointReport r = run(set(), new TinyMachine(), false);
        assertTrue(r.allPassed(), r::report);
        assertTrue(r.results().stream().allMatch(x -> x.status() == MATCH));
        assertTrue(r.report().startsWith("checkpoints: 5 of 5 passed"), r::report);
    }

    @Test
    void theFirstMismatchStopsTheRunWithADiffAndTrace() {
        run(set(), new TinyMachine(), true);
        TinyMachine faulty = new TinyMachine();
        faulty.faultAtStep = 15;
        faulty.faultMask = 0x02;
        CheckpointReport r = run(set(), faulty, false);
        assertFalse(r.allPassed());
        CheckpointResult first = r.firstFailure().orElseThrow();
        assertEquals("c100", first.checkpoint().name());
        assertEquals(MISMATCH, first.status());
        assertEquals("ACC", first.diff().fieldDifferences().get(0).name());
        assertEquals(8, first.recentTrace().size());
        assertEquals(MATCH, r.result("i10").orElseThrow().status());
        assertEquals(NOT_REACHED, r.result("pc5-again").orElseThrow().status());
        assertEquals(26, faulty.steps, "stopped at the mismatching checkpoint");
        String report = r.report();
        assertTrue(report.contains("MISMATCH    c100 @ cycle 100  (instruction 26, cycle 104, PC=001A)"), report);
        assertTrue(report.contains("--- c100 @ cycle 100 ---"), report);
        assertTrue(report.contains("not run: an earlier checkpoint mismatched"), report);
    }

    @Test
    void canContinuePastMismatchesAndIgnoreFields() {
        run(set(), new TinyMachine(), true);
        TinyMachine faulty = new TinyMachine();
        faulty.faultAtStep = 15;
        faulty.faultMask = 0x02;
        CheckpointReport r = run(set().stopAtFirstMismatch(false), faulty, false);
        assertEquals(MISMATCH, r.result("pc5-again").orElseThrow().status());

        TinyMachine faulty2 = new TinyMachine();
        faulty2.faultAtStep = 15;
        faulty2.faultMask = 0x02;
        assertTrue(run(set().ignore("ACC"), faulty2, false).allPassed());
    }

    @Test
    void missingFilesAndUnreachedCheckpointsAreReported() {
        SnapshotCheckpoints cps = SnapshotCheckpoints.in(dir).add(Checkpoint.atInstruction("nofile", 3))
                .add(Checkpoint.atCycle("far", 1_000_000_000L)).stopAtFirstMismatch(false);
        TinyMachine m = new TinyMachine();
        CheckpointReport r = cps.verify(new Debugger(m, null), 1000);
        assertEquals(NO_FILE, r.result("nofile").orElseThrow().status());
        assertEquals(NOT_REACHED, r.result("far").orElseThrow().status());
        assertEquals(StopReason.Kind.CYCLE_LIMIT, r.stop().kind());
    }

    @Test
    void namesMustBeFileFriendlyAndUnique() {
        assertThrows(IllegalArgumentException.class, () -> Checkpoint.atCycle("a b", 1));
        assertThrows(IllegalArgumentException.class, () -> Checkpoint.atCycle("../x", 1));
        assertThrows(IllegalArgumentException.class, () -> SnapshotCheckpoints.in(dir)
                .add(Checkpoint.atCycle("x", 1)).add(Checkpoint.atCycle("x", 2)));
    }
}
