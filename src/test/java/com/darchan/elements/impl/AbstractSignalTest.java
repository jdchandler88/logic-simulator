package com.darchan.elements.impl;

import com.darchan.elements.iface.ISignalChangeListener;
import com.darchan.sim.Simulator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AbstractSignalTest {

    private final Simulator sim = new Simulator();

    @Test
    public void initialFalseStateShouldBeReturned() {
        AbstractSignal sig = new AbstractSignal(sim, false) {};
        assertFalse(sig.isOn());
    }

    @Test
    public void initialTrueStateShouldBeReturned() {
        AbstractSignal sig = new AbstractSignal(sim, true) {};
        assertTrue(sig.isOn());
    }

    @Test
    public void listenerShouldBeNotifiedOfStateChangeFromFalseToTrue() {
        boolean state = false;
        AbstractSignal cut = new AbstractSignal(sim, state) {
        };
        TestListener testListener = new TestListener();
        cut.addSignalChangeListener(testListener);
        cut.setState(!state);
        sim.settle();

        assertAll(
                () -> assertEquals(1, testListener.getNumInvokations()),
                () -> assertFalse(testListener.getOldValue()),
                () -> assertTrue(testListener.getNewValue())
        );
    }

    @Test
    public void listenerShouldBeNotifiedOfStateChangeFromTrueToFalse() {
        boolean state = true;
        AbstractSignal cut = new AbstractSignal(sim, state) {
        };
        TestListener testListener = new TestListener();
        cut.addSignalChangeListener(testListener);
        cut.setState(!state);
        sim.settle();

        assertAll(
                () -> assertEquals(1, testListener.getNumInvokations()),
                () -> assertTrue(testListener.getOldValue()),
                () -> assertFalse(testListener.getNewValue())
        );
    }

    @Test
    public void listenerShouldNotBeNotifiedIfStateDoesNotChange() {
        boolean state = false;
        AbstractSignal cut = new AbstractSignal(sim, state) {
        };
        TestListener testListener = new TestListener();
        cut.addSignalChangeListener(testListener);
        cut.setState(state);
        sim.settle();
        assertAll(
                () -> assertEquals(0, testListener.getNumInvokations())
        );
    }

    @Test
    public void listenerShouldNotBeNotifiedOfStateChangeAfterItWasRemoved() {
        boolean state = false;
        AbstractSignal cut = new AbstractSignal(sim, state) {
        };
        TestListener testListener = new TestListener();
        cut.addSignalChangeListener(testListener);
        cut.removeSignalChangeListener(testListener);
        cut.setState(!state);
        sim.settle();

        assertAll(
                () -> assertEquals(0, testListener.getNumInvokations())
        );
    }

    @Test
    public void listenerShouldOnlyBeNotifiedOnceEvenIfAddedMultipleTimes() {
        boolean state = false;
        AbstractSignal cut = new AbstractSignal(sim, state) {
        };
        TestListener testListener = new TestListener();
        cut.addSignalChangeListener(testListener);
        cut.addSignalChangeListener(testListener);
        cut.setState(!state);
        sim.settle();

        assertAll(
                () -> assertEquals(1, testListener.getNumInvokations()),
                () -> assertFalse(testListener.getOldValue()),
                () -> assertTrue(testListener.getNewValue())
        );
    }

    @Test
    public void listenerShouldNotBeNotifiedBeforeTheSimulatorSettles() {
        AbstractSignal cut = new AbstractSignal(sim, false) {
        };
        TestListener testListener = new TestListener();
        cut.addSignalChangeListener(testListener);
        cut.setState(true);
        assertAll(
                () -> assertFalse(cut.isOn()),
                () -> assertEquals(0, testListener.getNumInvokations())
        );
    }

    @Test
    public void listenersMayBeAddedAndRemovedDuringNotification() {
        AbstractSignal cut = new AbstractSignal(sim, false) {
        };
        TestListener late = new TestListener();
        TestListener[] other = {new TestListener()};
        ISignalChangeListener selfRemoving = new ISignalChangeListener() {
            @Override
            public void signalChanged(boolean oldValue, boolean newValue) {
                cut.removeSignalChangeListener(this);
                cut.removeSignalChangeListener(other[0]);
                cut.addSignalChangeListener(late);
            }
        };
        cut.addSignalChangeListener(selfRemoving);
        cut.addSignalChangeListener(other[0]);

        cut.setState(true);
        assertDoesNotThrow(sim::settle);
        //the set of listeners is snapshotted per event: 'late' only hears the next event
        assertEquals(0, late.getNumInvokations());

        cut.setState(false);
        sim.settle();
        assertAll(
                () -> assertEquals(1, late.getNumInvokations()),
                () -> assertEquals(1, other[0].getNumInvokations())
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
