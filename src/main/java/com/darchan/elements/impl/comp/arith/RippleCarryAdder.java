package com.darchan.elements.impl.comp.arith;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.IComponent;
import com.darchan.elements.iface.IRange;
import com.darchan.elements.iface.ISignal;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.SimpleRange;
import com.darchan.sim.Simulator;
import com.darchan.validation.Validation;

/**
 * N-bit ripple-carry adder: a chain of {@link FullAdder}s, carry out of bit i feeding carry in of bit i+1.
 *
 * <p>Input bus (2n+1 bits): {@code [n-1:0]} = A, {@code [2n-1:n]} = B, {@code [2n]} = carry in.
 * Output bus (n+1 bits): {@code [n-1:0]} = sum, {@code [n]} = carry out (so the output reads as the integer
 * A + B + carry in). {@link #getCarries()} exposes the carry out of every bit, e.g. bit 3 for a half-carry flag.
 */
public class RippleCarryAdder implements IComponent {

    private final IRange allowedInputBusWidth;

    private final IRange allowedOutputBusWidth;

    private final IBus inputBus;

    private final IBus outputBus;

    private final IBus carries;

    /**
     * @param sim simulator
     * @param width number of bits n (at least 1)
     * @param inputBus A, B and carry in, 2n+1 bits
     */
    public RippleCarryAdder(Simulator sim, int width, IBus inputBus) {
        if (width < 1) {
            throw new IllegalArgumentException("width must be at least 1 but was " + width);
        }
        this.allowedInputBusWidth = new SimpleRange(2 * width + 1, 2 * width + 1);
        this.allowedOutputBusWidth = new SimpleRange(width + 1, width + 1);
        Validation.validateInputWidth(this.allowedInputBusWidth, inputBus);
        this.inputBus = inputBus;

        ISignal[] sum = new ISignal[width + 1];
        ISignal[] carryOut = new ISignal[width];
        ISignal carry = inputBus.getSignal(2 * width);
        for (int i = 0; i < width; i++) {
            FullAdder fa = new FullAdder(sim, new Bus(inputBus.getSignal(i), inputBus.getSignal(width + i), carry));
            sum[i] = fa.getSum();
            carryOut[i] = fa.getCarryOut();
            carry = carryOut[i];
        }
        sum[width] = carry;
        this.outputBus = new Bus(sum);
        this.carries = new Bus(carryOut);
    }

    /**
     * @return n bits: [i] = carry out of bit i ([n-1] is the adder's carry out)
     */
    public IBus getCarries() {
        return this.carries;
    }

    @Override
    public IBus getInputBus() {
        return this.inputBus;
    }

    @Override
    public IRange getAllowedInputBusWidth() {
        return this.allowedInputBusWidth;
    }

    @Override
    public IBus getOutputBus() {
        return this.outputBus;
    }

    @Override
    public IRange getAllowedOutputBusWidth() {
        return this.allowedOutputBusWidth;
    }
}
