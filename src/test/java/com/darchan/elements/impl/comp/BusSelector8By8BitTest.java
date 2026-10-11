package com.darchan.elements.impl.comp;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ISignal;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.SimpleSignal;
import com.darchan.sim.Simulator;
import com.darchan.testutil.Buses;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class BusSelector8By8BitTest {

    private final Simulator sim = new Simulator();

    /**
     * Data bus {@code n} has only bit {@code n} set; the select bus has only bit {@code selected} set
     * (-1 selects nothing, i.e. the output floats).
     */
    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 2, 3, 4, 5, 6, 7})
    void correctInputBusIsSelected(int selected) {
        ISignal[][] data = new ISignal[8][];
        for (int n = 0; n < 8; n++) {
            data[n] = createSignalArray(n);
        }
        ISignal[] selectionSignal = createSignalArray(selected);
        IBus inputBus = new Bus(combine(data[0], data[1], data[2], data[3], data[4], data[5], data[6], data[7],
                selectionSignal));
        IBus expected = new Bus(selected < 0 ? createSignalArray(-1) : data[selected]);

        BusSelector8By8Bit mux = new BusSelector8By8Bit(sim, inputBus);
        sim.settle();
        assertEquals(collectValuesFromBus(expected), collectValuesFromBus(mux.getOutputBus()));
    }

    @Test
    void outputFollowsSelectionAndSelectedDataChanges() {
        IBus inputBus = Buses.simpleBus(sim, 72, 0);
        IBus[] data = new IBus[8];
        for (int n = 0; n < 8; n++) {
            data[n] = inputBus.slice(new com.darchan.elements.impl.SimpleRange(8 * n, 8 * n + 7));
            Buses.drive(data[n], 0x11 * n + 1);
        }
        IBus select = inputBus.slice(new com.darchan.elements.impl.SimpleRange(64, 71));
        BusSelector8By8Bit mux = new BusSelector8By8Bit(sim, inputBus);
        sim.settle();
        assertEquals(0, Buses.toInt(mux.getOutputBus()), "nothing selected");

        for (int n = 0; n < 8; n++) {
            Buses.drive(select, 1 << n);
            sim.settle();
            assertEquals(0x11 * n + 1, Buses.toInt(mux.getOutputBus()), "selected " + n);
        }

        //changing the selected bus updates the output; changing another bus does not
        Buses.drive(data[7], 0xA5);
        Buses.drive(data[3], 0x5A);
        sim.settle();
        assertEquals(0xA5, Buses.toInt(mux.getOutputBus()));

        //change selection and data in the same delta
        Buses.drive(select, 1 << 3);
        Buses.drive(data[3], 0xC3);
        sim.settle();
        assertEquals(0xC3, Buses.toInt(mux.getOutputBus()));
        Buses.drive(data[7], 0x00);
        sim.settle();
        assertEquals(0xC3, Buses.toInt(mux.getOutputBus()));
    }

    private ISignal[] createSignalArray(int on) {
        ISignal[] sigs = new ISignal[8];
        for (int i=0; i<8; i++) {
            sigs[i] = new SimpleSignal(sim, i==on);
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
