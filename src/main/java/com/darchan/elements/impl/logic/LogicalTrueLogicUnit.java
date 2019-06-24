package com.darchan.elements.impl.logic;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ILogicUnit;

public class LogicalTrueLogicUnit implements ILogicUnit {
    @Override
    public boolean evaluate(IBus inputBus) {
        return true;
    }
}
