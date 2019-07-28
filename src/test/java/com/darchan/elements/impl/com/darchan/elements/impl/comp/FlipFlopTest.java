package com.darchan.elements.impl.com.darchan.elements.impl.comp;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ISignal;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.Clock;
import com.darchan.elements.impl.SimpleSignal;
import com.darchan.elements.impl.comp.FlipFlop;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlipFlopTest {


    @ParameterizedTest
    @CsvSource(value={"true", "false"})
    void outputShouldBeSuppliedInitialValue(boolean initialValue) {
        //inputs for flipflop
        Clock clk = new Clock(false);
        ISignal inputSignal = new SimpleSignal(false);
        IBus inputBus = new Bus(inputSignal);

        //instantiate class under test
        FlipFlop ff = new FlipFlop(clk, inputBus, initialValue);

        //make sure initial state is maintained by ff
        assertEquals(initialValue, ff.getOutputBus().getSignals().get(0).isOn());
    }

    @ParameterizedTest
    //initialValue, newValue
    @CsvSource(value={"false, false", "false, true", "true, false", "true, true"})
    void outputShouldChangeWhenClockChangesFromLowToHigh(boolean initialValue, boolean newValue) {
        //input signals for flipflop
        Clock clk = new Clock(false);
        SimpleSignal inputSignal = new SimpleSignal(newValue);
        Bus inputBus = new Bus(inputSignal);

        //create clock with input signals and initial value
        FlipFlop ff = new FlipFlop(clk, inputBus, initialValue);

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
        Clock clk = new Clock(true);
        SimpleSignal inputSignal = new SimpleSignal(newValue);
        Bus inputBus = new Bus(inputSignal);

        //create clock with input signals and initial value
        FlipFlop ff = new FlipFlop(clk, inputBus, initialValue);

        //tick clock to trigger change (low to high)
        clk.tick();

        //assert that clock has the new value
        assertEquals(initialValue, ff.getOutputBus().getSignals().get(0).isOn());
    }

}
