package com.darchan.elements.impl;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ILogicUnit;
import com.darchan.elements.iface.ISignal;

public class LogicSignal extends AbstractSignal {

    private IBus inputBus;

    private ILogicUnit logicUnit;

    public LogicSignal(IBus inputBus, ILogicUnit logicUnit) {
        super(logicUnit.evaluate(inputBus));
        this.inputBus = inputBus;
        this.logicUnit = logicUnit;
        for (ISignal inputSignal : this.inputBus.getSignals()) {
            inputSignal.addSignalChangeListener(this::listenForSignalChange);
        }
    }

    private void listenForSignalChange(boolean oldValue, boolean newValue) {
        this.setState(this.logicUnit.evaluate(this.inputBus));
    }

}
