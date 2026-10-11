package com.darchan.elements.impl;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ISignalChangeListener;
import com.darchan.elements.iface.LogicValue;
import com.darchan.sim.Simulator;
import com.darchan.testutil.Buses;
import com.darchan.validation.UnexpectedBusWidthException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WireTest {

    private final Simulator sim = new Simulator();

    @Test
    void unconnectedWireReadsLow() {
        assertFalse(new Wire(sim).isOn());
    }

    @Test
    void unconnectedWireFloats() {
        assertEquals(LogicValue.Z, new Wire(sim).getValue());
    }

    @Test
    void wireTakesItsDriversValueWhenConnected() {
        Wire wire = new Wire(sim);
        wire.connect(ConstantSignal.ON);
        sim.settle();
        assertTrue(wire.isOn());
    }

    @Test
    void wireFollowsItsDriver() {
        SimpleSignal driver = new SimpleSignal(sim, false);
        Wire wire = new Wire(sim);
        wire.connect(driver);
        sim.settle();
        assertFalse(wire.isOn());
        assertEquals(LogicValue.ZERO, wire.getValue());

        driver.setOn(true);
        sim.settle();
        assertTrue(wire.isOn());

        driver.setOn(false);
        sim.settle();
        assertFalse(wire.isOn());
    }

    @Test
    void multipleDriversAreResolved() {
        SimpleSignal a = new SimpleSignal(sim, LogicValue.Z);
        SimpleSignal b = new SimpleSignal(sim, LogicValue.Z);
        Wire wire = new Wire(sim);
        wire.connect(a);
        wire.connect(b);
        sim.settle();
        assertEquals(LogicValue.Z, wire.getValue(), "no driver");

        a.setValue(LogicValue.ONE);
        sim.settle();
        assertEquals(LogicValue.ONE, wire.getValue(), "one driver");

        b.setValue(LogicValue.ONE);
        sim.settle();
        assertEquals(LogicValue.ONE, wire.getValue(), "two agreeing drivers");

        b.setValue(LogicValue.ZERO);
        sim.settle();
        assertEquals(LogicValue.X, wire.getValue(), "two conflicting drivers");
        assertFalse(wire.isOn());

        a.setValue(LogicValue.Z);
        sim.settle();
        assertEquals(LogicValue.ZERO, wire.getValue(), "conflict resolved");
    }

    @Test
    void listenersHearTransitionsBetweenZAndLow() {
        SimpleSignal driver = new SimpleSignal(sim, LogicValue.Z);
        Wire wire = new Wire(sim);
        wire.connect(driver);
        sim.settle();
        List<String> seen = new ArrayList<>();
        wire.addSignalChangeListener(new ISignalChangeListener() {
            @Override
            public void signalChanged(boolean oldValue, boolean newValue) {
                fail("valueChanged is overridden");
            }

            @Override
            public void valueChanged(LogicValue oldValue, LogicValue newValue) {
                seen.add(oldValue + ">" + newValue);
            }
        });
        List<String> legacy = new ArrayList<>();
        wire.addSignalChangeListener((o, n) -> legacy.add(o + ">" + n));

        driver.setValue(LogicValue.ZERO);
        sim.settle();
        driver.setValue(LogicValue.Z);
        sim.settle();
        assertAll(
                () -> assertEquals(List.of("Z>0", "0>Z"), seen),
                //boolean listeners are still told, with Z read as false
                () -> assertEquals(List.of("false>false", "false>false"), legacy)
        );
    }

    @Test
    void busHelpersConnectWiresBitwise() {
        IBus wires = Wire.createBus(sim, 4);
        IBus drivers = Buses.simpleBus(sim, 4, 0b1010);
        Wire.connect(wires, drivers);
        sim.settle();
        assertEquals(0b1010, Buses.toInt(wires));
        assertThrows(UnexpectedBusWidthException.class, () -> Wire.connect(wires, Buses.simpleBus(sim, 3, 0)));
    }
}
