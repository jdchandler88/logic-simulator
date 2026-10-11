package com.darchan.elements.impl;

import com.darchan.sim.Simulator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WireTest {

    private final Simulator sim = new Simulator();

    @Test
    void unconnectedWireReadsLow() {
        assertFalse(new Wire(sim).isOn());
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

        driver.setOn(true);
        sim.settle();
        assertTrue(wire.isOn());

        driver.setOn(false);
        sim.settle();
        assertFalse(wire.isOn());
    }
}
