package com.darchan.elements.impl;

import com.darchan.elements.iface.ISignal;
import com.darchan.elements.iface.ISignalChangeListener;
import com.darchan.elements.iface.LogicValue;

public abstract class AbstractSignalDecorator implements ISignal {

	protected ISignal wrapped;
	
	protected AbstractSignalDecorator(ISignal wrapped) {
		this.wrapped = wrapped;
	}
	
	@Override
	public final boolean isOn() {
		return this.wrapped.isOn();
	}

	@Override
	public final LogicValue getValue() {
		return this.wrapped.getValue();
	}

	@Override
	public void addSignalChangeListener(ISignalChangeListener listener) {
		this.wrapped.addSignalChangeListener(listener);
	}

	@Override
	public void removeSignalChangeListener(ISignalChangeListener listener) {
		this.wrapped.removeSignalChangeListener(listener);
	}
}
