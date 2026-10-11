package com.darchan.elements.impl.comp;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.IClock;
import com.darchan.elements.iface.ISignal;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.Clock;
import com.darchan.elements.impl.SimpleSignal;
import com.darchan.sim.Simulator;
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


}
