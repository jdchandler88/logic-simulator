package com.darchan.elements.iface;

public interface IComponent {
	 
	IBus getInputBus();

	IRange getAllowedInputBusWidth();
	
	IBus getOutputBus();

	IRange getAllowedOutputBusWidth();
		
}
