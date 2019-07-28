package com.darchan.elements.impl.logic;

import com.darchan.com.darchan.validation.Validation;
import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ILogicUnit;
import com.darchan.elements.iface.IRange;
import com.darchan.elements.impl.SimpleRange;

public class InverterLogicUnit implements ILogicUnit {

    private static final IRange ALLOWABLE_BUS_WIDTH = new SimpleRange(1, 1);

    @Override
    public boolean evaluate(IBus inputBus) {
        Validation.validateInputWidth(ALLOWABLE_BUS_WIDTH, inputBus);
        return !inputBus.getSignals().get(0).isOn();
    }

    @Override
    public IRange getAllowedInputBusWidth() {
        return ALLOWABLE_BUS_WIDTH;
    }
}
