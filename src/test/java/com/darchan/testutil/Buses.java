package com.darchan.testutil;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ISignal;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.SimpleSignal;
import com.darchan.sim.Simulator;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Test helpers for converting between buses and integers. Bit 0 of the bus is the least significant bit.
 */
public final class Buses {

    private Buses() {}

    /** Creates a bus of {@code width} directly-settable signals, initialised to the low bits of {@code value}. */
    public static IBus simpleBus(Simulator sim, int width, int value) {
        ISignal[] signals = new ISignal[width];
        for (int i = 0; i < width; i++) {
            signals[i] = new SimpleSignal(sim, ((value >> i) & 1) == 1);
        }
        return new Bus(signals);
    }

    /** Schedules the low bits of {@code value} onto a bus of {@link SimpleSignal}s (call {@code settle()} after). */
    public static void drive(IBus bus, int value) {
        for (int i = 0; i < bus.getSignals().size(); i++) {
            ((SimpleSignal) bus.getSignal(i)).setOn(((value >> i) & 1) == 1);
        }
    }

    /** Reads a bus as an unsigned integer (bit 0 = LSB). Z and X read as 0, like {@link ISignal#isOn()}. */
    public static int toInt(IBus bus) {
        int value = 0;
        List<ISignal> signals = bus.getSignals();
        for (int i = 0; i < signals.size(); i++) {
            if (signals.get(i).isOn()) {
                value |= 1 << i;
            }
        }
        return value;
    }

    public static List<Boolean> values(IBus bus) {
        return bus.getSignals().stream().map(ISignal::isOn).collect(Collectors.toList());
    }

    public static boolean bit(IBus bus, int idx) {
        return bus.getSignal(idx).isOn();
    }
}
