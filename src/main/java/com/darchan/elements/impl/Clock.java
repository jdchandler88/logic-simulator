package com.darchan.elements.impl;

import com.darchan.elements.iface.IClock;

public class Clock extends AbstractSignal implements IClock {
	
	@Override
	public void tick() {
		this.setState(!state);
	}
	
}
