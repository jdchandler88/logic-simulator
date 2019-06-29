package com.darchan.elements.impl.logic;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ILogicUnit;
import com.darchan.elements.iface.ISignal;

public class AndGate implements ILogicUnit {

    @Override
    public boolean evaluate(IBus inputBus) {
        boolean result = true;
        for (ISignal inputSignal : inputBus.getSignals()) {
            if (!inputSignal.isOn()) {
                result = false;
                break;
            }
        }
        return result;
    }

}
