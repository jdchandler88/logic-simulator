package com.darchan.physical.comp;

import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.ConstantSignal;
import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.comp.Inverter;
import com.darchan.physical.sim.Simulator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class InverterTest {

    private final Simulator sim = new Simulator();

    @Test
    void initialStateShouldBeFalseIfInputIsTrue() {
        ISignal in = ConstantSignal.ON;
        IBus inputBus = new Bus(in);
        Inverter cut = new Inverter(sim, inputBus);
        assertFalse(cut.getOutputBus().getSignals().get(0).isOn());
    }

    @Test
    void initialStateShouldBeTrueIfInputIsFalse() {
        ISignal in = ConstantSignal.OFF;
        IBus inputBus = new Bus(in);
        Inverter cut = new Inverter(sim, inputBus);
        assertTrue(cut.getOutputBus().getSignals().get(0).isOn());
    }

    @Test
    void inputBusShouldHaveSameSignals() {
        ISignal in = ConstantSignal.ON;
        IBus inputBus = new Bus(in);
        Inverter cut = new Inverter(sim, inputBus);
        assertAll(
                () -> assertEquals(1, cut.getInputBus().getSignals().size()),
                () -> assertTrue(cut.getInputBus().getSignals().get(0).isOn())
        );
    }

    /**
     * We're not going to test the number of input bus signals because this component
     * should only ever use the first input, but should be able to use any width signal bus
     */
    @Test
    void outputBusShouldHaveAppropriateNumberOfSignals() {
        ISignal in = ConstantSignal.ON;
        IBus inputBus = new Bus(in);
        Inverter cut = new Inverter(sim, inputBus);
        assertEquals(1, cut.getOutputBus().getSignals().size());
    }

}
