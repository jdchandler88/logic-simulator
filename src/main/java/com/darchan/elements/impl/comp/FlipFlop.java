package com.darchan.elements.impl.comp;

import com.darchan.elements.iface.*;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.BusWidthAllowableRange;
import com.darchan.elements.impl.SimpleSignal;

public class FlipFlop implements IComponent {

    /**
     * both input/output buses should be width 1
     */
    private static final IRange ALLOWABLE_BUS_WIDTH = new BusWidthAllowableRange(1, 1);

    /**
     * input bus
     */
    private final IBus inputBus;

    /**
     * clock
     */
    private final IClock clk;

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
     * @param clk clock
     * @param inputBus input signals
     */
    public FlipFlop(IClock clk, IBus inputBus, boolean initialValue) {
        this.clk = clk;
        this.inputBus = inputBus;
        //initialize outputs
        this.outputSignal = new SimpleSignal(initialValue);
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
