package com.darchan.elements.impl.com.darchan;

import com.darchan.elements.impl.AbstractSignal;

/**
 * Class used for test. This signal can be used to set values explicitly
 */
public class TestSignal extends AbstractSignal {


    public TestSignal(boolean initialState) {
        super(initialState);
    }

    public void setValue(boolean value) {
        this.setState(value);
    }

}
