package com.darchan.elements.impl.logic;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ILogicUnit;

public class InverterLogicUnit implements ILogicUnit {
    @Override
    public boolean evaluate(IBus inputBus) {
        return !inputBus.getSignals().get(0).isOn();
    }
}
