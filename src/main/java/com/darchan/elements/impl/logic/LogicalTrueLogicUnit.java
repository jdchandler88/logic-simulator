package com.darchan.elements.impl.logic;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ILogicUnit;
import com.darchan.elements.iface.IRange;
import com.darchan.elements.impl.SimpleRange;

public class LogicalTrueLogicUnit implements ILogicUnit {

    private static final IRange ALLOWABLE_BUS_WIDTH = new SimpleRange(1, Integer.MAX_VALUE);

    @Override
    public boolean evaluate(IBus inputBus) {
        return true;
    }

    @Override
    public IRange getAllowedInputBusWidth() {
        return ALLOWABLE_BUS_WIDTH;
    }
}
