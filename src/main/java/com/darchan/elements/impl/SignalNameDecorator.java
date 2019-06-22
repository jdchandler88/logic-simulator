package com.darchan.elements.impl;

import com.darchan.elements.iface.ISignal;
import com.darchan.elements.iface.ISignalChangeListener;

public class SignalNameDecorator extends AbstractSignalDecorator {
	
	private String name;
	
	public SignalNameDecorator(ISignal wrapped, String name) {
		super(wrapped);
		this.name = name;
	}
	
	@Override
	public String toString() {
		return this.name + "-" + this.isOn();
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
