package com.darchan.elements.impl;

import com.darchan.sim.Simulator;

/**
 * A directly-settable signal: its state is changed explicitly via {@link #setOn(boolean)}.
 * Used by components internally and handy for driving inputs in tests. Like every write, {@code setOn} is
 * scheduled: the new value is visible after the simulator runs the next delta cycle (e.g. {@code sim.settle()}).
 */
public class SimpleSignal extends AbstractSignal {

    public SimpleSignal(Simulator sim, boolean initialState) {
        super(sim, initialState);
    }

    public void setOn(boolean value) {
        this.setState(value);
    }

}
