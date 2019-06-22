package com.darchan.elements.impl;

import com.darchan.elements.iface.ISignal;

public class HighSource extends AbstractSignal implements ISignal {
	
	@Override
	public boolean isOn() {
		return true;
	}
	
}
