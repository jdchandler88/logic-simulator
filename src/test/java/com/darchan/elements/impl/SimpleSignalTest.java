package com.darchan.elements.impl;

import com.darchan.sim.Simulator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimpleSignalTest {

    private final Simulator sim = new Simulator();

    @ParameterizedTest
    @CsvSource(value = {"true", "false"})
    void shouldHaveInitialState(boolean initialValue) {
        SimpleSignal ts = new SimpleSignal(sim, initialValue);
        assertEquals(initialValue, ts.isOn());
    }

    @ParameterizedTest
    @CsvSource(value = {"true", "false"})
    void shouldHaveSetState(boolean targetState) {
        SimpleSignal ts = new SimpleSignal(sim, false);
        ts.setOn(targetState);
        sim.settle();
        assertEquals(targetState, ts.isOn());
    }

}
