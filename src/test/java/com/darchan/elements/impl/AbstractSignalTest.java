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
        cut.setState(!state);

        assertAll(
                () -> assertEquals(1, testListener.getNumInvokations()),
                () -> assertFalse(testListener.getOldValue()),
                () -> assertTrue(testListener.getNewValue())
        );
    }

    @Test
    public void listenerShouldBeNotifiedOfStateChangeFromTrueToFalse() {
        boolean state = true;
        AbstractSignal cut = new AbstractSignal(state) {
        };
        TestListener testListener = new TestListener();
        cut.addSignalChangeListener(testListener);
        cut.setState(!state);

        assertAll(
                () -> assertEquals(1, testListener.getNumInvokations()),
                () -> assertTrue(testListener.getOldValue()),
                () -> assertFalse(testListener.getNewValue())
        );
    }

    @Test
    public void listenerShouldNotBeNotifiedIfStateDoesNotChange() {
        boolean state = false;
        AbstractSignal cut = new AbstractSignal(state) {
        };
        TestListener testListener = new TestListener();
        cut.addSignalChangeListener(testListener);
        cut.setState(state);
        assertAll(
                () -> assertEquals(0, testListener.getNumInvokations())
        );
    }

    @Test
    public void listenerShouldNotBeNotifiedOfStateChangeAfterItWasRemoved() {
        boolean state = false;
        AbstractSignal cut = new AbstractSignal(state) {
        };
        TestListener testListener = new TestListener();
        cut.addSignalChangeListener(testListener);
        cut.removeSignalChangeListener(testListener);
        cut.setState(!state);

        assertAll(
                () -> assertEquals(0, testListener.getNumInvokations())
        );
    }

    @Test
    public void listenerShouldOnlyBeNotifiedOnceEvenIfAddedMultipleTimes() {
        boolean state = false;
        AbstractSignal cut = new AbstractSignal(state) {
        };
        TestListener testListener = new TestListener();
        cut.addSignalChangeListener(testListener);
        cut.addSignalChangeListener(testListener);
        cut.setState(!state);

        assertAll(
                () -> assertEquals(1, testListener.getNumInvokations()),
                () -> assertFalse(testListener.getOldValue()),
                () -> assertTrue(testListener.getNewValue())
        );
    }

    private static class TestListener implements ISignalChangeListener {
        int numInvokations = 0;
        boolean oldValue;
        boolean newValue;
        @Override
        public void signalChanged(boolean oldValue, boolean newValue) {
            this.numInvokations++;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }
        public int getNumInvokations() {
            return this.numInvokations;
        }
        public boolean getOldValue() {
            return this.oldValue;
        }
        public boolean getNewValue() {
            return this.newValue;
        }
    }

}
