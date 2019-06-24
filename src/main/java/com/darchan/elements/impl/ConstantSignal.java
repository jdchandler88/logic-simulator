package com.darchan.elements.impl;

import com.darchan.elements.iface.ISignal;
import com.darchan.elements.iface.ISignalChangeListener;

public class ConstantSignal implements ISignal {

    public static ISignal ON = new ConstantSignal(true);

    public static ISignal OFF = new ConstantSignal(false);

    private final boolean on;

    public ConstantSignal(boolean on) {
        this.on = on;
    }

    @Override
    public boolean isOn() {
        return this.on;
    }

    @Override
    public void addSignalChangeListener(ISignalChangeListener listener) {}

    @Override
    public void removeSignalChangeListener(ISignalChangeListener listener) {}

}
