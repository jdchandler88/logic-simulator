package com.darchan.util;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.IRange;
import com.darchan.elements.iface.ISignal;
import com.darchan.elements.iface.ISignalChangeListener;
import com.darchan.elements.impl.Bus;

/**
 * utility methods for project
 */
public final class Util {

    /**
     * private to avoid instantiation
     */
    private Util() {}

    public static IBus sliceBus(IRange signalRange, IBus bus) {
        int numSignals = signalRange.getMax() - signalRange.getMin() + 1;
        ISignal[] splice = new ISignal[numSignals];
        int signalIdx = signalRange.getMin();
        for (int i=0; i<numSignals; i++) {
            splice[i] = bus.getSignals().get(signalIdx++);
        }
        return new Bus(splice);
    }

}
