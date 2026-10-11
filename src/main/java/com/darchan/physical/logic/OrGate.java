package com.darchan.physical.logic;

import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.ILogicUnit;
import com.darchan.physical.signal.IRange;
import com.darchan.physical.signal.ISignal;

import java.util.List;
import com.darchan.physical.signal.SimpleRange;

public class OrGate implements ILogicUnit {

    private static final IRange ALLOWABLE_BUS_WIDTH = new SimpleRange(2, Integer.MAX_VALUE);

    @Override
    public boolean evaluate(IBus inputBus) {
        List<ISignal> signals = inputBus.getSignals();
        for (int i = 0; i < signals.size(); i++) {
            if (signals.get(i).isOn()) {
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
