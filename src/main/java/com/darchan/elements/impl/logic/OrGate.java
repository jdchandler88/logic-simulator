package com.darchan.elements.impl.logic;

import com.darchan.com.darchan.validation.Validation;
import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ILogicUnit;
import com.darchan.elements.iface.ISignal;

public class OrGate implements ILogicUnit {

    private static final int MINIMUM_BUS_WIDTH = 2;

    private static final int MAXIMUM_BUS_WIDTH = Integer.MAX_VALUE;

    @Override
    public boolean evaluate(IBus inputBus) {
        Validation.validateInputWidth(MINIMUM_BUS_WIDTH, MAXIMUM_BUS_WIDTH, inputBus);
        return inputBus.getSignals().stream().anyMatch(ISignal::isOn);
    }

}
