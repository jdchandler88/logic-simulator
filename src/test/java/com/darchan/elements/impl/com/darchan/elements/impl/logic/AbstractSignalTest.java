package com.darchan.elements.impl.com.darchan.elements.impl.logic;

import com.darchan.elements.impl.AbstractSignal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AbstractSignalTest {

    @Test
    public void initialFalseStateShouldBeReturned() {
        AbstractSignal sig = new AbstractSignal(false) {};
        assertFalse(sig.isOn());
    }

    @Test
    public void initialTrueStateShouldBeReturned() {
        AbstractSignal sig = new AbstractSignal(true) {};
        assertTrue(sig.isOn());
    }

}
