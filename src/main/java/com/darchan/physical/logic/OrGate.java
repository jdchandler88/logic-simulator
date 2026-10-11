package com.darchan.physical.logic;

import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.ILogicUnit;
import com.darchan.physical.signal.IRange;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.LogicValue;
import com.darchan.physical.signal.SimpleRange;

import java.util.List;

/**
 * OR of all inputs, two or more. Four-valued: any 1 gives 1, all 0 gives 0, otherwise (a Z or X input and no 1) X.
 */
public class OrGate implements ILogicUnit {

    private static final IRange ALLOWABLE_BUS_WIDTH = new SimpleRange(2, Integer.MAX_VALUE);

    @Override
    public LogicValue evaluateValue(IBus inputBus) {
        List<ISignal> signals = inputBus.getSignals();
        LogicValue result = LogicValue.ZERO;
        for (int i = 0; i < signals.size(); i++) {
            LogicValue v = signals.get(i).getValue();
            if (v == LogicValue.ONE) {
                return LogicValue.ONE;
            }
            if (v != LogicValue.ZERO) {
                result = LogicValue.X;
            }
        }
        return result;
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
