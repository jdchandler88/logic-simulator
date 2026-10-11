package com.darchan.elements.impl.comp;

import com.darchan.validation.Validation;
import com.darchan.elements.iface.*;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.LogicSignal;
import com.darchan.elements.impl.SimpleRange;
import com.darchan.elements.impl.SimpleSignal;
import com.darchan.elements.impl.logic.AndGate;
import com.darchan.elements.impl.logic.InverterLogicUnit;
import com.darchan.elements.impl.logic.OrGate;
import com.darchan.sim.Simulator;

/**
 * Positive-edge-triggered D flip-flop. On a rising clock edge it samples its input as it was in the delta cycle of
 * the edge and drives the sampled value onto its output in the next delta cycle, so flip-flops sharing a clock all
 * sample their pre-edge inputs (shift registers and counters work).
 *
 * <p>Optionally with a write enable: the flip-flop then loads D on a rising edge only while the enable is high, and
 * otherwise reloads its own output. This is built from gates in front of the D input
 * ({@code D' = (EN AND D) OR (NOT EN AND Q)}), not by gating the clock.
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
     * write enable, or null if the flip-flop loads on every rising edge
     */
    private final ISignal writeEnable;

    /**
     * the signal sampled on a rising edge: D, or the write-enable multiplexer's output
     */
    private final ISignal sampled;

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
        this(sim, clk, inputBus, null, initialValue);
    }

    /**
     * Creates flip-flop with a write enable
     * @param sim simulator that owns this flip-flop's output
     * @param clk clock
     * @param inputBus input signals (D)
     * @param writeEnable load D on a rising edge only while this is high; {@code null} for no enable
     * @param initialValue output value before the first clock edge
     */
    public FlipFlop(Simulator sim, ISignal clk, IBus inputBus, ISignal writeEnable, boolean initialValue) {
        Validation.validateInputWidth(ALLOWABLE_BUS_WIDTH, inputBus);
        this.clk = clk;
        this.inputBus = inputBus;
        this.writeEnable = writeEnable;
        //initialize outputs
        this.outputSignal = new SimpleSignal(sim, initialValue);
        this.outputBus = new Bus(this.outputSignal);
        ISignal d = inputBus.getSignal(0);
        if (writeEnable == null) {
            this.sampled = d;
        } else {
            //D' = (EN AND D) OR (NOT EN AND Q)
            ISignal notEnable = new LogicSignal(sim, new Bus(writeEnable), new InverterLogicUnit());
            ISignal load = new LogicSignal(sim, new Bus(writeEnable, d), new AndGate());
            ISignal hold = new LogicSignal(sim, new Bus(notEnable, this.outputSignal), new AndGate());
            this.sampled = new LogicSignal(sim, new Bus(load, hold), new OrGate());
        }
        //changes occur on clock change (pos-edge)
        this.clk.addSignalChangeListener(new PosEdgeClockListener());
    }

    /**
     * @return the write enable, or {@code null} if this flip-flop has none
     */
    public ISignal getWriteEnable() {
        return this.writeEnable;
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
     * input clock goes from low->high. A rising edge is any change to 1 from a value that is not 1 (0, Z or X).
     * The sampled value is copied as-is, so Z or X data is captured as Z or X (with a write enable, D passes
     * through gates first, which read Z/X as 0).
     */
    private class PosEdgeClockListener implements ISignalChangeListener {

        @Override
        public void signalChanged(boolean oldValue, boolean newValue) {
            // only act on pos-edge clock
            if (!oldValue && newValue) {
                //latch the input value to the output
                FlipFlop.this.outputSignal.setValue(FlipFlop.this.sampled.getValue());
            }
        }
    }

}
