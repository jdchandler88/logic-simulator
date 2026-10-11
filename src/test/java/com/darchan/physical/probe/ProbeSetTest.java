package com.darchan.physical.probe;

import com.darchan.diag.Snapshot;
import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.LogicValue;
import com.darchan.physical.signal.SimpleSignal;
import com.darchan.physical.sim.Simulator;
import com.darchan.testutil.Buses;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProbeSetTest {

    private final Simulator sim = new Simulator();
    private final ProbeSet probes = new ProbeSet(sim);

    @Test
    void readsBusesAsUnsignedIntegers() {
        var bus = Buses.simpleBus(sim, 8, 0xA5);
        probes.add("alu.result", bus);
        assertEquals(0xA5, probes.value("alu.result"));
        assertEquals("A5", probes.read("alu.result").toString());
        assertTrue(probes.read("alu.result").isKnown());
        assertEquals("10100101", probes.read("alu.result").bits());
        Buses.drive(bus, 0x3C);
        sim.settle();
        assertEquals(0x3C, probes.value("alu.result"));
        Probe p = probes.get("alu.result");
        assertEquals("result", p.leafName());
        assertEquals(List.of("alu"), p.scopes());
    }

    @Test
    void highImpedanceAndUnknownBitsAreKeptApart() {
        SimpleSignal b0 = new SimpleSignal(sim, true);
        SimpleSignal b1 = new SimpleSignal(sim, LogicValue.Z);
        SimpleSignal b2 = new SimpleSignal(sim, LogicValue.X);
        SimpleSignal b3 = new SimpleSignal(sim, false);
        probes.add("bus", new Bus(b0, b1, b2, b3));
        ProbeValue v = probes.read("bus");
        assertEquals(1, v.value(), "Z and X read as 0");
        assertEquals(0b0010, v.z());
        assertEquals(0b0100, v.x());
        assertFalse(v.isKnown());
        assertEquals("0xz1", v.bits());
        assertEquals("0xz1", v.toString());
        assertEquals(LogicValue.Z, v.bit(1));

        probes.add("floating", new Bus(new SimpleSignal(sim, LogicValue.Z), new SimpleSignal(sim, LogicValue.Z)));
        assertEquals("Z", probes.read("floating").toString());
    }

    @Test
    void snapshotsAddMaskFieldsOnlyWhenNeeded() {
        SimpleSignal b0 = new SimpleSignal(sim, true);
        SimpleSignal b1 = new SimpleSignal(sim, LogicValue.Z);
        probes.add("ok", Buses.simpleBus(sim, 4, 9));
        probes.add("tri", new Bus(b0, b1));
        Snapshot s = probes.snapshot("gate");
        assertEquals(List.of("ok", "tri", "tri.Z"), s.fieldNames());
        assertEquals(9, s.value("ok"));
        assertEquals(1, s.value("tri"));
        assertEquals(2, s.value("tri.Z"));
        assertEquals("ok=9 tri=z1", probes.toString());

        b1.setValue(LogicValue.ONE);
        sim.settle();
        assertEquals(List.of("ok", "tri"), probes.snapshot("gate").fieldNames());
    }

    @Test
    void namesAreValidatedAndUnique() {
        var bus = Buses.simpleBus(sim, 2, 0);
        assertThrows(IllegalArgumentException.class, () -> probes.add("a b", bus));
        assertThrows(IllegalArgumentException.class, () -> probes.add("a=", bus));
        assertThrows(IllegalArgumentException.class, () -> probes.add(".a", bus));
        assertThrows(IllegalArgumentException.class, () -> probes.add("x.Z", bus));
        probes.add("alu.result", bus);
        assertThrows(IllegalArgumentException.class, () -> probes.add("alu.result", bus));
        assertThrows(IllegalArgumentException.class, () -> probes.add("alu", bus), "alu is a scope");
        assertThrows(IllegalArgumentException.class, () -> probes.add("alu.result.hi", bus));
        assertThrows(IllegalArgumentException.class, () -> probes.get("nope"));
        assertThrows(IllegalArgumentException.class, () -> probes.add("wide", Buses.simpleBus(sim, 65, 0)));
    }
}
