package com.darchan.elements.impl.comp.arith;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.IComponent;
import com.darchan.elements.iface.IRange;
import com.darchan.elements.iface.ISignal;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.Gates;
import com.darchan.elements.impl.SimpleRange;
import com.darchan.sim.Simulator;
import com.darchan.validation.Validation;

/**
 * Full adder built from two half adders and an OR gate: sum = a XOR b XOR cin,
 * carry out = (a AND b) OR (cin AND (a XOR b)).
 *
 * <p>Input bus: [0] = a, [1] = b, [2] = carry in. Output bus: [0] = sum, [1] = carry out.
 */
public class FullAdder implements IComponent {

    private static final IRange ALLOWED_INPUT_BUS_WIDTH = new SimpleRange(3, 3);

    private static final IRange ALLOWED_OUTPUT_BUS_WIDTH = new SimpleRange(2, 2);

    private final IBus inputBus;

    private final IBus outputBus;

    public FullAdder(Simulator sim, IBus inputBus) {
        Validation.validateInputWidth(ALLOWED_INPUT_BUS_WIDTH, inputBus);
        this.inputBus = inputBus;
        HalfAdder first = new HalfAdder(sim, new Bus(inputBus.getSignal(0), inputBus.getSignal(1)));
        HalfAdder second = new HalfAdder(sim, new Bus(first.getSum(), inputBus.getSignal(2)));
        this.outputBus = new Bus(second.getSum(), Gates.or(sim, first.getCarry(), second.getCarry()));
    }

    public ISignal getSum() {
        return this.outputBus.getSignal(0);
    }

    public ISignal getCarryOut() {
        return this.outputBus.getSignal(1);
    }

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
