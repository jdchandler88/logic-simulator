package com.darchan.elements.impl.comp.cpu.intel8080;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.IComponent;
import com.darchan.elements.iface.IRange;
import com.darchan.elements.iface.ISignal;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.SimpleRange;
import com.darchan.elements.impl.comp.Register;

/**
 * Module containing registers.
 *
 * InputBus: 24bits [23:16] = latches for each register [f,a,b,c,d,e,h,l]. [15:0] = data bus for registers
 */
public class RegisterFile implements IComponent {

    //input bus should be exactly 24 bits wide. 8 for 'select/enable/latch' and 16 for data
    private static final IRange ALLOWED_INPUT_BUS_WIDTH = new SimpleRange(24, 24);

    //output bus should be exactly 16 bits wide. 8 bits for flags and 8 bits from the accumulator
    private static final IRange ALLOWED_OUTPUT_BUS_WIDTH = new SimpleRange(8, 8);

    private final IBus inputBus;

    private final IBus outputBus;

    final Register F;

    final Register A;

    final Register B;

    final Register C;

    final Register D;

    final Register E;

    final Register H;

    final Register L;


    public RegisterFile(IBus inputBus) {
        //validate that input bus is the correct width

        this.inputBus = inputBus;

        //get shared bus
        IBus highBus = inputBus.slice(new SimpleRange(8, 15));
        IBus lowBus = inputBus.slice(new SimpleRange(0, 7));

        //initialize registers
        this.F = new Register(inputBus.getSignal(23), highBus, false);
        this.A = new Register(inputBus.getSignal(22), lowBus, false);
        this.B = new Register(inputBus.getSignal(21), highBus, false);
        this.C = new Register(inputBus.getSignal(20), lowBus, false);
        this.D = new Register(inputBus.getSignal(19), highBus, false);
        this.E = new Register(inputBus.getSignal(18), lowBus, false);
        this.H = new Register(inputBus.getSignal(17), highBus, false);
        this.L = new Register(inputBus.getSignal(16), lowBus, false);

        //output is F/A?
        ISignal[] outputSignals = new ISignal[16];
        for (int i=0; i<8; i++) {
            outputSignals[i] = this.F.getOutputBus().getSignal(i);
            outputSignals[i+8] = this.A.getOutputBus().getSignal(i);
        }
        this.outputBus = new Bus(outputSignals);


    }

    /**
     * latch and 8-bit-input for each register in the following order: F,A,B,C,D,E,H,L
     *
     * @return
     */
    @Override
    public IBus getInputBus() {
        return this.inputBus;
    }

    @Override
    public IRange getAllowedInputBusWidth() {
        return ALLOWED_INPUT_BUS_WIDTH;
    }

    @Override
    public IBus getOutputBus() {
        return this.outputBus;
    }

    @Override
    public IRange getAllowedOutputBusWidth() {
        return ALLOWED_OUTPUT_BUS_WIDTH;
    }
}
