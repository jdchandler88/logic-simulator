package com.darchan.elements.impl;

/**
 * A directly-settable signal: its state is changed explicitly via {@link #setOn(boolean)}.
 * Used by components internally and handy for driving inputs in tests.
 */
public class SimpleSignal extends AbstractSignal {


    public SimpleSignal(boolean initialState) {
        super(initialState);
    }

    public void setOn(boolean value) {
        this.setState(value);
    }

}
