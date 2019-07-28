package com.darchan.elements.impl.com.darchan.elements.impl.comp;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.IClock;
import com.darchan.elements.iface.ISignal;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.Clock;
import com.darchan.elements.impl.SimpleSignal;
import com.darchan.elements.impl.comp.Register;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegisterTest {

    @ParameterizedTest
    @CsvSource(value = {"2", "4", "8", "16"})
    void outputBusShouldBeLengthN(int width) {
        IClock clk = new Clock(false);
        IBus inputBus = creatEBusOfWidthWithValue(width, false);
        Register reg = new Register(clk, inputBus, false);
        assertEquals(width, reg.getOutputBus().getSignals().size());
    }

    @ParameterizedTest
    @CsvSource(value = {"true", "false"})
    void eachOutputShouldHaveSuppliedInitialValueWith16BitRegister(boolean initialValue) {
        int width = 16;
        IClock clk = new Clock(false);
        IBus inputBus = creatEBusOfWidthWithValue(width, initialValue);
        Register reg = new Register(clk, inputBus, initialValue);
        assertEquals(collectBusValues(inputBus), collectBusValues(reg.getOutputBus()));
    }

    @ParameterizedTest
    @MethodSource("getInputBuses")
    void outputBusShouldReflectRespectiveInputsAfterClockGoesHigh(IBus inputBus) {
        //initialize inputs
        boolean initialValue = false;
        IClock clk = new Clock(false);
        Register reg = new Register(clk, inputBus, initialValue);

        //clock goes low to high
        clk.tick();

        //inputs and outputs should equal
        assertEquals(collectBusValues(inputBus), collectBusValues(reg.getOutputBus()));
    }

    @ParameterizedTest
    @MethodSource("getInputBuses")
    void outputBusShouldNOTReflectRespectiveInputsAfterClockGoesLow(IBus inputBus) {
        //initialize inputs
        boolean initialValue = false;
        IClock clk = new Clock(true);
        Register reg = new Register(clk, inputBus, initialValue);

        //clock goes low to high
        clk.tick();

        //inputs and outputs should equal
        int inputWidth = inputBus.getSignals().size();
        IBus expectedValues = creatEBusOfWidthWithValue(inputWidth, initialValue);
        assertEquals(collectBusValues(expectedValues), collectBusValues(reg.getOutputBus()));
    }

    static Stream<Arguments> getInputBuses() {
        return Stream.of(
                Arguments.of(createBusOfWidthWithAlternatingValues(2)),
                Arguments.of(createBusOfWidthWithAlternatingValues(4)),
                Arguments.of(createBusOfWidthWithAlternatingValues(8)),
                Arguments.of(createBusOfWidthWithAlternatingValues(16))
        );
    }


    /*
    HELPER SECTION
     */

    private static IBus creatEBusOfWidthWithValue(int width, boolean value) {
        ISignal[] sigs = new ISignal[width];
        for (int i=0; i<width; i++) {
            sigs[i] = new SimpleSignal(value);
        }
        return new Bus(sigs);
    }

    private static IBus createBusOfWidthWithAlternatingValues(int width) {
        ISignal[] sigs = new ISignal[width];
        for (int i=0; i<width; i++) {
            //even signals will be true, odd will be false
            sigs[i] = new SimpleSignal(i%2==0);
        }
        return new Bus(sigs);
    }

    private static List<Boolean> collectBusValues(IBus bus) {
        return bus.getSignals().stream().map(ISignal::isOn).collect(Collectors.toList());
    }


}
