package com.darchan.elements.impl.comp;

import com.darchan.elements.iface.*;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.SimpleRange;
import com.darchan.elements.impl.SimpleSignal;
import com.darchan.sim.Simulator;

/**
 * Positive-edge-triggered D flip-flop. On a rising clock edge it samples its input as it was in the delta cycle of
 * the edge and drives the sampled value onto its output in the next delta cycle, so flip-flops sharing a clock all
 * sample their pre-edge inputs (shift registers and counters work).
 *
 * <p>Keep logic off the clock path: a clock that passes through a gate arrives a delta cycle later than the raw
 * clock and creates a race with flip-flops on the raw clock (as in any delta-cycle HDL simulator). Use a
 * write-enable instead of gating the clock.
 */
public class FlipFlop implements IComponent {

    /**
     * both input/output buses should be width 1
     */
    private static final IRange ALLOWABLE_BUS_WIDTH = new SimpleRange(1, 1);

    /**
     * input bus
     */
    private final IBus inputBus;

    /**
     * clock
     */
    private final ISignal clk;

    /**
     * signal on the output bus
     */
    private final SimpleSignal outputSignal;

    /**
     * output bus
     */
    private final IBus outputBus;

    /**
     * Creates flip-flop with provided inputs
     * @param sim simulator that owns this flip-flop's output
     * @param clk clock
     * @param inputBus input signals
     * @param initialValue output value before the first clock edge
     */
    public FlipFlop(Simulator sim, ISignal clk, IBus inputBus, boolean initialValue) {
        this.clk = clk;
        this.inputBus = inputBus;
        //initialize outputs
        this.outputSignal = new SimpleSignal(sim, initialValue);
        this.outputBus = new Bus(this.outputSignal);
        //changes occur on clock change (pos-edge)
        this.clk.addSignalChangeListener(new PosEdgeClockListener());
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

    /**
     * implementation of signal change listener that propagates input to output ONLY WHEN the
     * input clock goes from low->high
     */
    private class PosEdgeClockListener implements ISignalChangeListener {

        @Override
        public void signalChanged(boolean oldValue, boolean newValue) {
            // only act on pos-edge clock
            if (!oldValue && newValue) {
                //latch the input value to the output
                boolean inputValue = FlipFlop.this.inputBus.getSignals().get(0).isOn();
                FlipFlop.this.outputSignal.setOn(inputValue);
            }
        }
    }



}
