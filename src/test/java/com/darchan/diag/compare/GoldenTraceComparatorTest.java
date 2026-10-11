package com.darchan.diag.compare;

import com.darchan.diag.trace.TraceEvent;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The line-by-line golden comparison itself: markers, differing columns, ignored columns, and the comparison of the
 * state a run stopped in. Events are fed by hand so each line is exactly what the test says.
 */
class GoldenTraceComparatorTest {

    private static final String L0 = "000000000000 000000000000 00: 00  ADD PC ACC=00";
    private static final String L1 = "000000000003 000000000001 01: 01  ADD PC ACC=00";
    private static final String L2 = "000000000008 000000000002 02: 02  ADD PC ACC=01";

    private static TraceEvent event(long cycle, long instruction, int pc, int acc) {
        TraceEvent e = new TraceEvent(TinyMachine.LAYOUT, (address, bytes, length) -> "ADD PC");
        e.begin(cycle, instruction, pc, false);
        e.setBytes(pc, 1);
        e.setRegister(0, acc);
        return e;
    }

    private static GoldenTraceComparator comparator(String text, Set<String> ignored, int context) {
        return new GoldenTraceComparator("t", new BufferedReader(new StringReader(text)), ignored, context);
    }

    // ---- live lines ------------------------------------------------------------------------------------------------

    @Test
    void matchingLinesAreCountedAndTheGoldenFileIsConsumedInOrder() {
        GoldenTraceComparator c = comparator("# header\n" + L0 + "\n\n" + L1 + "\n", Set.of(), 4);
        assertEquals("t", c.source());
        assertEquals(L0, c.nextExpectedLine());
        assertEquals(2, c.nextExpectedLineNumber(), "the header is line 1");
        assertFalse(c.done());
        assertFalse(c.exhausted());
        c.onInstruction(event(0, 0, 0, 0));
        assertEquals(1, c.linesCompared());
        assertEquals(L1, c.nextExpectedLine());
        assertEquals(4, c.nextExpectedLineNumber(), "a blank line is skipped but counted");
        c.onInstruction(event(3, 1, 1, 0));
        assertEquals(2, c.linesCompared());
        assertTrue(c.exhausted());
        assertTrue(c.done());
        assertFalse(c.mismatched());
        assertNull(c.mismatch());
        // once the golden trace is used up further events are ignored
        c.onInstruction(event(8, 2, 2, 9));
        assertEquals(2, c.linesCompared());
        assertFalse(c.mismatched());
    }

    @Test
    void theFirstMismatchIsKeptAndLaterLinesAreNotCompared() {
        GoldenTraceComparator c = comparator(L0 + "\n" + L1 + "\n" + L2 + "\n", Set.of(), 4);
        c.onInstruction(event(0, 0, 0, 0));
        c.onInstruction(event(3, 1, 1, 7)); // wrong ACC
        assertTrue(c.mismatched());
        assertTrue(c.done());
        TraceMismatch m = c.mismatch();
        assertEquals(2, m.lineNumber());
        assertEquals(1, m.instruction());
        assertEquals(L1, m.expected());
        assertEquals("000000000003 000000000001 01: 01  ADD PC ACC=07", m.actual());
        assertEquals(List.of("ACC: expected 00, actual 07"), m.differingColumns());
        assertEquals(List.of(L0), m.context());
        assertEquals(List.of(L2), m.following());
        assertEquals(1, c.linesCompared());
        c.onInstruction(event(8, 2, 2, 1)); // would have matched the next line, but we stopped comparing
        assertSame(m, c.mismatch());
        assertEquals(1, c.linesCompared());
    }

    @Test
    void markersPointAtEveryDifferingCharacterIncludingExtraTrailingOnes() {
        // the golden line is one character shorter than the live one: the extra character is marked too
        String shorter = "000000000000 000000000000 00: 00  ADD PC ACC=0";
        TraceMismatch m = mismatchFor(shorter, event(0, 0, 0, 0));
        assertEquals(" ".repeat(shorter.length()) + "^", m.markers());
        // and the other way round: the live line is shorter
        String longer = L0 + "0";
        m = mismatchFor(longer, event(0, 0, 0, 0));
        assertEquals(" ".repeat(L0.length()) + "^", m.markers());
        // a difference in the first character and in the last
        String firstAndLast = "100000000000 000000000000 00: 00  ADD PC ACC=01";
        m = mismatchFor(firstAndLast, event(0, 0, 0, 0));
        assertEquals("^" + " ".repeat(L0.length() - 2) + "^", m.markers());
    }

    private static TraceMismatch mismatchFor(String golden, TraceEvent live) {
        GoldenTraceComparator c = comparator(golden + "\n", Set.of(), 0);
        c.onInstruction(live);
        assertTrue(c.mismatched());
        return c.mismatch();
    }

    @Test
    void missingAndEmptyColumnsAreNamedInTheDifferences() {
        // the golden line has no ACC column at all
        TraceMismatch m = mismatchFor("000000000000 000000000000 00: 00  ADD PC", event(0, 0, 0, 0));
        assertEquals(List.of("ACC: expected <missing>, actual 00"), m.differingColumns());
        // the golden line has an empty ACC value
        m = mismatchFor("000000000000 000000000000 00: 00  ADD PC ACC=", event(0, 0, 0, 0));
        assertEquals(List.of("ACC: expected <empty>, actual 00"), m.differingColumns());
        // a column only the golden line has (live lines have just ACC)
        m = mismatchFor(L0 + " EXTRA=1", event(0, 0, 0, 0));
        assertEquals(List.of("EXTRA: expected 1, actual <missing>"), m.differingColumns());
        // a header-like line is a single 'line' column
        m = mismatchFor("not a trace line", event(0, 0, 0, 0));
        assertEquals("line: expected not a trace line, actual <missing>", m.differingColumns().get(0));
        assertTrue(m.differingColumns().contains("cycle: expected <missing>, actual 000000000000"),
                m.differingColumns().toString());
    }

    @Test
    void contextKeepsOnlyTheLastMatchingLinesAndNoneWhenDisabled() {
        StringBuilder golden = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            golden.append(String.format("%012d %012d %02X: %02X  ADD PC ACC=00%n", i * 3, i, i, i));
        }
        golden.append("000000000018 000000000006 06: 06  ADD PC ACC=00\n");
        for (int context : new int[]{0, 1, 3}) {
            GoldenTraceComparator c = comparator(golden.toString(), Set.of(), context);
            for (int i = 0; i < 6; i++) {
                c.onInstruction(event(i * 3, i, i, 0));
            }
            c.onInstruction(event(18, 6, 6, 1));
            assertEquals(context, c.mismatch().context().size(), "context " + context);
            if (context > 0) {
                assertTrue(c.mismatch().context().get(context - 1).startsWith("000000000015 000000000005 05:"),
                        c.mismatch().context().toString());
            }
        }
    }

    @Test
    void followingLinesSkipCommentsAndStopAtThree() {
        String golden = L0 + "\n# note\n\n" + L1 + "\n" + L2 + "\n" + L0 + "\n" + L1 + "\n" + L2 + "\n";
        GoldenTraceComparator c = comparator(golden, Set.of(), 0);
        c.onInstruction(event(99, 0, 0, 0));
        assertEquals(List.of(L1, L2, L0), c.mismatch().following());
    }

    // ---- ignored columns -----------------------------------------------------------------------------------------

    @Test
    void ignoredColumnsDoNotMaskDifferencesElsewhere() {
        GoldenTraceComparator c = comparator(L1 + "\n", Set.of("cycle"), 0);
        c.onInstruction(event(99, 1, 1, 5)); // cycle and ACC both differ; only ACC counts
        assertTrue(c.mismatched());
        TraceMismatch m = c.mismatch();
        assertEquals(List.of("ACC: expected 00, actual 05"), m.differingColumns());
        // the markers skip the ignored column: nothing under the cycle digits
        String marks = m.markers();
        assertEquals(' ', marks.charAt(0));
        assertEquals(' ', marks.charAt(11));
        assertEquals('^', marks.charAt(marks.length() - 1));
        assertEquals(1, marks.chars().filter(ch -> ch == '^').count(), marks);
    }

    @Test
    void aDifferenceOnlyInIgnoredColumnsIsAMatch() {
        GoldenTraceComparator c = comparator(L1 + "\n", Set.of("cycle", "instruction"), 0);
        c.onInstruction(event(1234, 77, 1, 0));
        assertFalse(c.mismatched());
        assertEquals(1, c.linesCompared());
    }

    @Test
    void ignoringTheLastColumnStillMarksTheOthers() {
        // ACC is the last column; ignoring it must not hide a difference in the pc column
        GoldenTraceComparator c = comparator(L1 + "\n", Set.of("ACC"), 0);
        c.onInstruction(event(3, 1, 2, 9));
        TraceMismatch m = c.mismatch();
        assertEquals(List.of("pc: expected 01, actual 02", "bytes: expected 01, actual 02"), m.differingColumns());
        assertEquals(-1, m.markers().indexOf('^', L1.length() - 2), "nothing marked in the ignored ACC value");
    }

    // ---- stopped state -----------------------------------------------------------------------------------------

    private static TinyMachine machineAt(int steps) {
        TinyMachine m = new TinyMachine();
        for (int i = 0; i < steps; i++) {
            m.step();
        }
        return m;
    }

    @Test
    void aStoppedStateThatMatchesTheNextGoldenLineIsNotAMismatch() {
        TinyMachine m = machineAt(2);
        GoldenTraceComparator c = comparator(L2 + "\n", Set.of(), 0);
        assertFalse(c.compareStoppedState(m));
        assertFalse(c.mismatched());
    }

    @Test
    void aStoppedStateIsComparedColumnByColumn() {
        TinyMachine m = machineAt(2);
        m.acc ^= 0x40;
        m.cycles += 100;
        GoldenTraceComparator c = comparator(L2 + "\n", Set.of(), 0);
        assertTrue(c.compareStoppedState(m));
        TraceMismatch mm = c.mismatch();
        assertEquals(List.of("cycle: expected 000000000008, actual 000000000108", "ACC: expected 01, actual 41"),
                mm.differingColumns());
        assertEquals("000000000108 000000000002 02: ??  ?????? ACC=41", mm.actual());
        assertNotNull(mm.note());
        assertEquals(2, mm.instruction());
        // 'cycle' and 'ACC' are marked, with alignment under their values
        assertEquals("^".repeat(12), mm.markers().substring(0, 12));
        assertEquals('^', mm.markers().charAt(mm.markers().length() - 1));
        // the same call again keeps the first mismatch and reports nothing new
        assertFalse(c.compareStoppedState(m));
    }

    @Test
    void theInstructionPcAndRegisterColumnsAreComparedWithTheTargetsCounters() {
        TinyMachine m = machineAt(2);
        m.steps = 3;
        GoldenTraceComparator c = comparator(L2 + "\n", Set.of(), 0);
        assertTrue(c.compareStoppedState(m));
        assertEquals(List.of("instruction: expected 000000000002, actual 000000000003"),
                c.mismatch().differingColumns());

        m = machineAt(2);
        m.pc = 0x1A;
        c = comparator(L2 + "\n", Set.of(), 0);
        assertTrue(c.compareStoppedState(m));
        assertEquals(List.of("pc: expected 02, actual 1A"), c.mismatch().differingColumns());
    }

    @Test
    void aCounterWiderThanItsColumnIsNotTruncated() {
        TinyMachine m = machineAt(2);
        m.cycles = 1_000_000_000_008L; // 13 digits against a 12-digit column
        GoldenTraceComparator c = comparator(L2 + "\n", Set.of(), 0);
        assertTrue(c.compareStoppedState(m));
        assertEquals(List.of("cycle: expected 000000000008, actual 1000000000008"), c.mismatch().differingColumns());
    }

    @Test
    void nothingIsComparedWhenThereIsNothingToCompare() {
        TinyMachine m = machineAt(2);
        m.acc = 0x55;
        // the golden trace is used up
        GoldenTraceComparator done = comparator(L0 + "\n", Set.of(), 0);
        done.onInstruction(event(0, 0, 0, 0));
        assertTrue(done.exhausted());
        assertFalse(done.compareStoppedState(m));
        // a mismatch was already found
        GoldenTraceComparator bad = comparator(L0 + "\n" + L1 + "\n", Set.of(), 0);
        bad.onInstruction(event(0, 0, 0, 9));
        assertTrue(bad.mismatched());
        assertFalse(bad.compareStoppedState(m));
        // the next line is not a trace line
        GoldenTraceComparator text = comparator("this is not a trace line\n", Set.of(), 0);
        assertFalse(text.compareStoppedState(m));
        assertFalse(text.mismatched());
        // every rebuildable column is ignored
        GoldenTraceComparator ignoring = comparator(L2 + "\n", Set.of("cycle", "instruction", "pc", "ACC"), 0);
        assertFalse(ignoring.compareStoppedState(m));
        assertFalse(ignoring.mismatched());
        // a register the target does not have, and no other differing column
        GoldenTraceComparator unknownRegister = comparator("000000000008 000000000002 02: 02  ADD PC NOPE=77\n",
                Set.of(), 0);
        assertFalse(unknownRegister.compareStoppedState(machineAt(2)));
    }

    @Test
    void ignoredColumnsAreSkippedAtTheStopButOthersStillCount() {
        TinyMachine m = machineAt(2);
        m.acc = 0x66;
        m.cycles = 5;
        GoldenTraceComparator c = comparator(L2 + "\n", Set.of("cycle"), 0);
        assertTrue(c.compareStoppedState(m));
        assertEquals(List.of("ACC: expected 01, actual 66"), c.mismatch().differingColumns());
    }

    // ---- reading ---------------------------------------------------------------------------------------------------

    @Test
    void closeClosesTheReaderAndReadErrorsAreUnchecked() throws IOException {
        boolean[] closed = {false};
        Reader r = new StringReader("") {
            @Override
            public void close() {
                closed[0] = true;
            }
        };
        GoldenTraceComparator c = new GoldenTraceComparator("t", new BufferedReader(r), Set.of(), 0);
        c.close();
        assertTrue(closed[0]);

        Reader broken = new Reader() {
            @Override
            public int read(char[] cbuf, int off, int len) throws IOException {
                throw new IOException("disk gone");
            }

            @Override
            public void close() throws IOException {
                throw new IOException("close failed");
            }
        };
        assertThrows(java.io.UncheckedIOException.class,
                () -> new GoldenTraceComparator("t", new BufferedReader(broken), Set.of(), 0));
        GoldenTraceComparator ok = new GoldenTraceComparator("t", new BufferedReader(new Reader() {
            @Override
            public int read(char[] cbuf, int off, int len) {
                return -1;
            }

            @Override
            public void close() throws IOException {
                throw new IOException("close failed");
            }
        }), Set.of(), 0);
        assertThrows(java.io.UncheckedIOException.class, ok::close);
    }
}
