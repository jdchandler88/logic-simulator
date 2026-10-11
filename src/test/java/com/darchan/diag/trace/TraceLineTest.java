package com.darchan.diag.trace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TraceLineTest {

    private static final String LINE =
            "000000003126 000000000437 0508: 3A BF 06  LDA 06BFH      A=00 F=46 B=12 SP=07BD SZAPC=-Z-P-";

    @Test
    void splitsAnInstructionLineIntoNamedColumns() {
        TraceLine t = TraceLine.parse(LINE);
        assertTrue(t.isTrace());
        assertEquals(List.of("cycle", "instruction", "pc", "bytes", "disassembly", "A", "F", "B", "SP", "SZAPC"),
                t.columns().stream().map(TraceLine.Column::name).toList());
        assertEquals("000000003126", t.text("cycle").orElseThrow());
        assertEquals("0508", t.text("pc").orElseThrow());
        assertEquals("3A BF 06", t.text("bytes").orElseThrow());
        assertEquals("LDA 06BFH", t.text("disassembly").orElseThrow());
        assertEquals("07BD", t.text("SP").orElseThrow());
        assertEquals("-Z-P-", t.text("SZAPC").orElseThrow());
        TraceLine.Column a = t.column("A").orElseThrow();
        assertEquals("00", LINE.substring(a.start(), a.end()));
    }

    @Test
    void disassemblyWithSpacesAndCommasAndInterrupts() {
        TraceLine t = TraceLine.parse("000000000010 000000000002 0010: FF        INT RST 7      A=01");
        assertEquals("INT RST 7", t.text("disassembly").orElseThrow());
        assertEquals("FF", t.text("bytes").orElseThrow());
        t = TraceLine.parse("000000000010 000000000002 0010: 3E 3F     MVI A,3FH      A=01");
        assertEquals("MVI A,3FH", t.text("disassembly").orElseThrow());
    }

    @Test
    void roundTripsWhatTheFormatterWrites() {
        TraceEvent e = new TraceEvent(new TraceLayout("t", 16, 3, 10,
                List.of(RegisterSpec.of("A", 8), RegisterSpec.flags("F", "SZ.A.P.C"))), (addr, b, len) -> "OP X,Y");
        e.begin(12, 3, 0x1234, false);
        e.setBytes(0x0201, 2);
        e.setRegister(0, 0xAB);
        e.setRegister(1, 0x41);
        TraceLine t = TraceLine.parse(TraceFormatter.format(e));
        assertEquals("1234", t.text("pc").orElseThrow());
        assertEquals("01 02", t.text("bytes").orElseThrow());
        assertEquals("OP X,Y", t.text("disassembly").orElseThrow());
        assertEquals("AB", t.text("A").orElseThrow());
        assertEquals("-Z--C", t.text("SZAPC").orElseThrow());
    }

    @Test
    void anythingElseIsOneColumn() {
        TraceLine t = TraceLine.parse("# i8080 trace: cycle instruction pc: bytes  disassembly A F");
        assertFalse(t.isTrace());
        assertEquals(1, t.columns().size());
        assertEquals("line", t.columns().get(0).name());
    }
}
