package com.darchan.diag.trace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Trace columns of models with Z/X bits (gate level): z, x and ? per hex digit, z/x per flag. */
class UnknownBitsTraceTest {

    static final TraceLayout LAYOUT = new TraceLayout("gates", 16, 1, 6, List.of(RegisterSpec.of("A", 8),
            RegisterSpec.of("W", 5), RegisterSpec.flags("F", "SZ.A.P.C")), true);

    private static TraceEvent event(int a, int az, int ax, int w, int wz, int wx, int f, int fz, int fx) {
        TraceEvent e = new TraceEvent(LAYOUT, (pc, bytes, len) -> "CLK");
        e.begin(0, 0, 0, false);
        e.setRegister(0, a, az, ax);
        e.setRegister(1, w, wz, wx);
        e.setRegister(2, f, fz, fx);
        return e;
    }

    @Test
    void hexDigitsShowZXAndMixedNibbles() {
        assertEquals("000000000000 000000000000 0000:     CLK    A=zF W=00 F=02 SZAPC=-----",
                TraceFormatter.format(event(0x0F, 0xF0, 0, 0, 0, 0, 0x02, 0, 0)));
        assertEquals("A=xF", register(event(0x0F, 0, 0xF0, 0, 0, 0, 0, 0, 0)));
        assertEquals("A=?F", register(event(0x0F, 0x10, 0x20, 0, 0, 0, 0, 0, 0)), "Z and X in one nibble");
        assertEquals("A=?F", register(event(0x2F, 0x10, 0, 0, 0, 0, 0, 0, 0)), "Z and known bits in one nibble");
        assertEquals("A=xz", register(event(0, 0x0F, 0xF0, 0, 0, 0, 0, 0, 0)));
        assertEquals("A=zz", register(event(0, 0xFF, 0, 0, 0, 0, 0, 0, 0)));
    }

    @Test
    void aPartialTopNibbleCountsOnlyItsBits() {
        // W is 5 bits: the top digit has one bit, so that bit alone being Z makes it 'z'
        assertEquals("W=z3", register(event(0, 0, 0, 0x03, 0x10, 0, 0, 0, 0), 1));
        assertEquals("W=x?", register(event(0, 0, 0, 0x03, 0x04, 0x18, 0, 0, 0), 1));
    }

    @Test
    void flagsShowUnknownBits() {
        // S set, Z unknown, AC floating, P clear, CY set
        String line = TraceFormatter.format(event(0, 0, 0, 0, 0, 0, 0x83, 0x10, 0x40));
        assertTrue(line.endsWith(" SZAPC=Sxz-C"), line);
    }

    @Test
    void theFlagRegisterItselfShowsUnknownNibbles() {
        String line = TraceFormatter.format(event(0, 0, 0, 0, 0, 0, 0x83, 0x10, 0x40));
        assertTrue(line.contains(" F=?3 "), "bit 4 Z and bit 6 X in the high nibble: " + line);
    }

    @Test
    void theRingBufferKeepsTheMasks() {
        TraceRingBuffer ring = new TraceRingBuffer(4);
        TraceEvent e = event(0x0F, 0xF0, 0, 0x03, 0x10, 0, 0x83, 0x10, 0x40);
        ring.onInstruction(e);
        assertEquals(List.of(TraceFormatter.format(e)), ring.lines());
        assertTrue(ring.lines().get(0).contains("A=zF W=z3 F=?3 SZAPC=Sxz-C"), ring.lines().get(0));
    }

    @Test
    void twoValuedLayoutsAreUnaffected() {
        TraceLayout plain = new TraceLayout("cpu", 16, 1, 6, List.of(RegisterSpec.of("A", 8)));
        assertFalse(plain.unknownBits());
        TraceEvent e = new TraceEvent(plain, (pc, bytes, len) -> "NOP");
        e.begin(0, 0, 0, false);
        e.setRegister(0, 0x3E);
        assertThrows(IllegalStateException.class, () -> e.setRegister(0, 0, 1, 0),
                "masks need a layout with unknownBits");
        assertEquals("000000000000 000000000000 0000:     NOP    A=3E", TraceFormatter.format(e));
    }

    @Test
    void hexHelperMatchesTheRules() {
        assertEquals("zF", TraceFormatter.hex(0x0F, 0xF0, 0, 8));
        assertEquals("0x", TraceFormatter.hex(0, 0, 0x0F, 8));
        assertEquals("?0", TraceFormatter.hex(0, 0x10, 0, 8));
        assertEquals("00FF", TraceFormatter.hex(0xFF, 0, 0, 16));
    }

    private static String register(TraceEvent e) {
        return register(e, 0);
    }

    private static String register(TraceEvent e, int index) {
        String name = LAYOUT.registers().get(index).name() + "=";
        String line = TraceFormatter.format(e);
        int at = line.indexOf(" " + name) + 1;
        return line.substring(at, line.indexOf(' ', at));
    }
}
