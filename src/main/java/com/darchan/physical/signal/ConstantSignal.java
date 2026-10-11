package com.darchan.physical.signal;

import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.ISignalChangeListener;
import com.darchan.physical.signal.LogicValue;

/**
 * A signal that never changes (tied high, tied low, or left floating). Needs no simulator.
 */
public class ConstantSignal implements ISignal {

    public static final ISignal ON = new ConstantSignal(true);

    public static final ISignal OFF = new ConstantSignal(false);

    /**
     * an undriven (high-impedance) signal
     */
    public static final ISignal HIGH_Z = new ConstantSignal(LogicValue.Z);

    private final LogicValue value;

    public ConstantSignal(boolean on) {
        this(LogicValue.of(on));
    }

    public ConstantSignal(LogicValue value) {
        this.value = value;
    }

    @Override
    public boolean isOn() {
        return this.value.isOn();
    }

    @Override
    public LogicValue getValue() {
        return this.value;
    }

    @Override
    public void addSignalChangeListener(ISignalChangeListener listener) {}

    @Override
    public void removeSignalChangeListener(ISignalChangeListener listener) {}

}
