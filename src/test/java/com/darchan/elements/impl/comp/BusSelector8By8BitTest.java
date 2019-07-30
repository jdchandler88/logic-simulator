package com.darchan.elements.impl.comp;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ISignal;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.SimpleSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class BusSelector8By8BitTest {


    @ParameterizedTest
    @MethodSource("getInputs")
    void correctInputBusIsSelected(IBus inputBus, IBus expected) {
        BusSelector8By8Bit mux = new BusSelector8By8Bit(inputBus);
        assertEquals(collectValuesFromBus(expected), collectValuesFromBus(mux.getOutputBus()));
    }


    static Stream<Arguments> getInputs() {

        List<Arguments> args = new ArrayList<>();

        //-1 will create the floating selection
        for (int i=-1; i<8; i++) {
            ISignal[] selectionSignal = createSignalArray(i);
            ISignal[] bus0 = createSignalArray(0);
            ISignal[] bus1 = createSignalArray(1);
            ISignal[] bus2 = createSignalArray(2);
            ISignal[] bus3 = createSignalArray(3);
            ISignal[] bus4 = createSignalArray(4);
            ISignal[] bus5 = createSignalArray(5);
            ISignal[] bus6 = createSignalArray(6);
            ISignal[] bus7 = createSignalArray(7);

            ISignal[] inputBus = combine(bus0, bus1, bus2, bus3, bus4, bus5, bus6, bus7, selectionSignal);
            ISignal[] expectedOutput;
            switch (i) {
                case -1:
                    expectedOutput = createSignalArray(-1);
                    break;
                case 0:
                    expectedOutput = bus0;
                    break;
                case 1:
                    expectedOutput = bus1;
                    break;
                case 2:
                    expectedOutput = bus2;
                    break;
                case 3:
                    expectedOutput = bus3;
                    break;
                case 4:
                    expectedOutput = bus4;
                    break;
                case 5:
                    expectedOutput = bus5;
                    break;
                case 6:
                    expectedOutput = bus6;
                    break;
                case 7:
                    expectedOutput = bus7;
                    break;
                default:
                    expectedOutput = createSignalArray(-1);
                    break;
            }

            args.add(Arguments.of(new Bus(inputBus), new Bus(expectedOutput)));
        }

        return args.stream();
    }

    private static ISignal[] createSignalArray(int on) {
        ISignal[] sigs = new ISignal[8];
        for (int i=0; i<8; i++) {
            sigs[i] = new SimpleSignal(i==on);
        }
        return sigs;
    }

    private static ISignal[] combine(ISignal[]... toCombine) {
        return Stream.of(toCombine)
            .flatMap(Arrays::stream)
            .toArray(ISignal[]::new);
    }

    private static List<Boolean> collectValuesFromBus(IBus bus) {
        return bus.getSignals().stream()
                .map(s -> s.isOn())
                .collect(Collectors.toList());
    }



}