package com.darchan.physical.signal;

import com.darchan.physical.sim.Simulator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ClockTest {

    private final Simulator sim = new Simulator();

    @Test
    public void clockShouldHaveInitialState() {
        assertAll(
            () -> assertTrue(new Clock(sim, true).isOn()),
            () -> assertFalse(new Clock(sim, false).isOn())
        );
    }

    @Test
    public void clockShouldSwitchStateWhenTickCalled() {
        Clock clock = new Clock(sim, false);
        assertAll(
                () -> {
                    clock.tick();
                    assertTrue(clock.isOn());
                },
                () -> {
                    clock.tick();
                    assertFalse(clock.isOn());
                }
        );
    }

    @Test
    public void tickShouldSettleTheCircuit() {
        Clock clock = new Clock(sim, false);
        LogicSignal inverted = new LogicSignal(sim, new Bus(clock), new com.darchan.physical.logic.InverterLogicUnit());
        clock.tick();
        assertAll(
                () -> assertFalse(inverted.isOn()),
                () -> assertTrue(sim.isQuiescent())
        );
    }

}
