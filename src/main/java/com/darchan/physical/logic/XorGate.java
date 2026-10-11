package com.darchan.physical.logic;

import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.ILogicUnit;
import com.darchan.physical.signal.IRange;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.LogicValue;
import com.darchan.physical.signal.SimpleRange;

import java.util.List;

/**
 * Exclusive OR of all inputs: true when an odd number of inputs are on (with 2 inputs, plain XOR). Two or more inputs.
 * Four-valued: any Z or X input gives X (no input value controls an XOR).
 */
public class XorGate implements ILogicUnit {

    private static final IRange ALLOWABLE_BUS_WIDTH = new SimpleRange(2, Integer.MAX_VALUE);

    @Override
    public LogicValue evaluateValue(IBus inputBus) {
        List<ISignal> signals = inputBus.getSignals();
        boolean odd = false;
        for (int i = 0; i < signals.size(); i++) {
            LogicValue v = signals.get(i).getValue();
            if (v == LogicValue.ONE) {
                odd = !odd;
            } else if (v != LogicValue.ZERO) {
                return LogicValue.X;
            }
        }
        return odd ? LogicValue.ONE : LogicValue.ZERO;
    }

    /** {@code evaluateValue(inputBus).isOn()} */
    @Override
    public boolean evaluate(IBus inputBus) {
        return evaluateValue(inputBus) == LogicValue.ONE;
    }

    @Override
    public IRange getAllowedInputBusWidth() {
        return ALLOWABLE_BUS_WIDTH;
    }
}
