package com.darchan.physical.comp;

import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.IComponent;
import com.darchan.physical.signal.IRange;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.SimpleRange;
import com.darchan.physical.signal.Wire;
import com.darchan.physical.sim.Simulator;

import static com.darchan.physical.logic.Gates.and;
import static com.darchan.physical.logic.Gates.not;
import static com.darchan.physical.logic.Gates.xor;

/**
 * N-bit synchronous binary up-counter: one {@link FlipFlop} per bit, all on the same clock, with next-state logic
 * {@code q[i]' = q[i] XOR (q[0] AND ... AND q[i-1])} built from gates (a ripple of AND gates for the carries). It
 * counts once per rising clock edge, wrapping from {@code 2^N - 1} to 0, and starts at 0. Call
 * {@code sim.settle()} after building it, before the first clock edge, so the next-state logic is valid.
 *
 * <p>Input bus: none (empty). Output bus: the count, bit 0 = LSB. {@link #getNextState()} exposes the
 * combinational next-state bus (handy in waveforms to see the carry ripple settle).
 */
public class Counter implements IComponent {

    private static final IRange ALLOWED_WIDTH = new SimpleRange(1, 63);

    private final IBus outputBus;

    private final IBus nextState;

    public Counter(Simulator sim, ISignal clk, int width) {
        if (width < ALLOWED_WIDTH.getMin() || width > ALLOWED_WIDTH.getMax()) {
            throw new IllegalArgumentException("width must be 1..63: " + width);
        }
        Wire[] next = new Wire[width];
        ISignal[] q = new ISignal[width];
        for (int i = 0; i < width; i++) {
            next[i] = new Wire(sim);
            q[i] = new FlipFlop(sim, clk, new Bus(next[i]), false).getOutputBus().getSignal(0);
        }
        ISignal carry = null;
        for (int i = 0; i < width; i++) {
            if (i == 0) {
                next[i].connect(not(sim, q[0]));
                carry = q[0];
            } else {
                next[i].connect(xor(sim, q[i], carry));
                carry = and(sim, carry, q[i]);
            }
        }
        this.outputBus = new Bus(q);
        this.nextState = new Bus(next);
    }

    public IBus getNextState() {
        return nextState;
    }

    @Override
    public IBus getInputBus() {
        return new Bus();
    }

    @Override
    public IRange getAllowedInputBusWidth() {
        return new SimpleRange(0, 0);
    }

    @Override
    public IBus getOutputBus() {
        return outputBus;
    }

    @Override
    public IRange getAllowedOutputBusWidth() {
        return ALLOWED_WIDTH;
    }
}
