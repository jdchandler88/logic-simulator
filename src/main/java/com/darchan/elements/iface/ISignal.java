package com.darchan.elements.iface;

public interface ISignal {

	public boolean isOn();
	
	public void addSignalChangeListener(ISignalChangeListener listener);
	
	public void removeSignalChangeListener(ISignalChangeListener listener);
	
}
