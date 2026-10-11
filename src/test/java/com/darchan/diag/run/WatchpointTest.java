package com.darchan.diag.run;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WatchpointTest {

    @Test
    void aRangeIsInclusiveAtBothEnds() {
        Watchpoint w = Watchpoint.memoryRead(0x10, 0x20);
        assertFalse(w.matches(AccessType.READ, 0x0F));
        assertTrue(w.matches(AccessType.READ, 0x10));
        assertTrue(w.matches(AccessType.READ, 0x18));
        assertTrue(w.matches(AccessType.READ, 0x20));
        assertFalse(w.matches(AccessType.READ, 0x21));
        assertFalse(w.matches(AccessType.WRITE, 0x18), "wrong access type");
        assertFalse(w.matches(AccessType.FETCH, 0x18), "reads do not include fetches");
        assertThrows(NullPointerException.class, () -> w.matches(null, 0x18));
    }

    @Test
    void theFactoriesPickTheRightAccessTypes() {
        assertEquals(Set.of(AccessType.READ), Watchpoint.memoryRead(5).types());
        assertEquals(Set.of(AccessType.WRITE), Watchpoint.memoryWrite(5).types());
        assertEquals(Set.of(AccessType.READ, AccessType.WRITE), Watchpoint.memoryAccess(5, 6).types());
        assertEquals(Set.of(AccessType.IO_IN), Watchpoint.ioIn(5).types());
        assertEquals(Set.of(AccessType.IO_OUT), Watchpoint.ioOut(5).types());
        assertEquals(Set.of(AccessType.IO_IN, AccessType.IO_OUT), Watchpoint.io(5).types());
        Watchpoint access = Watchpoint.memoryAccess(0x40, 0x4F);
        assertTrue(access.matches(AccessType.READ, 0x45));
        assertTrue(access.matches(AccessType.WRITE, 0x45));
        assertFalse(access.matches(AccessType.FETCH, 0x45));
        assertFalse(access.matches(AccessType.IO_IN, 0x45));
        Watchpoint io = Watchpoint.io(0x07);
        assertTrue(io.matches(AccessType.IO_IN, 7));
        assertTrue(io.matches(AccessType.IO_OUT, 7));
        assertFalse(io.matches(AccessType.IO_OUT, 8));
        assertFalse(io.matches(AccessType.READ, 7));
        assertEquals(7, io.from());
        assertEquals(7, io.to());
        assertEquals(5, Watchpoint.memoryRead(5).from());
        assertEquals(5, Watchpoint.memoryWrite(5).to());
        assertTrue(Watchpoint.ioIn(3).matches(AccessType.IO_IN, 3));
        assertFalse(Watchpoint.ioIn(3).matches(AccessType.IO_OUT, 3));
        assertTrue(Watchpoint.ioOut(3).matches(AccessType.IO_OUT, 3));
        assertFalse(Watchpoint.ioOut(3).matches(AccessType.IO_IN, 3));
    }

    @Test
    void invalidWatchpointsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Watchpoint(Set.of(), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Watchpoint(EnumSet.noneOf(AccessType.class), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> Watchpoint.memoryRead(-1, 5));
        assertThrows(IllegalArgumentException.class, () -> Watchpoint.memoryRead(5, 4));
        assertEquals(0, Watchpoint.memoryRead(0, 0).from());
        assertEquals(5, Watchpoint.memoryRead(5, 5).to());
    }

    @Test
    void theDescriptionNamesTheTypesAndTheRange() {
        assertEquals("watch [WRITE] 06BF", Watchpoint.memoryWrite(0x6BF).toString());
        assertEquals("watch [READ, WRITE] 0010..001F", Watchpoint.memoryAccess(0x10, 0x1F).toString());
    }

    @Test
    void onlyFetchReadAndWriteAreMemoryAccesses() {
        assertTrue(AccessType.FETCH.isMemory());
        assertTrue(AccessType.READ.isMemory());
        assertTrue(AccessType.WRITE.isMemory());
        assertFalse(AccessType.IO_IN.isMemory());
        assertFalse(AccessType.IO_OUT.isMemory());
    }
}
