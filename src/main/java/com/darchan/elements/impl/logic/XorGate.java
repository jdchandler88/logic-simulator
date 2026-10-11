package com.darchan.elements.impl.logic;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ILogicUnit;
import com.darchan.elements.iface.IRange;
import com.darchan.elements.iface.ISignal;
import com.darchan.elements.impl.SimpleRange;

import java.util.List;

/**
 * Exclusive OR of all inputs: true when an odd number of inputs are on (with 2 inputs, plain XOR). Two or more inputs.
 */
public class XorGate implements ILogicUnit {

    private static final IRange ALLOWABLE_BUS_WIDTH = new SimpleRange(2, Integer.MAX_VALUE);

    @Override
    public boolean evaluate(IBus inputBus) {
        List<ISignal> signals = inputBus.getSignals();
        boolean result = false;
        for (int i = 0; i < signals.size(); i++) {
            result ^= signals.get(i).isOn();
        }
        return result;
    }

    @Override
    public IRange getAllowedInputBusWidth() {
        return ALLOWABLE_BUS_WIDTH;
    }
}
