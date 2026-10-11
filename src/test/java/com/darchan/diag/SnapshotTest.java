package com.darchan.diag;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotTest {

    static Snapshot sample(int pc, int a) {
        return Snapshot.builder("test-cpu")
                .label("pc=" + pc)
                .hex("PC", 16, pc)
                .hex("A", 8, a)
                .bool("INTE", true)
                .dec("CYCLES", 4924)
                .build();
    }

    @Test
    void fieldFormatting() {
        assertEquals("0100", Field.hex("PC", 16, 0x100).formattedValue());
        assertEquals("3E", Field.hex("A", 8, 0x3E).formattedValue());
        assertEquals("1F", Field.hex("X", 5, 0x1F).formattedValue());
        assertEquals("4924", Field.dec("CYCLES", 4924).formattedValue());
        assertEquals("1", Field.bool("INTE", true).formattedValue());
        assertEquals("PC=0100", Field.hex("PC", 16, 0x100).toString());
        assertEquals("FFFFFFFFFFFFFFFF", Field.hex("W", 64, -1L).formattedValue());
    }

    @Test
    void fieldValidation() {
        assertThrows(IllegalArgumentException.class, () -> Field.hex("A", 8, 0x100));
        assertThrows(IllegalArgumentException.class, () -> Field.hex("A", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Field.hex("A", 65, 0));
        assertThrows(IllegalArgumentException.class, () -> Field.hex("two words", 8, 0));
        assertThrows(IllegalArgumentException.class, () -> new Field("B", 2, 1, Field.Format.BOOL));
    }

    @Test
    void builderKeepsOrderAndRejectsDuplicates() {
        Snapshot s = sample(0x100, 0x3E);
        assertEquals("test-cpu", s.machine());
        assertEquals(java.util.List.of("PC", "A", "INTE", "CYCLES"), s.fieldNames());
        assertEquals(0x3E, s.value("A"));
        assertTrue(s.field("B").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> s.value("B"));
        assertThrows(IllegalArgumentException.class, () -> Snapshot.builder("x").hex("A", 8, 0).hex("A", 8, 1));
        assertEquals("test-cpu \"pc=256\": PC=0100 A=3E INTE=1 CYCLES=4924", s.toString());
    }

    @Test
    void regionsAreCopied() {
        byte[] data = {1, 2, 3};
        MemoryRegion r = new MemoryRegion("ram", 0x10, data);
        data[0] = 99;
        assertEquals(1, r.byteAt(0x10));
        assertEquals(3, r.byteAt(0x12));
        assertThrows(IndexOutOfBoundsException.class, () -> r.byteAt(0x13));
        Snapshot s = sample(0, 0).withRegion(r);
        assertEquals(r, s.region("ram").orElseThrow());
        assertEquals(s, sample(0, 0).withRegion(new MemoryRegion("ram", 0x10, new byte[]{1, 2, 3})));
    }

    @Test
    void captureFromMemoryView() {
        MemoryView mem = address -> address & 0xFF;
        MemoryRegion r = MemoryRegion.capture("ram", mem, 0x1FE, 4);
        assertArrayEquals(new byte[]{(byte) 0xFE, (byte) 0xFF, 0, 1}, r.bytes());
    }

    @Test
    void identicalSnapshotsHaveAnEmptyDiff() {
        SnapshotDiff d = sample(0x100, 1).diff(sample(0x100, 1));
        assertTrue(d.isEmpty());
        assertTrue(d.report().startsWith("snapshots are identical"));
    }

    @Test
    void diffReportsFieldsAndBytes() {
        Snapshot expected = sample(0x100, 0x3E).withLabel("golden")
                .withRegion(new MemoryRegion("ram", 0x2000, new byte[]{0, 1, 2}));
        Snapshot actual = Snapshot.builder("test-cpu").label("run")
                .hex("PC", 16, 0x103).hex("A", 8, 0x3E).bool("INTE", true).dec("CYCLES", 4924).hex("B", 8, 7)
                .region(new MemoryRegion("ram", 0x2000, new byte[]{0, 1, (byte) 0xFF}))
                .build();
        SnapshotDiff d = expected.diff(actual);
        assertFalse(d.isEmpty());
        assertEquals(2, d.fieldDifferences().size());
        assertEquals(1, d.byteDifferenceCount());
        assertEquals(new SnapshotDiff.ByteDifference("ram", 0x2002, 2, 0xFF), d.byteDifferences().get(0));
        assertEquals("""
                2 field(s) and 1 memory byte(s) differ (expected "golden" vs actual "run")
                  PC          expected=0100  actual=0103
                  B           expected=<missing>  actual=07
                  ram[2002]   expected=02  actual=FF""", d.report());
    }

    @Test
    void diffCanIgnoreFields() {
        Snapshot a = sample(0x100, 1);
        Snapshot b = Snapshot.builder("test-cpu").label("pc=256").hex("PC", 16, 0x100).hex("A", 8, 1)
                .bool("INTE", true).dec("CYCLES", 9999).build();
        assertFalse(a.diff(b).isEmpty());
        assertTrue(a.diff(b, Set.of("CYCLES")).isEmpty());
    }

    @Test
    void diffReportsRegionProblemsAndTruncatesBytes() {
        Snapshot a = sample(0, 0).withRegion(new MemoryRegion("ram", 0, new byte[200]));
        byte[] ones = new byte[100];
        java.util.Arrays.fill(ones, (byte) 1);
        Snapshot b = sample(0, 0).withRegion(new MemoryRegion("ram", 0, ones)).withRegion(new MemoryRegion("vram", 0, new byte[1]));
        SnapshotDiff d = a.diff(b);
        assertEquals(100, d.byteDifferenceCount());
        assertEquals(SnapshotDiff.MAX_LISTED_BYTES, d.byteDifferences().size());
        assertEquals(2, d.regionProblems().size());
        assertTrue(d.report().contains("... and 36 more byte(s)"), d.report());
    }

    @Test
    void registerDump() {
        assertEquals("PC=0100 A=3E INTE=1 CYCLES=4924", RegisterDump.format(sample(0x100, 0x3E)));
    }
}
