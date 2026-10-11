package com.darchan.physical.logic;

import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.ILogicUnit;
import com.darchan.physical.signal.IRange;
import com.darchan.physical.signal.LogicValue;
import com.darchan.physical.signal.SimpleRange;

public class LogicalFalseLogicUnit implements ILogicUnit {

    private static final IRange ALLOWABLE_BUS_WIDTH = new SimpleRange(1, Integer.MAX_VALUE);

    @Override
    public boolean evaluate(IBus inputBus) {
        return false;
    }

    /** {@link LogicValue#ZERO} whatever the inputs are */
    @Override
    public LogicValue evaluateValue(IBus inputBus) {
        return LogicValue.ZERO;
    }

    @Override
    public IRange getAllowedInputBusWidth() {
        return ALLOWABLE_BUS_WIDTH;
    }
}

