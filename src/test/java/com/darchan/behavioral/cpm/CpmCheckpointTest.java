package com.darchan.behavioral.cpm;

import com.darchan.diag.Snapshot;
import com.darchan.diag.compare.Checkpoint;
import com.darchan.diag.compare.CheckpointReport;
import com.darchan.diag.compare.CheckpointResult;
import com.darchan.diag.compare.SnapshotCheckpoints;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Snapshot checkpoints (registers + low RAM) on TST8080, recorded from a clean run. */
class CpmCheckpointTest {

    @TempDir
    Path dir;

    private static SnapshotCheckpoints checkpoints(Path dir, CpmMachine m) {
        return SnapshotCheckpoints.in(dir)
                .add(Checkpoint.atInstruction("i300", 300))
                .add(Checkpoint.atCycle("c3000", 3000))
                .add(Checkpoint.atPc("exit", 0x0000))
                .snapshot(() -> snapshot(m));
    }

    private static Snapshot snapshot(CpmMachine m) {
        return m.cpu().snapshot().withRegion("ram", m.ram()::peek, 0x0000, 0x0800);
    }

    private CheckpointReport record() throws IOException {
        CpmMachine m = new CpmMachine(CpmRomTest.rom("TST8080.COM"));
        return checkpoints(dir, m).record(m.debugger(), 1_000_000);
    }

    @Test
    void aCleanRunPassesEveryCheckpointIncludingTheExit() throws IOException {
        CheckpointReport rec = record();
        assertTrue(rec.allPassed(), rec::report);
        assertEquals(650, rec.result("exit").orElseThrow().instruction());
        CpmMachine m = new CpmMachine(CpmRomTest.rom("TST8080.COM"));
        CheckpointReport r = checkpoints(dir, m).verify(m.debugger(), 1_000_000);
        assertTrue(r.allPassed(), r::report);
    }

    @Test
    void aCorruptedWriteShowsUpAtTheNextCheckpointInMemory() throws IOException {
        record();
        CpmMachine m = new CpmMachine(CpmRomTest.rom("TST8080.COM"));
        FaultInjectingBus.install(m, FaultInjectingBus.Kind.WRITE, 358, 0x80); // MOV M,A at 0474
        CheckpointReport r = checkpoints(dir, m).verify(m.debugger(), 1_000_000);
        System.out.println(r.report());
        assertEquals(CheckpointResult.Status.MATCH, r.result("i300").orElseThrow().status());
        CheckpointResult c = r.firstFailure().orElseThrow();
        assertEquals("c3000", c.checkpoint().name());
        assertEquals(CheckpointResult.Status.MISMATCH, c.status());
        assertTrue(c.diff().byteDifferences().stream().anyMatch(b -> b.address() == 0x06BF), r::report);
        // the corrupted byte makes TST8080 report an error and exit early: the exit checkpoint is never verified
        assertEquals(CheckpointResult.Status.NOT_REACHED, r.result("exit").orElseThrow().status());
    }
}
