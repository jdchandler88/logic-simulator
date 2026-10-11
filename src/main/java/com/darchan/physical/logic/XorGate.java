package com.darchan.physical.logic;

import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.ILogicUnit;
import com.darchan.physical.signal.IRange;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.SimpleRange;

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
