package com.darchan.elements.impl.comp;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.IComponent;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.ReactiveSignal;

public class Inverter implements IComponent {

	private IBus inputBus;
	
	private IBus outputBus;
	
	public Inverter(IBus input) {
		this.inputBus = input;
		this.outputBus = new Bus(new ReactiveSignal(input.getSignals().get(0)) {
			@Override
			protected void reactToChange(boolean oldValue, boolean newValue) {
				setState(!newValue);
			}
		});
	}
	
	@Override
	public IBus getInputBus() {
		return this.inputBus;
	}
	
	@Override
	public IBus getOutputBus() {
		return this.outputBus;
	}
	
}
