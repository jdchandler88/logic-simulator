package com.darchan.elements.impl.logic;

import com.darchan.com.darchan.validation.Validation;
import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ILogicUnit;

public class InverterLogicUnit implements ILogicUnit {

    private static final int MINIMUM_BUS_WIDTH = 1;

    private static final int MAXIMUM_BUS_WIDTH = 1;

    @Override
    public boolean evaluate(IBus inputBus) {
        Validation.validateInputWidth(MINIMUM_BUS_WIDTH, MAXIMUM_BUS_WIDTH, inputBus);
        return !inputBus.getSignals().get(0).isOn();
    }
}
