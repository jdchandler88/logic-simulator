package com.darchan.physical.signal;

import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.ISignalChangeListener;

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
	
}
