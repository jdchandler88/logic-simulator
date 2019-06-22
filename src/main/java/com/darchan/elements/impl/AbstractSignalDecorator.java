package com.darchan.elements.impl;

import com.darchan.elements.iface.ISignal;

public abstract class AbstractSignalDecorator implements ISignal {

	protected ISignal wrapped;
	
	protected AbstractSignalDecorator(ISignal wrapped) {
		this.wrapped = wrapped;
	}
	
	@Override
	public final boolean isOn() {
		return this.wrapped.isOn();
	}
	
}
