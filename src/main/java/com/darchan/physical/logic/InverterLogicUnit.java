package com.darchan.physical.logic;

import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.ILogicUnit;
import com.darchan.physical.signal.IRange;
import com.darchan.physical.signal.LogicValue;
import com.darchan.physical.signal.SimpleRange;

/**
 * NOT of its single input. Four-valued: 0 and 1 swap, Z and X give X.
 */
public class InverterLogicUnit implements ILogicUnit {

    private static final IRange ALLOWABLE_BUS_WIDTH = new SimpleRange(1, 1);

    @Override
    public LogicValue evaluateValue(IBus inputBus) {
        return inputBus.getSignals().get(0).getValue().not();
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
