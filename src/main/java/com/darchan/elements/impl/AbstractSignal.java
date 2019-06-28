package com.darchan.elements.impl;

import java.util.ArrayList;
import java.util.List;

import com.darchan.elements.iface.ISignal;
import com.darchan.elements.iface.ISignalChangeListener;

public abstract class AbstractSignal implements ISignal {

	private List<ISignalChangeListener> listeners = new ArrayList<>();
	
	protected boolean state;

	protected AbstractSignal(boolean initialState) {
		this.state = initialState;
	}
	
	protected final void setState(boolean newState) {
		if (newState != state) {
			this.state = newState;
			this.listeners.forEach(l ->  l.signalChanged(!state, state));
		}
	}
	
	@Override
	public boolean isOn() {
		return this.state;
	}
	
	@Override
	public void addSignalChangeListener(ISignalChangeListener listener) {
		if (!this.listeners.contains(listener)) {
			this.listeners.add(listener);
		}
	}
	
	@Override
	public void removeSignalChangeListener(ISignalChangeListener listener) {
		this.listeners.remove(listener);
	}
	
}
