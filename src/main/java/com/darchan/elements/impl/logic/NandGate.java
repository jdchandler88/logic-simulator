package com.darchan.elements.impl.logic;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ILogicUnit;
import com.darchan.elements.iface.IRange;
import com.darchan.elements.iface.ISignal;
import com.darchan.elements.impl.SimpleRange;

import java.util.List;

/**
 * NOT AND: false only when every input is on. Two or more inputs.
 */
public class NandGate implements ILogicUnit {

    private static final IRange ALLOWABLE_BUS_WIDTH = new SimpleRange(2, Integer.MAX_VALUE);

    @Override
    public boolean evaluate(IBus inputBus) {
        List<ISignal> signals = inputBus.getSignals();
        for (int i = 0; i < signals.size(); i++) {
            if (!signals.get(i).isOn()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public IRange getAllowedInputBusWidth() {
        return ALLOWABLE_BUS_WIDTH;
    }
}
