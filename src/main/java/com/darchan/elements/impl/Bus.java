package com.darchan.elements.impl;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.IRange;
import com.darchan.elements.iface.ISignal;
import com.darchan.util.Util;

public class Bus implements IBus {

	private List<ISignal> signals;
	
	public Bus(ISignal... signals) {
		this.signals = Collections.unmodifiableList(Arrays.asList(signals));
	}
	
	@Override
	public List<ISignal> getSignals() {
		return this.signals;
	}

	@Override
	public IBus slice(IRange range) {
		return Util.sliceBus(range, this);
	}

	@Override
	public ISignal getSignal(int idx) {
		return this.signals.get(idx);
	}

}
