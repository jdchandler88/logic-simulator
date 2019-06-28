package com.darchan.elements.impl;

import com.darchan.elements.iface.ISignalChangeListener;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    public void listenerShouldBeNotifiedOfStateChangeFromFalseToTrue() {
        boolean state = false;
        AbstractSignal cut = new AbstractSignal(state) {
        };
        TestListener testListener = new TestListener();
        cut.addSignalChangeListener(testListener);
        cut.setState(true);

        assertAll(
                () -> assertTrue(testListener.isCalled()),
                () -> assertFalse(testListener.getOldValue()),
                () -> assertTrue(testListener.getNewValue())
        );
    }

    @Test
    public void listenerShouldBeNotifiedOfStateChangeFromTrueToFalse() {

    }

    @Test
    public void listenerShouldNotBeNotifiedIfStateDoesNotChange() {

    }

    @Test
    public void listenerShouldNotBeNotifiedOfStateChangeAfterItWasRemoved() {

    }

    private static class TestListener implements ISignalChangeListener {
        boolean isCalled = false;
        boolean oldValue;
        boolean newValue;
        @Override
        public void signalChanged(boolean oldValue, boolean newValue) {
            this.isCalled = true;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }
        public boolean isCalled() {
            return this.isCalled;
        }
        public boolean getOldValue() {
            return this.oldValue;
        }
        public boolean getNewValue() {
            return this.newValue;
        }
    }

}
