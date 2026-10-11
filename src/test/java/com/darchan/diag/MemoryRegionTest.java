package com.darchan.diag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryRegionTest {

    /**
     * Regression (found by the SnapshotJson round-trip property): a region whose last address does not fit in an
     * int used to be constructible. Its {@code end()} overflowed to a negative number, so {@code contains()} was
     * false everywhere, diffs compared nothing, and the JSON it wrote was rejected when loaded.
     */
    @Test
    void aRegionPastTheLargestAddressIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new MemoryRegion("big", Integer.MAX_VALUE, new byte[1]));
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryRegion("big", Integer.MAX_VALUE - 3, new byte[4]));
        assertThrows(IllegalArgumentException.class,
                () -> MemoryRegion.capture("big", a -> 0, Integer.MAX_VALUE - 1, 2));
    }

    @Test
    void aRegionEndingAtTheLargestAddressStillWorksAndRoundTrips() {
        MemoryRegion r = new MemoryRegion("top", Integer.MAX_VALUE - 3, new byte[]{1, 2, 3});
        assertEquals(Integer.MAX_VALUE, r.end());
        assertTrue(r.contains(Integer.MAX_VALUE - 1));
        assertFalse(r.contains(Integer.MAX_VALUE));
        assertEquals(3, r.byteAt(Integer.MAX_VALUE - 1));
        MemoryRegion empty = new MemoryRegion("none", Integer.MAX_VALUE, new byte[0]);
        assertEquals(Integer.MAX_VALUE, empty.end());
        Snapshot s = Snapshot.builder("m").region(r).region(empty).build();
        assertEquals(s, SnapshotJson.fromJson(SnapshotJson.toJson(s)));
    }
}
