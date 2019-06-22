package com.darchan.elements.impl;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ISignal;

public abstract class ReactiveSignal extends AbstractSignal {

	private ISignal stimulus;
	
	private IBus inputBus;
	
	private Map<Integer, ISignal>
	
	public ReactiveSignal(ISignal stimulus) {
		this.stimulus = stimulus;
		this.stimulus.addSignalChangeListener(this::reactToChange);
	}
	
	public ReactiveSignal(IBus inputBus) {
		this.inputBus = inputBus;
		
	}
	
	protected abstract void reactToChange(boolean oldValue, boolean newValue);
	
}
