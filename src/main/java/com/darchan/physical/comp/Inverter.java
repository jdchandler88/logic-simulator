package com.darchan.physical.comp;

import com.darchan.physical.validation.Validation;
import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.IComponent;
import com.darchan.physical.signal.IRange;
import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.SimpleRange;
import com.darchan.physical.signal.LogicSignal;
import com.darchan.physical.logic.InverterLogicUnit;
import com.darchan.physical.sim.Simulator;

public class Inverter implements IComponent {

	/**
	 * allowed width of both input and output buses
	 */
	private static final IRange ALLOWED_BUS_WIDTH = new SimpleRange(1, 1);

	private IBus inputBus;
	
	private IBus outputBus;
	
	public Inverter(Simulator sim, IBus input) {
		Validation.validateInputWidth(ALLOWED_BUS_WIDTH, input);
		this.inputBus = input;
		this.outputBus = new Bus(new LogicSignal(sim, this.inputBus, new InverterLogicUnit()));
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