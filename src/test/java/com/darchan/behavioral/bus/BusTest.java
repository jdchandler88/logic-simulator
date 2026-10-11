package com.darchan.behavioral.bus;

import com.darchan.diag.run.AccessType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BusTest {

    @Test
    void ramReadsWritesAndLoads() {
        Ram ram = new Ram();
        ram.write(0x1234, 0x1FF);
        assertEquals(0xFF, ram.read(0x1234), "stores a byte");
        ram.load(0xFFFE, new byte[]{1, 2});
        assertEquals(2, ram.peek(0xFFFF));
        assertEquals(1, ram.fetch(0xFFFE));
        assertArrayEquals(new byte[]{1, 2}, ram.copy(0xFFFE, 2));
        assertThrows(IllegalArgumentException.class, () -> ram.load(0xFFFF, new byte[2]));
    }

    @Test
    void ramRestoresACapturedRegion() {
        Ram ram = new Ram();
        ram.write(0x100, 0x11);
        com.darchan.diag.MemoryRegion saved = com.darchan.diag.MemoryRegion.capture("ram", ram::peek, 0x100, 2);
        ram.write(0x100, 0x22);
        ram.write(0x101, 0x33);
        ram.restore(saved);
        assertEquals(0x11, ram.read(0x100));
        assertEquals(0x00, ram.read(0x101));
    }

    @Test
    void observedBusesReportEveryAccessButPeek() {
        Ram ram = new Ram();
        ram.write(0x10, 0xAB);
        List<String> log = new ArrayList<>();
        ObservedMemoryBus mem = new ObservedMemoryBus(ram, (t, a, v) -> log.add(t + " " + a + " " + v));
        assertEquals(0xAB, mem.read(0x10));
        assertEquals(0xAB, mem.fetch(0x10));
        mem.write(0x11, 7);
        assertEquals(0xAB, mem.peek(0x10));
        assertEquals(7, ram.read(0x11));
        assertSame(ram, mem.inner());

        ObservedIoBus io = new ObservedIoBus(IoBus.UNCONNECTED, (t, a, v) -> log.add(t + " " + a + " " + v));
        assertEquals(0xFF, io.in(3));
        io.out(4, 5);
        assertEquals(List.of(AccessType.READ + " 16 171", AccessType.FETCH + " 16 171", AccessType.WRITE + " 17 7",
                AccessType.IO_IN + " 3 255", AccessType.IO_OUT + " 4 5"), log);
    }

    @org.junit.jupiter.api.Test
    void ramLoadChecksTheWholeRange() {
        Ram ram = new Ram();
        ram.load(0, new byte[Ram.SIZE]);
        ram.load(Ram.SIZE - 2, new byte[]{1, 2});
        assertEquals(2, ram.peek(Ram.SIZE - 1));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> ram.load(Ram.SIZE - 1, new byte[2]));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> ram.load(-1, new byte[1]));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> ram.load(0, new byte[Ram.SIZE + 1]));
        ram.load(Ram.SIZE, new byte[0]);
    }
}
