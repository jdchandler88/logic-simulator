package com.darchan.elements.impl;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ISignal;

public class Bus implements IBus {

	private List<ISignal> signals;
	
	public Bus(ISignal... signals) {
		this.signals = Collections.unmodifiableList(Arrays.asList(signals));
	}
	
	@Override
	public List<ISignal> getSignals() {
		return this.signals;
	}

}
