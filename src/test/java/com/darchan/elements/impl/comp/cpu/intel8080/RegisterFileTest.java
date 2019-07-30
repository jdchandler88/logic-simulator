package com.darchan.elements.impl.comp.cpu.intel8080;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ISignal;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.SimpleRange;
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

    @Test
    void outputBusHasFOnHigh8BitsAndAOnLow8BitsOfOutputBus() {
        //create inputs for register file
        ISignal[] inputSignals = new ISignal[24];
        for (int i=0; i<24; i++) {
            inputSignals[i] = new SimpleSignal(false);
        }
        IBus inputBus = new Bus(inputSignals);

        //create register file
        RegisterFile rf = new RegisterFile(inputBus);

        //set data to something unique for F and latch the data
        for (int i=0; i<8; i++) {
            ((SimpleSignal)inputSignals[i]).setOn(true);
        }
        List<Boolean> fValues = collectValuesFromBus(inputBus.slice(new SimpleRange(0, 7)));
        ((SimpleSignal)inputSignals[23]).setOn(true);   //turning this on will latch data on bus in F
        ((SimpleSignal)inputSignals[23]).setOn(false);  //turn this back off

        //set data to something unique for A and latch the data
        //set data to something unique for F and latch the data
        for (int i=0; i<8; i++) {
            ((SimpleSignal)inputSignals[i]).setOn(i%2==0);  //even bits on bus are on
        }
        List<Boolean> aValues = collectValuesFromBus(inputBus.slice(new SimpleRange(0, 7)));
        ((SimpleSignal)inputSignals[22]).setOn(true);   //turning this on will latch data on bus in F
        ((SimpleSignal)inputSignals[22]).setOn(false);  //turn this back off

        //assert that F and A are on teh correct position on the output bus
//        assertAll(
//                () -> assertEquals(fValues, )
//        );

    }

    @Test
    void FGetsValueWhenInput23GoesHigh() {

    }

    @Test
    void AGetsValueWhenInput22GoesHigh() {

    }

    @Test
    void BGetsValueWhenInput21GoesHigh() {

    }

    @Test
    void CGetsValueWhenInput20GoesHigh() {

    }

    @Test
    void DGetsValueWhenInput19GoesHigh() {

    }

    @Test
    void EGetsValueWhenInput18GoesHigh() {

    }

    @Test
    void HGetsValueWhenInput17GoesHigh() {

    }

    @Test
    void LGetsValueWhenInput16GoesHigh() {

    }

    private static List<Boolean> collectValuesFromBus(IBus bus) {
        return bus.getSignals().stream().map(ISignal::isOn).collect(Collectors.toList());
    }

}
