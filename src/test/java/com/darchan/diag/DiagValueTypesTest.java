package com.darchan.diag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Equality, text and argument checks of the snapshot value types. */
class DiagValueTypesTest {

    private static Snapshot.Builder base() {
        return Snapshot.builder("m").label("l").hex("A", 8, 1).hex("B", 8, 2);
    }

    @Test
    void snapshotsAreEqualOnlyWhenEveryPartIs() {
        Snapshot s = base().region(new MemoryRegion("r", 0, new byte[]{1})).build();
        Snapshot same = base().region(new MemoryRegion("r", 0, new byte[]{1})).build();
        assertEquals(s, same);
        assertEquals(s.hashCode(), same.hashCode());
        assertNotEquals(s, base().build(), "regions differ");
        assertNotEquals(s, Snapshot.builder("other").label("l").hex("A", 8, 1).hex("B", 8, 2)
                .region(new MemoryRegion("r", 0, new byte[]{1})).build(), "machine differs");
        assertNotEquals(s, s.withLabel("other"), "label differs");
        assertNotEquals(s, Snapshot.builder("m").label("l").hex("A", 8, 1).hex("B", 8, 3)
                .region(new MemoryRegion("r", 0, new byte[]{1})).build(), "a field value differs");
        assertNotEquals(s, Snapshot.builder("m").label("l").hex("B", 8, 2).hex("A", 8, 1)
                .region(new MemoryRegion("r", 0, new byte[]{1})).build(), "field order differs");
        assertNotEquals(s, null);
        assertNotEquals(s, "m");
        assertNotEquals(s.hashCode(), s.withLabel("other").hashCode());
        assertNotEquals(0, s.hashCode());
    }

    @Test
    void snapshotTextShowsLabelFieldsAndRegions() {
        Snapshot s = base().region(new MemoryRegion("ram", 0x10, new byte[]{1, 2})).build();
        assertEquals("m \"l\": A=01 B=02 [ram[10+2]]", s.toString());
        assertEquals("m: A=01 B=02", base().label("").build().toString());
        assertEquals("m: ", Snapshot.builder("m").build().toString());
        assertEquals(List.of("A", "B"), s.fieldNames());
        assertEquals(1, s.regions().size());
        assertTrue(s.region("ram").isPresent());
        assertTrue(s.region("none").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> Snapshot.builder(" "));
        assertThrows(NullPointerException.class, () -> Snapshot.builder(null));
        assertThrows(NullPointerException.class, () -> base().label(null));
        assertThrows(NullPointerException.class, () -> s.withLabel(null));
    }

    @Test
    void duplicateRegionsAreRejectedByTheBuilderAndReplacedByWith() {
        MemoryRegion a = new MemoryRegion("r", 0, new byte[]{1});
        MemoryRegion b = new MemoryRegion("r", 5, new byte[]{9, 9});
        assertThrows(IllegalArgumentException.class, () -> base().region(a).region(b));
        Snapshot s = base().region(a).build();
        assertEquals(b, s.withRegion(b).region("r").orElseThrow());
        assertEquals(1, s.withRegion(b).regions().size());
        Snapshot captured = s.withRegion("c", addr -> addr & 0xFF, 2, 3);
        assertArrayEquals(new byte[]{2, 3, 4}, captured.region("c").orElseThrow().bytes());
        assertEquals(2, captured.regions().size());
        Snapshot viaBuilder = base().region("c", addr -> addr & 0xFF, 2, 3).build();
        assertEquals(captured.region("c"), viaBuilder.region("c"));
    }

    @Test
    void regionsAreValidatedAndComparedByContent() {
        assertThrows(IllegalArgumentException.class, () -> new MemoryRegion(" ", 0, new byte[0]));
        assertThrows(NullPointerException.class, () -> new MemoryRegion(null, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new MemoryRegion("r", -1, new byte[0]));
        new MemoryRegion("r", 0, new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> MemoryRegion.capture("r", a -> 0, 0, -1));
        assertEquals(0, MemoryRegion.capture("r", a -> 0, 0, 0).length());
        MemoryRegion r = new MemoryRegion("r", 4, new byte[]{1, 2, 3});
        assertEquals(new MemoryRegion("r", 4, new byte[]{1, 2, 3}), r);
        assertEquals(new MemoryRegion("r", 4, new byte[]{1, 2, 3}).hashCode(), r.hashCode());
        assertNotEquals(r, new MemoryRegion("s", 4, new byte[]{1, 2, 3}));
        assertNotEquals(r, new MemoryRegion("r", 5, new byte[]{1, 2, 3}));
        assertNotEquals(r, new MemoryRegion("r", 4, new byte[]{1, 2, 4}));
        assertNotEquals(r, new MemoryRegion("r", 4, new byte[]{1, 2}));
        assertNotEquals(r, null);
        assertNotEquals(r, "r");
        assertNotEquals(0, r.hashCode());
        assertNotEquals(r.hashCode(), new MemoryRegion("r", 5, new byte[]{1, 2, 3}).hashCode());
        assertEquals(7, r.end());
        assertTrue(r.contains(4));
        assertTrue(r.contains(6));
        assertFalse(r.contains(3));
        assertFalse(r.contains(7));
        assertEquals(1, r.byteAt(4));
        assertEquals(3, r.byteAt(6));
        assertThrows(IndexOutOfBoundsException.class, () -> r.byteAt(3));
        assertThrows(IndexOutOfBoundsException.class, () -> r.byteAt(7));
        assertEquals("r[4+3]", r.toString());
        assertEquals(0xFF, new MemoryRegion("r", 0, new byte[]{(byte) 0xFF}).byteAt(0), "bytes are unsigned");
        assertEquals(0xFF, new MemoryRegion("r", 0, new byte[]{(byte) 0xFF}).view().peek(0));
        assertEquals(0, r.view().peek(100), "outside the region reads as 0");
        r.bytes()[0] = 99;
        assertEquals(1, r.byteAt(4), "bytes() returns a copy");
    }

    @Test
    void captureStopsAtTheRequestedLength() {
        int[] reads = {0};
        MemoryRegion r = MemoryRegion.capture("r", a -> {
            reads[0]++;
            return a;
        }, 0xFF, 3);
        assertEquals(3, reads[0]);
        assertArrayEquals(new byte[]{(byte) 0xFF, 0, 1}, r.bytes(), "peeked values are truncated to bytes");
    }

    @Test
    void diffNamesFieldsPadsThemToAColumnAndTextsAreNonEmpty() {
        Snapshot a = Snapshot.builder("m").label("e").hex("TWELVECHARSX", 8, 1).hex("SHORT", 8, 1).build();
        Snapshot b = Snapshot.builder("m").label("x").hex("TWELVECHARSX", 8, 2).hex("SHORT", 8, 2).build();
        SnapshotDiff d = a.diff(b);
        String report = d.report();
        assertTrue(report.contains("\n  TWELVECHARSX expected=01  actual=02"), "a 12-character name keeps one space: " + report);
        assertTrue(report.contains("\n  SHORT       expected=01  actual=02"), report);
        assertEquals(report, d.toString());
        assertEquals("TWELVECHARSX expected=01  actual=02", d.fieldDifferences().get(0).toString());
        assertEquals("TWELVECHARSX expected=01  actual=<missing>",
                new SnapshotDiff.FieldDifference("TWELVECHARSX", java.util.Optional.of(Field.hex("A", 8, 1)),
                        java.util.Optional.empty()).toString());
        assertTrue(report.startsWith("2 field(s) and 0 memory byte(s) differ (expected \"e\" vs actual \"x\")"), report);
        assertEquals("snapshots are identical (expected \"e\" vs actual \"e\")", a.diff(a).report());
        // a field on one side only is a difference
        Snapshot c = Snapshot.builder("m").hex("TWELVECHARSX", 8, 1).build();
        assertEquals(1, a.diff(c).fieldDifferences().size());
        assertEquals("SHORT", a.diff(c).fieldDifferences().get(0).name());
        assertEquals(1, c.diff(a).fieldDifferences().size());
        // machines differing is a region-level problem
        Snapshot other = Snapshot.builder("n").hex("TWELVECHARSX", 8, 1).build();
        assertEquals(List.of("machine: expected=m  actual=n"), c.diff(other).regionProblems());
        assertFalse(c.diff(other).isEmpty());
        // ignoring fields
        assertTrue(a.diff(b, Set.of("TWELVECHARSX", "SHORT")).isEmpty());
        assertEquals(1, a.diff(b, Set.of("SHORT")).fieldDifferences().size());
    }

    @Test
    void regionDifferencesCoverMissingBoundsAndOverlap() {
        Snapshot withRegion = base().region(new MemoryRegion("r", 4, new byte[]{1, 2, 3, 4})).build();
        Snapshot none = base().build();
        assertEquals(List.of("region r[4+4] missing from actual"), withRegion.diff(none).regionProblems());
        assertEquals(List.of("region r[4+4] missing from expected"), none.diff(withRegion).regionProblems());
        // different bounds: compared over the overlap only
        Snapshot shifted = base().region(new MemoryRegion("r", 6, new byte[]{3, 9, 5})).build();
        SnapshotDiff d = withRegion.diff(shifted);
        assertEquals(1, d.regionProblems().size());
        assertTrue(d.regionProblems().get(0).contains("expected r[4+4]  actual r[6+3] (comparing the overlap)"),
                d.regionProblems().toString());
        assertEquals(1, d.byteDifferenceCount());
        assertEquals(7, d.byteDifferences().get(0).address());
        assertEquals(4, d.byteDifferences().get(0).expected());
        assertEquals(9, d.byteDifferences().get(0).actual());
        assertTrue(d.report().contains("r[0007]     expected=04  actual=09"), d.report());
        // same start, different length
        Snapshot longer = base().region(new MemoryRegion("r", 4, new byte[]{1, 2, 3, 4, 5})).build();
        assertEquals(1, withRegion.diff(longer).regionProblems().size());
        assertEquals(0, withRegion.diff(longer).byteDifferenceCount());
        // same length, different start only
        Snapshot moved = base().region(new MemoryRegion("r", 5, new byte[]{1, 2, 3, 4})).build();
        assertEquals(1, withRegion.diff(moved).regionProblems().size());
    }

    @Test
    void fieldFormattingPadsToTheWidth() {
        assertEquals("00", Field.hex("A", 8, 0).formattedValue());
        assertEquals("000", Field.hex("A", 9, 0).formattedValue());
        assertEquals("0", Field.hex("A", 1, 0).formattedValue());
        assertEquals("1FF", Field.hex("A", 9, 0x1FF).formattedValue());
        assertEquals("7F", Field.hex("A", 7, 0x7F).formattedValue());
        assertEquals("18446744073709551615", Field.dec("D", -1L).formattedValue(), "decimal is unsigned");
        assertEquals("0", Field.bool("B", false).formattedValue());
        assertTrue(Field.bool("B", true).asBoolean());
        assertFalse(Field.bool("B", false).asBoolean());
        for (String bad : new String[]{"", " ", " A", "A ", "A B", "A=B", "\tA", "A\n"}) {
            assertThrows(IllegalArgumentException.class, () -> Field.hex(bad, 8, 0), "'" + bad + "'");
        }
        assertThrows(NullPointerException.class, () -> new Field(null, 8, 0, Field.Format.HEX));
        assertThrows(NullPointerException.class, () -> new Field("A", 8, 0, null));
        Field.hex("A", 64, -1L);
        Field.hex("A", 1, 1);
        assertThrows(IllegalArgumentException.class, () -> Field.hex("A", 1, 2));
        assertThrows(IllegalArgumentException.class, () -> Field.hex("A", 63, -1L));
    }

    @Test
    void hexDumpRowsAreSixteenBytesWideWithAWiderAddressPastTheFirst64K() {
        String dump = HexDump.format(a -> a & 0xFF, 0xFFF8, 24);
        String[] lines = dump.split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("0000FFF8: F8 F9"), "the range crosses 0x10000, so addresses get 8 digits: " + dump);
        assertTrue(lines[1].startsWith("00010008: 08 09"), dump);
        String small = HexDump.format(a -> 0x41, 0, 0x10000);
        assertTrue(small.startsWith("0000: 41"), "ends exactly at 0x10000: four digits");
        assertTrue(small.endsWith("|AAAAAAAAAAAAAAAA|"));
        assertEquals(4096, small.split("\n").length);
        assertEquals("", HexDump.format(a -> 0, 5, 0));
        assertEquals(RegisterDump.format(base().build()), "A=01 B=02");
    }
}
