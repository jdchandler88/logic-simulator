package com.darchan.elements.impl;

import com.darchan.elements.iface.IClock;

public class Clock extends AbstractSignal implements IClock {

	public Clock(boolean initialState) {
		this.state = initialState;
	}
	
	@Override
	public void tick() {
		this.setState(!state);
	}
	
}
