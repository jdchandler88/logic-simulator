package com.darchan.elements.impl;

import com.darchan.elements.impl.AbstractSignal;

/**
 * Class used for test. This signal can be used to set values explicitly
 */
public class SimpleSignal extends AbstractSignal {


    public SimpleSignal(boolean initialState) {
        super(initialState);
    }

    public void setOn(boolean value) {
        this.setState(value);
    }

}
