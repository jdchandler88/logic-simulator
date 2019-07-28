package com.darchan.elements.impl.comp.cpu.intel8080;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.IComponent;
import com.darchan.elements.iface.IRange;

/**
 * Attempted implementation of Intel 8080 based on ISA and timings
 */
public class Cpu implements IComponent {


    @Override
    public IBus getInputBus() {
        return null;
    }

    @Override
    public IRange getAllowedInputBusWidth() {
        return null;
    }

    @Override
    public IBus getOutputBus() {
        return null;
    }

    @Override
    public IRange getAllowedOutputBusWidth() {
        return null;
    }
}
