package com.darchan.physical.util;

import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.IRange;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.ISignalChangeListener;
import com.darchan.physical.signal.Bus;

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
