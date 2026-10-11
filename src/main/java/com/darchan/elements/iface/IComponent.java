package com.darchan.elements.iface;

/**
 * A circuit block with an input bus and an output bus. Implementations validate the input width in their
 * constructor and report the widths they accept and produce (never null).
 */
public interface IComponent {
	 
	IBus getInputBus();

	IRange getAllowedInputBusWidth();
	
	IBus getOutputBus();

	IRange getAllowedOutputBusWidth();
		
}
