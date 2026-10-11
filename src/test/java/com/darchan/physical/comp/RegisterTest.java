package com.darchan.physical.comp;

import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.IClock;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.Clock;
import com.darchan.physical.signal.SimpleSignal;
import com.darchan.physical.sim.Simulator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegisterTest {

    private final Simulator sim = new Simulator();

    @ParameterizedTest
    @CsvSource(value = {"2", "4", "8", "16"})
    void outputBusShouldBeLengthN(int width) {
        IClock clk = new Clock(sim, false);
        IBus inputBus = creatEBusOfWidthWithValue(width, false);
        Register reg = new Register(sim, clk, inputBus, false);
        assertEquals(width, reg.getOutputBus().getSignals().size());
    }

    @ParameterizedTest
    @CsvSource(value = {"true", "false"})
    void eachOutputShouldHaveSuppliedInitialValueWith16BitRegister(boolean initialValue) {
        int width = 16;
        IClock clk = new Clock(sim, false);
        IBus inputBus = creatEBusOfWidthWithValue(width, initialValue);
        Register reg = new Register(sim, clk, inputBus, initialValue);
        assertEquals(collectBusValues(inputBus), collectBusValues(reg.getOutputBus()));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 4, 8, 16})
    void outputBusShouldReflectRespectiveInputsAfterClockGoesHigh(int width) {
        IBus inputBus = createBusOfWidthWithAlternatingValues(width);
        //initialize inputs
        boolean initialValue = false;
        IClock clk = new Clock(sim, false);
        Register reg = new Register(sim, clk, inputBus, initialValue);

        //clock goes low to high
        clk.tick();

        //inputs and outputs should equal
        assertEquals(collectBusValues(inputBus), collectBusValues(reg.getOutputBus()));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 4, 8, 16})
    void outputBusShouldNOTReflectRespectiveInputsAfterClockGoesLow(int width) {
        IBus inputBus = createBusOfWidthWithAlternatingValues(width);
        //initialize inputs
        boolean initialValue = false;
        IClock clk = new Clock(sim, true);
        Register reg = new Register(sim, clk, inputBus, initialValue);

        //clock goes high to low
        clk.tick();

        //outputs keep their initial value
        int inputWidth = inputBus.getSignals().size();
        IBus expectedValues = creatEBusOfWidthWithValue(inputWidth, initialValue);
        assertEquals(collectBusValues(expectedValues), collectBusValues(reg.getOutputBus()));
    }


    /*
    HELPER SECTION
     */

    private IBus creatEBusOfWidthWithValue(int width, boolean value) {
        ISignal[] sigs = new ISignal[width];
        for (int i=0; i<width; i++) {
            sigs[i] = new SimpleSignal(sim, value);
        }
        return new Bus(sigs);
    }

    private IBus createBusOfWidthWithAlternatingValues(int width) {
        ISignal[] sigs = new ISignal[width];
        for (int i=0; i<width; i++) {
            //even signals will be true, odd will be false
            sigs[i] = new SimpleSignal(sim, i%2==0);
        }
        return new Bus(sigs);
    }

    private static List<Boolean> collectBusValues(IBus bus) {
        return bus.getSignals().stream().map(ISignal::isOn).collect(Collectors.toList());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 8, 16})
    void registerWithWriteEnableLoadsOnlyWhenEnabled(int width) {
        int mask = (1 << width) - 1;
        IBus inputBus = com.darchan.testutil.Buses.simpleBus(sim, width, 0x5555 & mask);
        SimpleSignal enable = new SimpleSignal(sim, false);
        IClock clk = new Clock(sim, false);
        Register reg = new Register(sim, clk, inputBus, enable, false);
        sim.settle();

        clk.tick();
        assertEquals(0, com.darchan.testutil.Buses.toInt(reg.getOutputBus()), "disabled: hold");
        clk.tick();

        enable.setOn(true);
        sim.settle();
        clk.tick();
        assertEquals(0x5555 & mask, com.darchan.testutil.Buses.toInt(reg.getOutputBus()), "enabled: load");
        clk.tick();

        enable.setOn(false);
        com.darchan.testutil.Buses.drive(inputBus, 0xAAAA & mask);
        sim.settle();
        clk.tick();
        assertEquals(0x5555 & mask, com.darchan.testutil.Buses.toInt(reg.getOutputBus()), "disabled: hold");
    }

}
