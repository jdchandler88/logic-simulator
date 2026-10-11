package com.darchan.physical.comp;

import com.darchan.physical.validation.Validation;
import com.darchan.physical.signal.*;
import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.SimpleRange;
import com.darchan.physical.sim.Simulator;
import com.darchan.physical.sim.StateElement;

import java.util.ArrayList;
import java.util.List;

/**
 * Class representing N-length flip-flop. This can be used as a register
 *
 * <p>A register is one {@link StateElement} of its width (its flip-flops are not registered separately), named as
 * given or automatically {@code reg<n>}; bit {@code i} is the flip-flop on input/output bit {@code i}.
 */
public class Register implements IComponent, StateElement {

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
     * storage-element name
     */
    private final String name;

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
        this(sim, clk, inputBus, writeEnable, initialValue, null);
    }

    /**
     * Create a named register (see {@link StateElement} for names).
     * @param sim simulator
     * @param clk input clock
     * @param inputBus input bus
     * @param writeEnable write enable shared by all bits; {@code null} to load on every rising edge
     * @param initialValue initial value for each flip-flop
     * @param name storage-element name; {@code null} for an automatic {@code reg<n>}
     */
    public Register(Simulator sim, ISignal clk, IBus inputBus, ISignal writeEnable, boolean initialValue,
                    String name) {
        Validation.validateInputWidth(ALLOWABLE_BUS_WIDTH, inputBus);
        if (inputBus.getSignals().size() > 64) {
            throw new IllegalArgumentException("a register has at most 64 bits: " + inputBus.getSignals().size());
        }
        this.name = name != null ? name : sim.uniqueName("reg");
        this.clk = clk;
        this.inputBus = inputBus;
        //create 'register' of flip flops. direct each input signal on this component's bus to each flipflop
        //also, direct each FF's output signal to this component's output bus
        this.flipFlops = new ArrayList<>();
        List<ISignal> outputSignals = new ArrayList<>();
        for (int i=0; i<this.inputBus.getSignals().size(); i++) {
            FlipFlop ff = new FlipFlop(sim, clk, new Bus(inputBus.getSignals().get(i)), writeEnable, initialValue,
                    this.name + "_" + i, false);
            flipFlops.add(ff);
            outputSignals.add(ff.getOutputBus().getSignals().get(0));
        }
        //create output bus from flipflops
        this.outputBus = new Bus(outputSignals.toArray(new ISignal[0]));
        sim.register(this);
    }

    @Override
    public String name() {
        return this.name;
    }

    @Override
    public int width() {
        return this.flipFlops.size();
    }

    @Override
    public LogicValue get(int bit) {
        return this.flipFlops.get(bit).get(0);
    }

    @Override
    public void force(int bit, LogicValue value) {
        this.flipFlops.get(bit).force(0, value);
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
