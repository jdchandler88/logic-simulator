package com.darchan.physical.comp;

import com.darchan.physical.validation.Validation;
import com.darchan.physical.signal.*;
import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.LogicSignal;
import com.darchan.physical.signal.SimpleRange;
import com.darchan.physical.signal.SimpleSignal;
import com.darchan.physical.logic.AndGate;
import com.darchan.physical.logic.InverterLogicUnit;
import com.darchan.physical.logic.OrGate;
import com.darchan.physical.sim.Simulator;
import com.darchan.physical.sim.StateElement;

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
 *
 * <p>A flip-flop is a {@link StateElement}: it registers itself with its simulator at construction (unless it is
 * part of a {@link Register} or {@link Counter}, which register themselves as one element), under the name given
 * or an automatic {@code ff<n>}. {@link #get}/{@link #force} read and overwrite the stored level.
 */
public class FlipFlop implements IComponent, StateElement {

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
     * storage-element name
     */
    private final String name;

    /**
     * Creates flip-flop with provided inputs
     * @param sim simulator that owns this flip-flop's output
     * @param clk clock
     * @param inputBus input signals
     * @param initialValue output value before the first clock edge
     */
    public FlipFlop(Simulator sim, ISignal clk, IBus inputBus, boolean initialValue) {
        this(sim, clk, inputBus, null, initialValue, null, true);
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
        this(sim, clk, inputBus, writeEnable, initialValue, null, true);
    }

    /**
     * Creates a named flip-flop
     * @param sim simulator that owns this flip-flop's output
     * @param clk clock
     * @param inputBus input signals (D)
     * @param writeEnable load D on a rising edge only while this is high; {@code null} for no enable
     * @param initialValue output value before the first clock edge
     * @param name storage-element name (see {@link StateElement}); {@code null} for an automatic {@code ff<n>}
     */
    public FlipFlop(Simulator sim, ISignal clk, IBus inputBus, ISignal writeEnable, boolean initialValue,
                    String name) {
        this(sim, clk, inputBus, writeEnable, initialValue, name, true);
    }

    /**
     * @param name     storage-element name; {@code null} for an automatic one (only when registering)
     * @param register register with the simulator; false for a bit of a component that registers itself
     */
    FlipFlop(Simulator sim, ISignal clk, IBus inputBus, ISignal writeEnable, boolean initialValue, String name,
             boolean register) {
        Validation.validateInputWidth(ALLOWABLE_BUS_WIDTH, inputBus);
        this.clk = clk;
        this.inputBus = inputBus;
        this.writeEnable = writeEnable;
        this.name = name != null ? name : register ? sim.uniqueName("ff") : "ff";
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
        if (register) {
            sim.register(this);
        }
    }

    @Override
    public String name() {
        return this.name;
    }

    /** 1 */
    @Override
    public int width() {
        return 1;
    }

    /** the stored level (the output Q) */
    @Override
    public LogicValue get(int bit) {
        checkBit(bit);
        return this.outputSignal.getValue();
    }

    /** schedules {@code value} onto Q; visible after the simulator runs */
    @Override
    public void force(int bit, LogicValue value) {
        checkBit(bit);
        this.outputSignal.setValue(value);
    }

    private static void checkBit(int bit) {
        if (bit != 0) {
            throw new IndexOutOfBoundsException("a flip-flop has one bit: " + bit);
        }
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
