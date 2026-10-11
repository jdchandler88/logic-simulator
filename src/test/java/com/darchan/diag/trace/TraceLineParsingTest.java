package com.darchan.diag.trace;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** How {@link TraceLine} splits lines, and that anything it cannot split becomes a single {@code line} column. */
class TraceLineParsingTest {

    private static final String OK = "000000000010 000000000002 0010: 3E 3F     MVI A,3FH      A=01 F=02";

    @Test
    void columnsPointExactlyAtTheirText() {
        TraceLine t = TraceLine.parse(OK);
        assertEquals(List.of("cycle", "instruction", "pc", "bytes", "disassembly", "A", "F"),
                t.columns().stream().map(TraceLine.Column::name).toList());
        for (TraceLine.Column c : t.columns()) {
            assertEquals(c.text(), OK.substring(c.start(), c.end()), c.name());
        }
        TraceLine.Column cycle = t.column("cycle").orElseThrow();
        assertEquals(0, cycle.start());
        assertEquals(12, cycle.end());
        TraceLine.Column instruction = t.column("instruction").orElseThrow();
        assertEquals(13, instruction.start());
        assertEquals(25, instruction.end());
        TraceLine.Column pc = t.column("pc").orElseThrow();
        assertEquals("0010", pc.text());
        assertEquals(26, pc.start());
        assertEquals(30, pc.end());
        assertEquals(OK, t.line());
        assertEquals("MVI A,3FH", t.text("disassembly").orElseThrow());
        assertEquals("01", t.text("A").orElseThrow());
        assertEquals("02", t.text("F").orElseThrow());
        assertTrue(t.text("nope").isEmpty());
        assertTrue(t.column("nope").isEmpty());
    }

    @Test
    void aLineWithOnlyTheFixedColumnsHasAnEmptyDisassemblyColumn() {
        TraceLine t = TraceLine.parse("000000000010 000000000002 0010: 3E");
        assertTrue(t.isTrace());
        assertEquals("3E", t.text("bytes").orElseThrow());
        assertEquals("", t.text("disassembly").orElseThrow());
        t = TraceLine.parse("000000000010 000000000002 0010: ");
        assertTrue(t.isTrace());
        assertEquals("", t.text("bytes").orElseThrow());
    }

    @Test
    void valuesMayBeEmptyAndTokensKeepTheirOrder() {
        TraceLine t = TraceLine.parse("000000000000 000000000000 00: 00  NOP A=  B=1");
        assertTrue(t.isTrace());
        assertEquals("", t.text("A").orElseThrow());
        assertEquals("1", t.text("B").orElseThrow());
        assertEquals(List.of("A", "B"), t.columns().stream().skip(5).map(TraceLine.Column::name).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", " ", "x", "# comment line", "no spaces at all",
            "000000000000", "000000000000 ", "000000000000 000000000000", "000000000000 000000000000 ",
            "000000000000 000000000000 0010", "000000000000 000000000000 0010:", "000000000000 000000000000 00 10: 00",
            "00000000000x 000000000000 0010: 00  NOP A=1", "000000000000 00000000000x 0010: 00  NOP A=1",
            " 000000000000 0010: 00  NOP", "000000000000  0010: 00  NOP A=1",
            "000000000000 000000000000 0010: 00  NOP A=1 A=2",
            "000000000000 000000000000 0010: 00  NOP A=1 junk"})
    void linesThatAreNotTraceLinesBecomeOneColumn(String text) {
        TraceLine t = TraceLine.parse(text);
        assertFalse(t.isTrace(), text);
        assertEquals(1, t.columns().size());
        TraceLine.Column c = t.column("line").orElseThrow();
        assertEquals(0, c.start());
        assertEquals(text.length(), c.end());
        assertEquals(text, c.text());
        assertEquals(text, t.text("line").orElseThrow());
        assertTrue(t.column("cycle").isEmpty(), "no half-parsed columns survive: " + t.columns());
    }

    @Test
    void aDuplicateColumnNameMakesTheWholeLineOneColumn() {
        TraceLine t = TraceLine.parse("000000000000 000000000000 0010: 00  NOP A=1 A=2");
        assertFalse(t.isTrace());
    }

    @Test
    void nameCharactersAreLettersDigitsUnderscoreAndDot() {
        TraceLine t = TraceLine.parse("000000000000 000000000000 0010: 00  NOP r_1.x=5 Y9=7");
        assertEquals("5", t.text("r_1.x").orElseThrow());
        assertEquals("7", t.text("Y9").orElseThrow());
        assertEquals("NOP", t.text("disassembly").orElseThrow());
    }

    @Property
    void parsingNeverThrowsAndColumnsAlwaysMatchTheirText(@ForAll String text) {
        TraceLine t = TraceLine.parse(text);
        assertFalse(t.columns().isEmpty());
        for (TraceLine.Column c : t.columns()) {
            assertTrue(c.start() >= 0 && c.start() <= c.end() && c.end() <= text.length(), c.toString());
            assertEquals(text.substring(c.start(), c.end()), c.text());
        }
        assertEquals(text, t.line());
        assertEquals(t.isTrace(), t.column("line").isEmpty());
    }

    @Property
    void formatterLinesAlwaysParseBackAsTraceLines(@ForAll("lines") String line) {
        assertTrue(TraceLine.parse(line).isTrace(), line);
    }

    @net.jqwik.api.Provide
    net.jqwik.api.Arbitrary<String> lines() {
        return net.jqwik.api.Arbitraries.integers().between(0, 0xFFFF).tuple3().map(t -> {
            TraceEvent e = new TraceEvent(TraceTest.LAYOUT, TraceTest.DECODER);
            e.begin(t.get1() * 1000L, t.get2(), t.get3(), t.get1() % 2 == 0);
            e.setBytes(t.get1() | t.get2() << 8, t.get3() % 4);
            e.setRegister(0, t.get1() & 0xFF);
            e.setRegister(1, t.get2() & 0xFF);
            e.setRegister(2, t.get3() & 0xFF);
            e.setRegister(3, t.get1());
            return TraceFormatter.format(e);
        });
    }
}
