package com.darchan.elements.impl.comp;

import com.darchan.validation.Validation;
import com.darchan.elements.iface.*;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.SimpleRange;
import com.darchan.sim.Simulator;

import java.util.ArrayList;
import java.util.List;

/**
 * Class representing N-length flip-flop. This can be used as a register
 */
public class Register implements IComponent {

    /**
     * allowable bus width. should be at least 2-wide. otherwise, use a flip-flop
     */
    private static final IRange ALLOWABLE_BUS_WIDTH = new SimpleRange(2, Integer.MAX_VALUE);

    /**
     * input clock
     */
    private final ISignal clk;

    /**
     * input bus
     */
    private final IBus inputBus;

    /**
     * output bus
     */
    private final IBus outputBus;

    /**
     * collection of flipflops that compose the register
     */
    private final List<FlipFlop> flipFlops;

    /**
     * Create register of length equal to inputBus width
     * @param sim simulator
     * @param clk input clock
     * @param inputBus input bus
     * @param initialValue initial value for each flip-flopp
     */
    public Register(Simulator sim, ISignal clk, IBus inputBus, boolean initialValue) {
        this(sim, clk, inputBus, null, initialValue);
    }

    /**
     * Create register with a write enable: it loads on a rising clock edge only while {@code writeEnable} is high
     * and holds its value otherwise (see {@link FlipFlop}).
     * @param sim simulator
     * @param clk input clock
     * @param inputBus input bus
     * @param writeEnable write enable shared by all bits; {@code null} to load on every rising edge
     * @param initialValue initial value for each flip-flop
     */
    public Register(Simulator sim, ISignal clk, IBus inputBus, ISignal writeEnable, boolean initialValue) {
        Validation.validateInputWidth(ALLOWABLE_BUS_WIDTH, inputBus);
        this.clk = clk;
        this.inputBus = inputBus;
        //create 'register' of flip flops. direct each input signal on this component's bus to each flipflop
        //also, direct each FF's output signal to this component's output bus
        this.flipFlops = new ArrayList<>();
        List<ISignal> outputSignals = new ArrayList<>();
        for (int i=0; i<this.inputBus.getSignals().size(); i++) {
            FlipFlop ff = new FlipFlop(sim, clk, new Bus(inputBus.getSignals().get(i)), writeEnable, initialValue);
            flipFlops.add(ff);
            outputSignals.add(ff.getOutputBus().getSignals().get(0));
        }
        //create output bus from flipflops
        this.outputBus = new Bus(outputSignals.toArray(new ISignal[0]));
    }

    @Override
    public IBus getInputBus() {
        return this.inputBus;
    }

    @Override
    public IRange getAllowedInputBusWidth() {
        return ALLOWABLE_BUS_WIDTH;
    }

    @Override
    public IBus getOutputBus() {
        return this.outputBus;
    }

    @Override
    public IRange getAllowedOutputBusWidth() {
        return ALLOWABLE_BUS_WIDTH;
    }
}
