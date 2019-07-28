package com.darchan.elements.impl.comp;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.IComponent;
import com.darchan.elements.iface.IRange;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.BusWidthAllowableRange;
import com.darchan.elements.impl.LogicSignal;
import com.darchan.elements.impl.logic.InverterLogicUnit;

public class Inverter implements IComponent {

	/**
	 * allowed width of both input and output buses
	 */
	private static final IRange ALLOWED_BUS_WIDTH = new BusWidthAllowableRange(1, 1);

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
	public IRange getAllowedInputBusWidth() {
		return ALLOWED_BUS_WIDTH;
	}

	@Override
	public IBus getOutputBus() {
		return this.outputBus;
	}

	@Override
	public IRange getAllowedOutputBusWidth() {
		return ALLOWED_BUS_WIDTH;
	}

}