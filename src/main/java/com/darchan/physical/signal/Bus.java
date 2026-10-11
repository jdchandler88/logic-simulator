package com.darchan.physical.signal;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.IRange;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.util.Util;

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
