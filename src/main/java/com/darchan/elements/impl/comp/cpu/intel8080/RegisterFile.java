package com.darchan.elements.impl.comp.cpu.intel8080;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.IComponent;
import com.darchan.elements.iface.IRange;
import com.darchan.elements.impl.comp.Register;

/**
 * Module containing registers.
 *
 * InputBus: 24bits [23:16] = latches for each register [f,a,b,c,d,e,h,l]. [15:0] = data bus for registers
 */
public class RegisterFile implements IComponent {

//    private final Register F;
//
//    private final Register A;
//
//    private final Register B;
//
//    private final Register C;
//
//    private final Register D;
//
//    private final Register E;
//
//    private final Register H;
//
//    private final Register L;
//
//
//    public RegisterFile(IBus inputBus) {
//        this.F = new Register(inputBus.getSignals().get(23), fData, false);
//        this.A = new Register(aLatch, aData, false);
//        this.B = new Register(bLatch, bData, false);
//        this.C = new Register(cLatch, cData, false);
//        this.D = new Register(dLatch, dData, false);
//        this.E = new Register(eLatch, eData, false);
//        this.H = new Register(hLatch, hData, false);
//        this.L = new Register(lLatch, lData, false);
//    }

    /**
     * latch and 8-bit-input for each register in the following order: F,A,B,C,D,E,H,L
     *
     * @return
     */
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
