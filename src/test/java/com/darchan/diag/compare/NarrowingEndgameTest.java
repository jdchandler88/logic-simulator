package com.darchan.diag.compare;

import com.darchan.diag.run.Debugger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** The last few narrowing and checkpoint details: windows ending in the fault, drifting replays, wording of reasons. */
class NarrowingEndgameTest {

    @TempDir
    Path dir;

    @Test
    void aFaultInTheLastStepOfAWindowNeedsNoCrossCheckNote() {
        TinyMachine ref = new TinyMachine();
        TinyMachine cand = new TinyMachine();
        cand.faultAtStep = 19;
        cand.faultMask = 1;
        Narrowing n = Lockstep.builder("ref", new Debugger(ref, null), "cand", new Debugger(cand, null))
                .granularity(Granularity.everySteps(10)).build().run(1000).divergence().narrowing();
        assertEquals(Narrowing.Status.NARROWED, n.status());
        assertEquals(20, n.step());
        assertEquals("", n.reason(), "found at the very end of the window: nothing left to cross-check, nothing to note");
    }

    @Test
    void aReplayThatComparesAtOtherStepsThanTheOriginalIsReported() {
        TinyMachine ref = new TinyMachine();
        TinyMachine cand = new TinyMachine();
        ref.snapshotComplete = false;
        cand.snapshotComplete = false;
        cand.faultAtStep = 40;
        cand.faultMask = 1;
        Supplier<Lockstep.Builder> slower = () -> {
            TinyMachine r = new TinyMachine();
            TinyMachine c = new TinyMachine();
            r.snapshotComplete = false;
            c.snapshotComplete = false;
            r.extraCycles = 1; // both replayed sides run a cycle slower per step than the originals
            c.extraCycles = 1;
            c.faultAtStep = 40;
            c.faultMask = 1;
            return Lockstep.builder("ref", new Debugger(r, null), "cand", new Debugger(c, null))
                    .granularity(Granularity.everyCycles(100)).ignore("CYCLES");
        };
        Narrowing n = Lockstep.builder("ref", new Debugger(ref, null), "cand", new Debugger(cand, null))
                .granularity(Granularity.everyCycles(100)).ignore("CYCLES").replayWith(slower).build().run(1000)
                .divergence().narrowing();
        assertEquals(Narrowing.Status.NOT_NARROWED, n.status());
        assertTrue(n.reason().contains("replay: the replay's last comparison before the window was after step"),
                n.reason());
    }

    @Test
    void anUnreachedCheckpointOfARunWithoutAMismatchSaysTheRunEnded() {
        SnapshotCheckpoints.in(dir).add(Checkpoint.atInstruction("a", 3)).record(new Debugger(new TinyMachine(), null), 1000);
        CheckpointReport r = SnapshotCheckpoints.in(dir).add(Checkpoint.atInstruction("a", 3))
                .add(Checkpoint.atInstruction("far", 400)).verify(new Debugger(new TinyMachine(), null), 30);
        assertEquals(CheckpointResult.Status.MATCH, r.result("a").orElseThrow().status());
        assertEquals("run ended first", r.result("far").orElseThrow().detail());
    }
}
