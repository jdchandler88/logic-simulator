package com.darchan.elements.impl.comp;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ISignal;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.Clock;
import com.darchan.elements.impl.SimpleSignal;
import com.darchan.sim.Simulator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlipFlopTest {

    private final Simulator sim = new Simulator();


    @ParameterizedTest
    @CsvSource(value={"true", "false"})
    void outputShouldBeSuppliedInitialValue(boolean initialValue) {
        //inputs for flipflop
        Clock clk = new Clock(sim, false);
        ISignal inputSignal = new SimpleSignal(sim, false);
        IBus inputBus = new Bus(inputSignal);

        //instantiate class under test
        FlipFlop ff = new FlipFlop(sim, clk, inputBus, initialValue);

        //make sure initial state is maintained by ff
        assertEquals(initialValue, ff.getOutputBus().getSignals().get(0).isOn());
    }

    @ParameterizedTest
    //initialValue, newValue
    @CsvSource(value={"false, false", "false, true", "true, false", "true, true"})
    void outputShouldChangeWhenClockChangesFromLowToHigh(boolean initialValue, boolean newValue) {
        //input signals for flipflop
        Clock clk = new Clock(sim, false);
        SimpleSignal inputSignal = new SimpleSignal(sim, newValue);
        Bus inputBus = new Bus(inputSignal);

        //create clock with input signals and initial value
        FlipFlop ff = new FlipFlop(sim, clk, inputBus, initialValue);

        //tick clock to trigger change (low to high)
        clk.tick();

        //assert that clock has the new value
        assertEquals(newValue, ff.getOutputBus().getSignals().get(0).isOn());
    }

    @ParameterizedTest
    //initialValue, newValue
    @CsvSource(value={"false, false", "false, true", "true, false", "true, true"})
    void outputShouldNOTChangeWhenClockChangesFromHighToLow(boolean initialValue, boolean newValue) {
        //input signals for flipflop
        Clock clk = new Clock(sim, true);
        SimpleSignal inputSignal = new SimpleSignal(sim, newValue);
        Bus inputBus = new Bus(inputSignal);

        //create clock with input signals and initial value
        FlipFlop ff = new FlipFlop(sim, clk, inputBus, initialValue);

        //tick clock to trigger change (low to high)
        clk.tick();

        //assert that clock has the new value
        assertEquals(initialValue, ff.getOutputBus().getSignals().get(0).isOn());
    }

}
