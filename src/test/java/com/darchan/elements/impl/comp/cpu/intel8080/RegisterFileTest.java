package com.darchan.elements.impl.comp.cpu.intel8080;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ISignal;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.SimpleSignal;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RegisterFileTest {

    @Test
    void registersHaveInitialValue() {
        //set up expected value (all registers should be set to all 0)
        Boolean[] expectedValues = new Boolean[8];
        Arrays.fill(expectedValues, false);
        List<Boolean> expected = Arrays.asList(expectedValues);

        //create register file with input bus
        ISignal[] inputSignals = new ISignal[24];
        for (int i=0; i<24; i++) {
            inputSignals[i] = new SimpleSignal(false);
        }
        IBus inputBus = new Bus(inputSignals);
        RegisterFile rf = new RegisterFile(inputBus);

        //make sure each register is initialized to false
        assertAll(
                () -> assertEquals(expected, collectValuesFromBus(rf.F.getOutputBus())),
                () -> assertEquals(expected, collectValuesFromBus(rf.A.getOutputBus())),
                () -> assertEquals(expected, collectValuesFromBus(rf.B.getOutputBus())),
                () -> assertEquals(expected, collectValuesFromBus(rf.C.getOutputBus())),
                () -> assertEquals(expected, collectValuesFromBus(rf.D.getOutputBus())),
                () -> assertEquals(expected, collectValuesFromBus(rf.E.getOutputBus())),
                () -> assertEquals(expected, collectValuesFromBus(rf.H.getOutputBus())),
                () -> assertEquals(expected, collectValuesFromBus(rf.L.getOutputBus()))
        );
    }

    // TODO(Phase 1): add real RegisterFile tests. Removed 9 vacuous tests (8 empty
    // per-register latch tests and outputBusHasFOnHigh8BitsAndAOnLow8BitsOfOutputBus)
    // that asserted nothing; rewrite them once RegisterFile wiring is fixed.

    private static List<Boolean> collectValuesFromBus(IBus bus) {
        return bus.getSignals().stream().map(ISignal::isOn).collect(Collectors.toList());
    }

}
