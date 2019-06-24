package com.darchan.elements.impl.comp;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.IComponent;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.LogicSignal;
import com.darchan.elements.impl.logic.InverterLogicUnit;

public class Inverter implements IComponent {

	private IBus inputBus;
	
	private IBus outputBus;
	
	public Inverter(IBus input) {
		this.inputBus = input;
		this.outputBus = new Bus(new LogicSignal(this.inputBus, new InverterLogicUnit()));
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

//listen to events instead of trying to connect listener objects. this sounds similar, but the difference is that
//every component, always, will listen to events. It will be up to the component to make sure  that it cares about the
//incoming event.