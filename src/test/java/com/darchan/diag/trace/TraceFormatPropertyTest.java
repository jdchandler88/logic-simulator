package com.darchan.diag.trace;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trace lines for arbitrary layouts, values and Z/X masks: formatting never throws, lines keep the documented shape
 * (no newline, no trailing space, fixed-width counters and hex columns), and {@link TraceLine} reads back what
 * {@link TraceFormatter} wrote.
 */
class TraceFormatPropertyTest {

    /** a random layout and a random event for it, built from one seed so a failing case is reproducible */
    record Case(TraceLayout layout, TraceEvent event, long cycle, long instruction, int pc, boolean interrupt,
                int[] values, int[] z, int[] x) {
    }

    @Provide
    Arbitrary<Case> cases() {
        return Arbitraries.longs().map(TraceFormatPropertyTest::build);
    }

    private static Case build(long seed) {
        Random r = new Random(seed);
        int regs = r.nextInt(7);
        List<RegisterSpec> specs = new ArrayList<>();
        for (int i = 0; i < regs; i++) {
            int width = 1 + r.nextInt(32);
            String name = "R" + i + (r.nextBoolean() ? "X" : "");
            if (r.nextInt(3) == 0) {
                StringBuilder bits = new StringBuilder();
                for (int b = 0; b < width; b++) {
                    bits.append(r.nextInt(3) == 0 ? '.' : (char) ('A' + i)); // flag letters unique to the register
                }
                bits.setCharAt(r.nextInt(width), (char) ('A' + i)); // at least one named flag
                specs.add(RegisterSpec.flags(name, bits.toString()));
            } else {
                specs.add(RegisterSpec.of(name, width));
            }
        }
        boolean unknown = r.nextBoolean();
        TraceLayout layout = new TraceLayout("cpu", 1 + r.nextInt(32), 1 + r.nextInt(4), r.nextInt(30), specs, unknown);
        TraceEvent e = new TraceEvent(layout, (pc, bytes, length) -> "OP" + length + (length > 0 ? "," + bytes[0] : ""));
        long cycle = r.nextInt(4) == 0 ? Math.abs(r.nextLong() % 1_000_000_000_000_000L) : r.nextInt(100_000);
        long instruction = r.nextInt(100_000);
        int pc = r.nextInt();
        boolean interrupt = r.nextBoolean();
        e.begin(cycle, instruction, pc, interrupt);
        e.setBytes(r.nextInt(), r.nextInt(layout.maxInstructionLength() + 1));
        int[] values = new int[regs];
        int[] z = new int[regs];
        int[] x = new int[regs];
        for (int i = 0; i < regs; i++) {
            int mask = mask(specs.get(i).width());
            values[i] = r.nextInt() & mask;
            if (unknown && r.nextBoolean()) {
                z[i] = r.nextInt() & mask & (r.nextBoolean() ? r.nextInt() : -1);
                x[i] = r.nextInt() & mask & ~z[i] & (r.nextBoolean() ? r.nextInt() : -1);
                values[i] &= ~(z[i] | x[i]);
                e.setRegister(i, values[i], z[i], x[i]);
            } else {
                e.setRegister(i, values[i]);
            }
        }
        return new Case(layout, e, cycle, instruction, pc, interrupt, values, z, x);
    }

    private static int mask(int width) {
        return width == 32 ? -1 : (1 << width) - 1;
    }

    @Property
    void aLineNeverThrowsAndKeepsItsShape(@ForAll("cases") Case c) {
        String line = TraceFormatter.format(c.event());
        assertFalse(line.contains("\n") || line.contains("\r"), "one line");
        assertFalse(line.endsWith(" "), "no trailing space: '" + line + "'");
        assertEquals(line, c.event().toString());
        StringBuilder appended = new StringBuilder("prefix");
        TraceFormatter.append(appended, c.event());
        assertEquals("prefix" + line, appended.toString(), "append writes the same line");
        String counters = String.format("%012d %012d ", c.cycle(), c.instruction());
        assertTrue(line.startsWith(counters), "decimal counters zero-padded to 12 digits: " + line);
        int pcDigits = (c.layout().pcWidth() + 3) / 4;
        assertTrue(line.substring(counters.length()).startsWith(hexDigits(c.pc(), pcDigits) + ": "), line);
        if (c.interrupt()) {
            assertTrue(line.contains("INT "), line);
        }
        assertEquals(c.event().copy(), c.event());
        assertEquals(line, TraceFormatter.format(c.event().copy()));
    }

    private static String hexDigits(int value, int digits) {
        StringBuilder sb = new StringBuilder();
        for (int d = digits - 1; d >= 0; d--) {
            sb.append("0123456789ABCDEF".charAt(value >>> 4 * d & 0xF));
        }
        return sb.toString();
    }

    @Property
    void theParserReadsBackTheRegisterColumns(@ForAll("cases") Case c) {
        TraceLine parsed = TraceLine.parse(TraceFormatter.format(c.event()));
        assertTrue(parsed.isTrace(), parsed.line());
        assertEquals(String.format("%012d", c.cycle()), parsed.text("cycle").orElseThrow());
        assertEquals(String.format("%012d", c.instruction()), parsed.text("instruction").orElseThrow());
        List<RegisterSpec> specs = c.layout().registers();
        for (int i = 0; i < specs.size(); i++) {
            RegisterSpec spec = specs.get(i);
            String text = parsed.text(spec.name()).orElseThrow(() -> new AssertionError("no column " + spec.name()));
            assertEquals(TraceFormatter.hex(c.values()[i] & 0xFFFFFFFFL, c.z()[i] & 0xFFFFFFFFL,
                    c.x()[i] & 0xFFFFFFFFL, spec.width()), text, spec.name());
            assertEquals((spec.width() + 3) / 4, text.length());
            if (spec.flagBits() != null) {
                String flags = parsed.text(spec.flagNames()).orElseThrow(
                        () -> new AssertionError("no flag column " + spec.flagNames()));
                assertEquals(spec.flagNames().length(), flags.length());
            }
        }
    }

    @Property
    void knownRegistersPrintAsPlainUppercaseHex(@ForAll("cases") Case c) {
        if (c.layout().unknownBits()) {
            return;
        }
        String line = TraceFormatter.format(c.event());
        TraceLine parsed = TraceLine.parse(line);
        for (int i = 0; i < c.layout().registers().size(); i++) {
            RegisterSpec spec = c.layout().registers().get(i);
            String text = parsed.text(spec.name()).orElseThrow();
            assertTrue(text.matches("[0-9A-F]{" + (spec.width() + 3) / 4 + "}"), text);
            assertEquals(c.values()[i] & 0xFFFFFFFFL, Long.parseLong(text, 16));
        }
    }

    // ---- unknown-bit digits ---------------------------------------------------------------------------------------

    @Property
    void unknownBitDigitsFollowTheNibbleRules(@ForAll long value, @ForAll long zRaw, @ForAll long xRaw,
                                              @ForAll @IntRange(min = 1, max = 64) int width) {
        long m = width == 64 ? -1L : (1L << width) - 1;
        long z = zRaw & m;
        long x = xRaw & m & ~z;
        long v = value & m & ~(z | x);
        String text = TraceFormatter.hex(v, z, x, width);
        assertEquals((width + 3) / 4, text.length());
        for (int d = 0; d < text.length(); d++) {
            int digit = text.length() - 1 - d; // digit index from the least significant end
            int bits = Math.min(4, width - 4 * digit);
            long nibble = (1L << bits) - 1;
            long zn = z >>> 4 * digit & nibble;
            long xn = x >>> 4 * digit & nibble;
            char ch = text.charAt(d);
            if (zn == nibble) {
                assertEquals('z', ch);
            } else if (xn == nibble) {
                assertEquals('x', ch);
            } else if ((zn | xn) != 0) {
                assertEquals('?', ch);
            } else {
                assertEquals("0123456789ABCDEF".charAt((int) (v >>> 4 * digit & 0xF)), ch);
            }
        }
    }

    @Property
    void theHeaderNamesEveryColumn(@ForAll("cases") Case c) {
        String header = TraceFormatter.header(c.layout());
        assertTrue(header.startsWith("# cpu trace:"));
        for (RegisterSpec r : c.layout().registers()) {
            assertTrue(header.contains(" " + r.name()), header);
        }
        assertEquals(c.layout().registers().size(), c.event().registerCount());
        assertTrue(c.event().length() <= c.layout().maxInstructionLength());
    }
}
