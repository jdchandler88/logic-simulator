package com.darchan.physical.comp;

import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.LogicValue;
import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.Clock;
import com.darchan.physical.signal.ConstantSignal;
import com.darchan.physical.signal.SimpleSignal;
import com.darchan.physical.sim.Simulator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** A rising edge is any transition to 1 from a value that is not 1 (so Z->1 counts, 1->Z->1 too). */
    @org.junit.jupiter.api.Test
    void transitionFromFloatingToHighIsARisingEdge() {
        SimpleSignal clk = new SimpleSignal(sim, LogicValue.Z);
        FlipFlop ff = new FlipFlop(sim, clk, new Bus(ConstantSignal.ON), false);
        clk.setOn(true);
        sim.settle();
        assertTrue(ff.getOutputBus().getSignal(0).isOn());
    }

    @ParameterizedTest
    @CsvSource(value = {"X", "Z"})
    void flipFlopCapturesUnknownData(LogicValue data) {
        Clock clk = new Clock(sim, false);
        SimpleSignal in = new SimpleSignal(sim, data);
        FlipFlop ff = new FlipFlop(sim, clk, new Bus(in), true);
        clk.tick();
        assertEquals(data, ff.getOutputBus().getSignal(0).getValue());
    }

    @ParameterizedTest
    //initialValue, data
    @CsvSource(value={"false, false", "false, true", "true, false", "true, true"})
    void flipFlopWithWriteEnableLoadsOnlyWhenEnabled(boolean initialValue, boolean data) {
        Clock clk = new Clock(sim, false);
        SimpleSignal in = new SimpleSignal(sim, data);
        SimpleSignal enable = new SimpleSignal(sim, false);
        FlipFlop ff = new FlipFlop(sim, clk, new Bus(in), enable, initialValue);
        sim.settle();

        //disabled: rising edge holds
        clk.tick();
        assertEquals(initialValue, ff.getOutputBus().getSignal(0).isOn());
        clk.tick();

        //toggling enable without a clock edge does not load
        enable.setOn(true);
        sim.settle();
        assertEquals(initialValue, ff.getOutputBus().getSignal(0).isOn());

        //enabled: rising edge loads
        clk.tick();
        assertEquals(data, ff.getOutputBus().getSignal(0).isOn());
        clk.tick();

        //disabled again: holds while data changes and clock runs
        enable.setOn(false);
        in.setOn(!data);
        sim.settle();
        clk.tick();
        assertEquals(data, ff.getOutputBus().getSignal(0).isOn());
    }

}
