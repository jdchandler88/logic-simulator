package com.darchan.diag.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TraceTest {

    /** 8080-like layout, but defined here so the test does not depend on the behavioral layer */
    static final TraceLayout LAYOUT = new TraceLayout("i8080", 16, 3, 14, List.of(
            RegisterSpec.of("A", 8), RegisterSpec.flags("F", "SZ.A.P.C"), RegisterSpec.of("B", 8),
            RegisterSpec.of("SP", 16)));

    static final InstructionDecoder DECODER = (pc, bytes, len) -> len == 3 ? "LXI SP,0700H" : "NOP";

    static TraceEvent event(long cycle, long instruction, int pc, int f, int... bytes) {
        TraceEvent e = new TraceEvent(LAYOUT, DECODER);
        e.begin(cycle, instruction, pc, false);
        for (int b : bytes) {
            e.addByte(b);
        }
        e.setRegister(0, 0x3E);
        e.setRegister(1, f);
        e.setRegister(2, 0);
        e.setRegister(3, 0xF000);
        return e;
    }

    @Test
    void exactLineFormat() {
        assertEquals("000000004924 000000000651 0100: 31 00 07  LXI SP,0700H   A=3E F=D7 B=00 SP=F000 SZAPC=SZAPC",
                TraceFormatter.format(event(4924, 651, 0x100, 0xD7, 0x31, 0x00, 0x07)));
        assertEquals("000000000000 000000000000 FFFF: 00        NOP            A=3E F=02 B=00 SP=F000 SZAPC=-----",
                TraceFormatter.format(event(0, 0, 0xFFFF, 0x02, 0x00)));
    }

    @Test
    void interruptAndWideCounters() {
        TraceEvent e = event(123_456_789_012_345L, 1, 0x1234, 0x46, 0xFF);
        e.begin(123_456_789_012_345L, 1, 0x1234, true);
        e.addByte(0xFF);
        assertEquals("123456789012345 000000000001 1234: FF        INT NOP        A=3E F=46 B=00 SP=F000 SZAPC=-Z-P-",
                TraceFormatter.format(e));
    }

    @Test
    void eventIsReusableAndCopyable() {
        TraceEvent e = event(1, 1, 0x100, 0, 0x31, 0, 7);
        TraceEvent copy = e.copy();
        e.begin(2, 2, 0x103, false);
        e.addByte(0);
        assertEquals(0x100, copy.pc());
        assertEquals(3, copy.length());
        assertEquals("LXI SP,0700H", copy.disassembly());
        assertEquals("NOP", e.disassembly());
        assertEquals(0xF000, copy.register("SP"));
        assertThrows(IllegalArgumentException.class, () -> copy.register("Q"));
    }

    @Test
    void ringBufferKeepsTheLastLinesInOrder() {
        TraceRingBuffer ring = new TraceRingBuffer(3);
        assertEquals(0, ring.size());
        assertTrue(ring.lines().isEmpty());
        TraceEvent e = new TraceEvent(LAYOUT, DECODER);
        for (int i = 0; i < 5; i++) {
            e.begin(i * 4L, i, 0x100 + i, false);
            e.addByte(0);
            ring.onInstruction(e);
        }
        assertEquals(3, ring.size());
        assertEquals(5, ring.totalSeen());
        List<String> lines = ring.lines();
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).startsWith("000000000008 000000000002 0102: 00 "), lines.get(0));
        assertTrue(lines.get(2).startsWith("000000000016 000000000004 0104: 00 "), lines.get(2));
        assertTrue(ring.dump().startsWith("--- last 3 of 5 traced instruction(s) (i8080) ---\n"));
        ring.clear();
        assertEquals(0, ring.size());
    }

    @Test
    void ringBufferLinesMatchTheFormatter() {
        TraceRingBuffer ring = new TraceRingBuffer(2);
        TraceEvent e = event(4924, 651, 0x100, 0xD7, 0x31, 0x00, 0x07);
        ring.onInstruction(e);
        assertEquals(List.of(TraceFormatter.format(e)), ring.lines());
    }

    @Test
    void inMemoryTraceAndLimit() {
        InMemoryTrace t = new InMemoryTrace(1);
        t.onInstruction(event(0, 0, 0, 0, 0));
        t.onInstruction(event(4, 1, 1, 0, 0));
        assertEquals(1, t.lines().size());
        assertEquals(1, t.dropped());
        assertTrue(t.text().endsWith("\n"));
    }

    @Test
    void textWriterWritesOneLinePerInstruction(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("trace.txt");
        try (TextTraceWriter w = TextTraceWriter.toFile(file)) {
            w.writeLine(TraceFormatter.header(LAYOUT));
            w.onInstruction(event(0, 0, 0x100, 0x02, 0x31, 0, 7));
            w.onInstruction(event(10, 1, 0x103, 0x02, 0));
            assertEquals(2, w.linesWritten());
        }
        List<String> lines = Files.readAllLines(file);
        assertEquals(3, lines.size());
        assertEquals("# i8080 trace: cycle instruction pc: bytes  disassembly A F B SP", lines.get(0));
        assertEquals(TraceFormatter.format(event(10, 1, 0x103, 0x02, 0)), lines.get(2));
    }

    @Test
    void bytesCanBeSetPackedAndAreMaskedToTheLength() {
        TraceEvent e = event(0, 0, 0x100, 0x02);
        e.setBytes(0xAA070031, 3); // 31 00 07 + a garbage fourth byte
        assertEquals(3, e.length());
        assertEquals(0x070031, e.packedBytes());
        assertEquals(0x07, e.byteAt(2));
        assertThrows(IndexOutOfBoundsException.class, () -> e.byteAt(3));
        assertEquals(TraceFormatter.format(event(0, 0, 0x100, 0x02, 0x31, 0x00, 0x07)), TraceFormatter.format(e));
        assertThrows(IllegalArgumentException.class, () -> e.setBytes(0, 4));
        e.setBytes(0, 0);
        assertThrows(IllegalStateException.class, () -> {
            e.addByte(1);
            e.addByte(2);
            e.addByte(3);
            e.addByte(4);
        });
    }

    @Test
    void layoutValidation() {
        assertThrows(IllegalArgumentException.class, () -> new TraceLayout("x", 16, 5, 10, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RegisterSpec("F", 8, "SZ"));
        assertEquals("SZAPC", RegisterSpec.flags("F", "SZ.A.P.C").flagNames());
    }
}
