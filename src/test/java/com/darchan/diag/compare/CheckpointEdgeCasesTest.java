package com.darchan.diag.compare;

import com.darchan.diag.Snapshot;
import com.darchan.diag.SnapshotDiff;
import com.darchan.diag.run.Debugger;
import com.darchan.diag.run.StopReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.darchan.diag.compare.CheckpointResult.Status.*;
import static org.junit.jupiter.api.Assertions.*;

/** Triggers, result lines and report text of the snapshot checkpoints, and the state saver helpers. */
class CheckpointEdgeCasesTest {

    @TempDir
    Path dir;

    // ---- triggers --------------------------------------------------------------------------------------------------

    @Test
    void instructionAndCycleTriggersFireAtOrAfterTheirCount() {
        TinyMachine m = new TinyMachine();
        Checkpoint.Trigger instr = new Checkpoint.AtInstruction(2);
        Checkpoint.Trigger cycle = new Checkpoint.AtCycle(8);
        assertFalse(instr.reached(m));
        assertFalse(cycle.reached(m));
        m.step(); // 1 instruction, 3 cycles
        assertFalse(instr.reached(m));
        assertFalse(cycle.reached(m));
        m.step(); // 2 instructions, 8 cycles: exactly on both
        assertTrue(instr.reached(m));
        assertTrue(cycle.reached(m));
        m.step();
        assertTrue(instr.reached(m), "and stays reached");
        assertTrue(cycle.reached(m));
        assertEquals("instruction 2", instr.toString());
        assertEquals("cycle 8", cycle.toString());
        assertSame(instr, instr.fresh());
        assertSame(cycle, cycle.fresh());
        assertEquals("start @ instruction 0", Checkpoint.atInstruction("start", 0).toString());
        assertEquals("c @ cycle 5", Checkpoint.atCycle("c", 5).toString());
    }

    @Test
    void aPcTriggerCountsOccurrencesAndFreshResetsTheCount() {
        TinyMachine m = new TinyMachine();
        Checkpoint.AtPc second = new Checkpoint.AtPc(0, 2);
        assertEquals(0, second.address());
        assertEquals(2, second.occurrence());
        assertFalse(second.reached(m), "first boundary at PC 0: occurrence 1 of 2");
        assertTrue(second.reached(m), "second boundary at PC 0: occurrence 2");
        assertFalse(second.reached(m), "the third is past it");
        Checkpoint.AtPc fresh = (Checkpoint.AtPc) second.fresh();
        assertNotSame(second, fresh);
        assertEquals(0, fresh.address());
        assertEquals(2, fresh.occurrence());
        assertFalse(fresh.reached(m));
        assertTrue(fresh.reached(m), "the second boundary at PC 0 is occurrence 2");
        assertFalse(fresh.reached(m), "the third is past it");
        assertThrows(IllegalArgumentException.class, () -> new Checkpoint.AtPc(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Checkpoint.AtPc(0, -1));
        assertEquals(1, new Checkpoint.AtPc(0, 1).occurrence());
        assertEquals("PC=0005", new Checkpoint.AtPc(5, 1).toString());
        assertEquals("PC=00FF (#3)", new Checkpoint.AtPc(255, 3).toString());
        assertEquals("p @ PC=0010 (#2)", Checkpoint.atPc("p", 0x10, 2).toString());
        assertEquals("p @ PC=0010", Checkpoint.atPc("p", 0x10).toString());
    }

    // ---- result lines ------------------------------------------------------------------------------------------------

    private static SnapshotDiff diff() {
        return Snapshot.builder("m").hex("A", 8, 1).build().diff(Snapshot.builder("m").hex("A", 8, 2).build());
    }

    @Test
    void aResultSummaryNamesStatusCheckpointPositionAndDetail() {
        Checkpoint c = Checkpoint.atInstruction("boot", 1000);
        assertEquals("MATCH       boot @ instruction 1000  (instruction 1000, cycle 7012, PC=01C2)",
                new CheckpointResult(c, MATCH, 1000, 7012, 0x1C2, null, null, List.of()).summary());
        assertEquals("MISMATCH    boot @ instruction 1000  (instruction 1000, cycle 7012, PC=01C2): 2 field(s) differ",
                new CheckpointResult(c, MISMATCH, 1000, 7012, 0x1C2, diff(), "2 field(s) differ", List.of()).summary());
        assertEquals("NOT_REACHED boot @ instruction 1000: run ended first",
                new CheckpointResult(c, NOT_REACHED, -1, -1, -1, null, "run ended first", List.of()).summary());
        assertEquals("RECORDED    boot @ instruction 1000  (instruction 0, cycle 0, PC=0000)",
                new CheckpointResult(c, RECORDED, 0, 0, 0, null, null, List.of()).summary(),
                "position 0 is a real position");
        assertTrue(new CheckpointResult(c, MISMATCH, 1, 1, 1, diff(), null, List.of()).snapshotDiff().isPresent());
        assertTrue(new CheckpointResult(c, NO_FILE, 1, 1, 1, null, null, List.of()).snapshotDiff().isEmpty());
        assertEquals(c.name() + " @ " + c.trigger(), c.toString());
    }

    @Test
    void aReportCountsPassesListsEachResultAndExplainsMismatches() {
        Checkpoint a = Checkpoint.atInstruction("a", 1);
        Checkpoint b = Checkpoint.atInstruction("b", 2);
        Checkpoint c = Checkpoint.atInstruction("c", 3);
        Checkpoint d = Checkpoint.atInstruction("d", 4);
        Checkpoint e = Checkpoint.atInstruction("e", 5);
        StopReason stop = new StopReason(StopReason.Kind.CONDITION, "stopped at the first mismatch", 12, 3);
        CheckpointReport r = new CheckpointReport(List.of(
                new CheckpointResult(a, MATCH, 1, 3, 1, null, null, List.of()),
                new CheckpointResult(b, RECORDED, 2, 8, 2, null, "saved b.json", List.of()),
                new CheckpointResult(c, MISMATCH, 3, 11, 3, diff(), "1 field(s) differ", List.of("t1", "t2")),
                new CheckpointResult(d, NO_FILE, 4, 14, 4, null, "cannot load", List.of()),
                new CheckpointResult(e, NOT_REACHED, -1, -1, -1, null, "run ended first", List.of())), stop);
        assertFalse(r.allPassed());
        assertEquals(c, r.firstFailure().orElseThrow().checkpoint());
        assertEquals(Optional.empty(), r.result("zzz"));
        assertEquals(b, r.result("b").orElseThrow().checkpoint());
        String report = r.report();
        assertEquals(report, r.toString());
        assertTrue(report.startsWith("checkpoints: 2 of 5 passed; run ended: " + stop + "\n"), report);
        assertTrue(report.contains("\n  MATCH       a @ instruction 1  (instruction 1, cycle 3, PC=0001)\n"), report);
        assertTrue(report.contains("\n  RECORDED    b @ instruction 2  (instruction 2, cycle 8, PC=0002): saved b.json\n"), report);
        assertTrue(report.contains("\n--- c @ instruction 3 ---\n" + diff().report()), report);
        assertTrue(report.contains("\n--- last 2 instruction(s) before it ---\n  t1\n  t2"), report);
        assertFalse(report.contains("--- d @"), "only mismatches get a section");
        // a passing report has no mismatch section, and a mismatch without trace has no trace section
        CheckpointReport passing = new CheckpointReport(List.of(new CheckpointResult(a, MATCH, 1, 3, 1, null, null,
                List.of())), stop);
        assertTrue(passing.allPassed());
        assertTrue(passing.firstFailure().isEmpty());
        assertFalse(passing.report().contains("---"));
        CheckpointReport noTrace = new CheckpointReport(List.of(new CheckpointResult(c, MISMATCH, 3, 11, 3, diff(),
                "x", List.of())), stop);
        assertFalse(noTrace.report().contains("instruction(s) before it"));
        assertTrue(new CheckpointReport(List.of(), stop).allPassed(), "no checkpoints: nothing failed");
    }

    @Test
    void theCheckpointSetListsItsCheckpointsAndFiles() {
        SnapshotCheckpoints cps = SnapshotCheckpoints.in(dir).add(Checkpoint.atInstruction("a", 1))
                .add(Checkpoint.atCycle("b", 2));
        assertEquals(List.of("a", "b"), cps.checkpoints().stream().map(Checkpoint::name).toList());
        assertEquals(dir.resolve("a.json"), cps.file(cps.checkpoints().get(0)));
        assertThrows(UnsupportedOperationException.class, () -> cps.checkpoints().add(null));
        assertThrows(NullPointerException.class, () -> SnapshotCheckpoints.in(null));
        assertThrows(NullPointerException.class, () -> cps.snapshot(null));
    }

    @Test
    void aCustomSnapshotSupplierIsUsedAndTheInitialStateCanBeACheckpoint() {
        TinyMachine m = new TinyMachine();
        SnapshotCheckpoints rec = SnapshotCheckpoints.in(dir).add(Checkpoint.atInstruction("zero", 0))
                .snapshot(() -> Snapshot.builder("custom").hex("X", 8, 7).build());
        CheckpointReport r = rec.record(new Debugger(m, null), 100);
        assertEquals(RECORDED, r.result("zero").orElseThrow().status());
        assertEquals(0, m.steps, "the initial state is handled before anything runs");
        assertEquals("stopped at all checkpoints handled".isEmpty() ? "" : "all checkpoints handled", r.stop().detail());
        TinyMachine m2 = new TinyMachine();
        CheckpointReport v = SnapshotCheckpoints.in(dir).add(Checkpoint.atInstruction("zero", 0))
                .snapshot(() -> Snapshot.builder("custom").hex("X", 8, 8).build()).verify(new Debugger(m2, null), 100);
        assertEquals(MISMATCH, v.result("zero").orElseThrow().status());
        assertEquals("1 field(s) and 0 memory byte(s) differ", v.result("zero").orElseThrow().detail());
        assertEquals(StopReason.Kind.CONDITION, v.stop().kind());
        assertEquals("stopped at the first mismatch", v.stop().detail());
    }

    @Test
    void aTightCycleBudgetEndsTheRunWithAUsedUpBudget() {
        TinyMachine m = new TinyMachine();
        CheckpointReport r = SnapshotCheckpoints.in(dir).add(Checkpoint.atInstruction("late", 500))
                .record(new Debugger(m, null), 20);
        assertEquals(NOT_REACHED, r.result("late").orElseThrow().status());
        assertEquals("run ended first", r.result("late").orElseThrow().detail());
        assertEquals(StopReason.Kind.CYCLE_LIMIT, r.stop().kind());
        assertFalse(r.allPassed());
        CheckpointReport none = SnapshotCheckpoints.in(dir).add(Checkpoint.atInstruction("late", 500))
                .record(new Debugger(new TinyMachine(), null), 0);
        assertEquals("cycle budget used up", none.stop().detail());
    }

    // ---- state savers ---------------------------------------------------------------------------------------------------

    @Test
    void aSaverWithMemoryRoundTripsTheRegionAndRejectsASaveWithoutIt() {
        TinyMachine m = new TinyMachine();
        int[] mem = {1, 2, 3, 4};
        AtomicReference<com.darchan.diag.MemoryRegion> written = new AtomicReference<>();
        StateSaver saver = StateSaver.of(m).withMemory("ram", a -> mem[a], written::set, 0, 4);
        m.step();
        Snapshot saved = saver.save();
        assertEquals(1, saved.regions().size());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, saved.region("ram").orElseThrow().bytes());
        m.step();
        m.step();
        saver.restore(saved);
        assertEquals(1, m.steps, "the model's own state was restored");
        assertEquals("ram", written.get().name());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, written.get().bytes(), "and the memory writer was called");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> saver.restore(m.snapshot()));
        assertTrue(e.getMessage().contains("no memory region ram"), e.getMessage());
        assertThrows(NullPointerException.class, () -> StateSaver.of(null));
        assertThrows(NullPointerException.class, () -> StateSaver.of(m).withMemory(null, a -> 0, r -> { }, 0, 1));
        assertThrows(NullPointerException.class, () -> StateSaver.of(m).withMemory("r", null, r -> { }, 0, 1));
        assertThrows(NullPointerException.class, () -> StateSaver.of(m).withMemory("r", a -> 0, null, 0, 1));
    }

    // ---- trace mismatch report -----------------------------------------------------------------------------------------

    @Test
    void aTraceMismatchReportCoversNotesEmptyColumnsAndTheEndOfFile() {
        TraceMismatch plain = new TraceMismatch("g.trace", 7, 5, "exp", "act", "   ^", List.of("A: expected 1, actual 2"),
                List.of("ctx1", "ctx2"), List.of("next1"));
        assertEquals("GOLDEN TRACE MISMATCH at line 7 of g.trace (instruction 5)\n"
                + "  expected: exp\n"
                + "  actual:   act\n"
                + "               ^\n"
                + "  differing columns: A: expected 1, actual 2\n"
                + "--- context: 2 matching line(s) before ---\n  ctx1\n  ctx2\n"
                + "--- the golden trace continues with ---\n  next1", plain.report());
        assertEquals(plain.report(), plain.toString());
        assertNull(plain.note());
        TraceMismatch noted = new TraceMismatch("g", 1, 0, "e", "a", "^", List.of(), List.of(), List.of(),
                "rebuilt from state");
        assertEquals("GOLDEN TRACE MISMATCH at line 1 of g (instruction 0)\n  (rebuilt from state)\n"
                + "  expected: e\n  actual:   a\n            ^\n  differing columns: (none parsed)\n"
                + "--- context: 0 matching line(s) before ---\n--- the golden trace continues with ---\n"
                + "  (end of file)", noted.report());
        List<String> mutable = new java.util.ArrayList<>(List.of("x"));
        TraceMismatch copy = new TraceMismatch("g", 1, 0, "e", "a", "^", mutable, mutable, mutable);
        mutable.add("y");
        assertEquals(List.of("x"), copy.context(), "lists are copied");
    }

    // ---- writers -------------------------------------------------------------------------------------------------------

    @Test
    void theTextWriterFlushesOnRequestAndOnCloseButClosesOnlyWhatItOwns() throws IOException {
        boolean[] closed = {false};
        boolean[] flushed = {false};
        Writer w = new StringWriter() {
            @Override
            public void flush() {
                flushed[0] = true;
            }

            @Override
            public void close() {
                closed[0] = true;
            }
        };
        com.darchan.diag.trace.TextTraceWriter t = com.darchan.diag.trace.TextTraceWriter.to(w);
        t.writeLine("# header");
        assertEquals(0, t.linesWritten(), "raw lines are not instruction lines");
        t.flush();
        assertTrue(flushed[0]);
        flushed[0] = false;
        t.close();
        assertTrue(closed[0], "to(Writer) closes it");

        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        boolean[] streamClosed = {false};
        java.io.PrintStream stream = new java.io.PrintStream(bytes) {
            @Override
            public void close() {
                streamClosed[0] = true;
            }
        };
        com.darchan.diag.trace.TextTraceWriter s = com.darchan.diag.trace.TextTraceWriter.to(stream);
        s.writeLine("hello");
        assertEquals("", bytes.toString(), "buffered until flushed");
        s.close();
        assertEquals("hello\n", bytes.toString(), "close flushes the stream it does not own");
        assertFalse(streamClosed[0], "and does not close it");

        Path file = dir.resolve("t.trace");
        com.darchan.diag.trace.TextTraceWriter f = com.darchan.diag.trace.TextTraceWriter.toFile(file);
        f.writeLine("one");
        f.close();
        assertEquals("one\n", java.nio.file.Files.readString(file));
        assertThrows(java.io.UncheckedIOException.class,
                () -> com.darchan.diag.trace.TextTraceWriter.toFile(dir.resolve("no/such/dir/t.trace")));
    }
}
