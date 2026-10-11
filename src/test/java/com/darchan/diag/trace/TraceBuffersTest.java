package com.darchan.diag.trace;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Ring buffer wrap-around, rollback, and the accessors of {@link TraceEvent}. */
class TraceBuffersTest {

    private static final TraceLayout PLAIN = new TraceLayout("p", 16, 2, 6,
            List.of(RegisterSpec.of("A", 8), RegisterSpec.flags("F", "S......C")));
    private static final TraceLayout GATES = new TraceLayout("g", 8, 1, 6,
            List.of(RegisterSpec.of("X", 12), RegisterSpec.flags("F", "Z......C")), true);
    private static final InstructionDecoder DECODER = (pc, bytes, length) -> "OP" + length;

    private static TraceEvent plain(int i) {
        TraceEvent e = new TraceEvent(PLAIN, DECODER);
        e.begin(i * 10L, i, 0x100 + i, i % 3 == 0);
        e.setBytes(0x0200 + i, 1 + i % 2);
        e.setRegister(0, i);
        e.setRegister(1, i % 2 == 0 ? 0x81 : 0x00);
        return e;
    }

    private static TraceEvent gates(int i) {
        TraceEvent e = new TraceEvent(GATES, DECODER);
        e.begin(i, i, i, false);
        e.setBytes(i, 1);
        e.setRegister(0, 0x0A0 + i, i % 2 == 0 ? 0x00F : 0, i % 2 == 0 ? 0 : 0xF00);
        e.setRegister(1, 0x01, 0, 0);
        return e;
    }

    private static List<String> expectedLines(TraceEvent[] events, int from) {
        List<String> out = new ArrayList<>();
        for (int i = from; i < events.length; i++) {
            out.add(TraceFormatter.format(events[i]));
        }
        return out;
    }

    // ---- ring buffer ----------------------------------------------------------------------------------------------

    @Test
    void theCapacityIsAtLeastOne() {
        assertThrows(IllegalArgumentException.class, () -> new TraceRingBuffer(0));
        assertThrows(IllegalArgumentException.class, () -> new TraceRingBuffer(-1));
        assertEquals(1, new TraceRingBuffer(1).capacity());
        assertEquals(64, new TraceRingBuffer(64).capacity());
    }

    @Test
    void itKeepsTheLastNEventsInOrderThroughWraparound() {
        for (int capacity : new int[]{1, 2, 3, 5}) {
            for (int count = 0; count <= 12; count++) {
                TraceRingBuffer ring = new TraceRingBuffer(capacity);
                TraceEvent[] events = new TraceEvent[count];
                for (int i = 0; i < count; i++) {
                    events[i] = plain(i);
                    ring.onInstruction(events[i]);
                }
                String context = "capacity " + capacity + ", events " + count;
                assertEquals(Math.min(count, capacity), ring.size(), context);
                assertEquals(count, ring.totalSeen(), context);
                assertEquals(expectedLines(events, Math.max(0, count - capacity)), ring.lines(), context);
            }
        }
    }

    @Test
    void unknownBitsSurviveTheBuffer() {
        TraceRingBuffer ring = new TraceRingBuffer(3);
        TraceEvent[] events = new TraceEvent[7];
        for (int i = 0; i < events.length; i++) {
            events[i] = gates(i);
            ring.onInstruction(events[i]);
        }
        assertEquals(expectedLines(events, 4), ring.lines());
        assertTrue(ring.lines().get(0).contains("X=0Az"), ring.lines().get(0));
        assertTrue(ring.lines().get(1).contains("X=x"), ring.lines().get(1));
        assertTrue(ring.lines().stream().anyMatch(l -> l.contains("z") || l.contains("x") || l.contains("?")),
                ring.lines().toString());
    }

    @Test
    void interruptsAndByteCountsSurviveTheBuffer() {
        TraceRingBuffer ring = new TraceRingBuffer(4);
        TraceEvent[] events = new TraceEvent[4];
        for (int i = 0; i < 4; i++) {
            events[i] = plain(i); // i = 0 and 3 are interrupts; lengths alternate 1, 2
            ring.onInstruction(events[i]);
        }
        List<String> lines = ring.lines();
        assertEquals(expectedLines(events, 0), lines);
        assertTrue(lines.get(0).contains("INT "), lines.get(0));
        assertFalse(lines.get(1).contains("INT "), lines.get(1));
        assertTrue(lines.get(3).contains("INT "), lines.get(3));
    }

    @Test
    void aNewLayoutStartsTheBufferOver() {
        TraceRingBuffer ring = new TraceRingBuffer(4);
        ring.onInstruction(plain(0));
        ring.onInstruction(plain(1));
        TraceEvent g = gates(9);
        ring.onInstruction(g);
        assertEquals(1, ring.size());
        assertEquals(1, ring.totalSeen());
        assertEquals(List.of(TraceFormatter.format(g)), ring.lines());
    }

    @Test
    void clearForgetsEverything() {
        TraceRingBuffer ring = new TraceRingBuffer(3);
        for (int i = 0; i < 5; i++) {
            ring.onInstruction(plain(i));
        }
        ring.clear();
        assertEquals(0, ring.size());
        assertEquals(0, ring.totalSeen());
        assertTrue(ring.lines().isEmpty());
        ring.onInstruction(plain(7));
        assertEquals(List.of(TraceFormatter.format(plain(7))), ring.lines());
    }

    @Test
    void rollbackDropsTheEventsAfterTheMark() {
        TraceRingBuffer ring = new TraceRingBuffer(4);
        for (int i = 0; i < 3; i++) {
            ring.onInstruction(plain(i));
        }
        long mark = ring.totalSeen();
        for (int i = 3; i < 6; i++) {
            ring.onInstruction(plain(i));
        }
        assertEquals(4, ring.size());
        ring.rollback(mark);
        assertEquals(3, mark);
        assertEquals(mark, ring.totalSeen());
        // events 0 and 1 were overwritten by 4 and 5 before the rollback, so only 2 remains of those before the mark
        assertEquals(1, ring.size());
        assertEquals(List.of(TraceFormatter.format(plain(2))), ring.lines());
        ring.onInstruction(plain(10));
        assertEquals(List.of(TraceFormatter.format(plain(2)), TraceFormatter.format(plain(10))), ring.lines());
    }

    @Test
    void rollbackToTheCurrentTotalChangesNothingAndBadMarksAreRejected() {
        TraceRingBuffer ring = new TraceRingBuffer(4);
        for (int i = 0; i < 3; i++) {
            ring.onInstruction(plain(i));
        }
        List<String> before = ring.lines();
        ring.rollback(3);
        assertEquals(before, ring.lines());
        assertEquals(3, ring.totalSeen());
        assertThrows(IllegalArgumentException.class, () -> ring.rollback(4));
        assertThrows(IllegalArgumentException.class, () -> ring.rollback(-1));
        ring.rollback(0);
        assertEquals(0, ring.size());
        assertEquals(0, ring.totalSeen());
        ring.rollback(0); // harmless on an empty buffer
    }

    @Test
    void theDumpHasATitleAndTheLines() {
        TraceRingBuffer ring = new TraceRingBuffer(2);
        assertEquals("--- last 0 of 0 traced instruction(s) ---", ring.dump());
        for (int i = 0; i < 3; i++) {
            ring.onInstruction(plain(i));
        }
        assertEquals("--- last 2 of 3 traced instruction(s) (p) ---\n" + TraceFormatter.format(plain(1)) + "\n"
                + TraceFormatter.format(plain(2)), ring.dump());
    }

    // ---- trace event ---------------------------------------------------------------------------------------------------

    @Test
    void bytesAreBoundsChecked() {
        TraceEvent e = plain(1); // length 2
        assertEquals(2, e.length());
        assertEquals(0x01, e.byteAt(0));
        assertEquals(0x02, e.byteAt(1));
        assertThrows(IndexOutOfBoundsException.class, () -> e.byteAt(2));
        assertThrows(IndexOutOfBoundsException.class, () -> e.byteAt(-1));
        assertEquals(0x0201, e.packedBytes());
    }

    @Test
    void addByteAndSetBytesCheckTheInstructionLength() {
        TraceEvent e = new TraceEvent(PLAIN, DECODER);
        e.begin(0, 0, 0, false);
        e.addByte(0x1FF); // masked to a byte
        e.addByte(0x7);
        assertEquals(0x07FF, e.packedBytes());
        assertThrows(IllegalStateException.class, () -> e.addByte(1));
        assertThrows(IllegalArgumentException.class, () -> e.setBytes(0, 3));
        assertThrows(IllegalArgumentException.class, () -> e.setBytes(0, -1));
        e.setBytes(0x7F7F7F, 1);
        assertEquals(0x7F, e.packedBytes(), "bytes beyond the length are dropped");
        e.setBytes(0xFFFFFF, 0);
        assertEquals(0, e.packedBytes());
        e.begin(1, 1, 1, false);
        assertEquals(0, e.length());
        assertEquals(0, e.packedBytes());
    }

    @Test
    void registersAreReadByIndexAndByName() {
        TraceEvent e = plain(6);
        assertEquals(2, e.registerCount());
        assertEquals(6, e.register(0));
        assertEquals(0x81, e.register(1));
        assertEquals(6, e.register("A"));
        assertEquals(0x81, e.register("F"));
        assertThrows(IllegalArgumentException.class, () -> e.register("Q"));
        assertEquals(0, e.registerZ(0), "no masks in a plain layout");
        assertEquals(0, e.registerX(0));
        assertThrows(IllegalStateException.class, () -> e.setRegister(0, 1, 0, 0));
    }

    @Test
    void maskedRegistersKeepTheirMasksUntilOverwritten() {
        TraceEvent e = new TraceEvent(GATES, DECODER);
        e.begin(0, 0, 0, false);
        e.setRegister(0, 0x0A0, 0x00F, 0xF00);
        assertEquals(0x0A0, e.register(0));
        assertEquals(0x00F, e.registerZ(0));
        assertEquals(0xF00, e.registerX(0));
        assertEquals(0, e.registerZ(1));
        e.setRegister(0, 0x123);
        assertEquals(0, e.registerZ(0), "a plain write clears the masks");
        assertEquals(0, e.registerX(0));
        assertEquals(0x123, e.register(0));
    }

    @Test
    void equalityComparesEverythingAndCopiesAreIndependent() {
        TraceEvent a = plain(4);
        TraceEvent b = plain(4);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a.hashCode(), plain(5).hashCode());
        assertNotEquals(a, plain(5));
        assertNotEquals(a, null);
        assertNotEquals(a, "a string");
        TraceEvent c = plain(4);
        c.setRegister(0, 99);
        assertNotEquals(a, c, "one register differs");
        TraceEvent d = plain(4);
        d.begin(40, 4, 0x104, true); // interrupt flag differs
        d.setBytes(0x0204, 1);
        d.setRegister(0, 4);
        d.setRegister(1, 0x81);
        assertNotEquals(a, d);
        TraceEvent g1 = gates(2);
        TraceEvent g2 = gates(2);
        assertEquals(g1, g2);
        g2.setRegister(0, g1.register(0), g1.registerZ(0), 0x001);
        assertNotEquals(g1, g2, "masks are part of the identity");
        TraceEvent copy = a.copy();
        assertEquals(a, copy);
        a.setRegister(0, 77);
        assertEquals(4, copy.register(0), "the copy does not follow later changes");
        TraceEvent gc = g1.copy();
        g1.setRegister(0, 1, 2, 4);
        assertEquals(0x0A2, gc.register(0));
        assertEquals(0x00F, gc.registerZ(0), "masks are copied too");
    }

    @Test
    void theDisassemblyIsComputedOnceAndRefreshedByBegin() {
        int[] calls = {0};
        TraceEvent e = new TraceEvent(PLAIN, (pc, bytes, length) -> {
            calls[0]++;
            return "D" + calls[0];
        });
        e.begin(0, 0, 0, false);
        e.setBytes(1, 1);
        assertEquals("D1", e.disassembly());
        assertEquals("D1", e.disassembly());
        e.begin(1, 1, 1, false);
        assertEquals("D2", e.disassembly());
        assertSame(DECODER, new TraceEvent(PLAIN, DECODER).decoder());
        assertSame(PLAIN, e.layout());
    }
}
