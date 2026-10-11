package com.darchan.physical.sim;

import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.LogicSignal;
import com.darchan.physical.signal.SimpleSignal;
import com.darchan.physical.signal.Wire;
import com.darchan.physical.comp.Inverter;
import com.darchan.physical.logic.InverterLogicUnit;
import com.darchan.physical.logic.OrGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Combinational feedback: stable loops must settle, zero-delay loops that never settle must be reported with an
 * {@link OscillationException} (never a StackOverflowError).
 */
class FeedbackCircuitTest {

    private final Simulator sim = new Simulator();

    /** Cross-coupled NOR SR latch: q = NOR(r, qBar), qBar = NOR(s, q). NOR is built from OR + inverter. */
    private final class SrLatch {
        final SimpleSignal s = new SimpleSignal(sim, false);
        final SimpleSignal r = new SimpleSignal(sim, false);
        final ISignal q;
        final ISignal qBar;

        SrLatch() {
            Wire qBarLoop = new Wire(sim);
            q = nor(r, qBarLoop);
            qBar = nor(s, q);
            qBarLoop.connect(qBar);
            sim.settle();
        }

        private ISignal nor(ISignal a, ISignal b) {
            LogicSignal or = new LogicSignal(sim, new Bus(a, b), new OrGate());
            return new LogicSignal(sim, new Bus(or), new InverterLogicUnit());
        }

        void pulse(SimpleSignal input) {
            input.setOn(true);
            sim.settle();
            input.setOn(false);
            sim.settle();
        }
    }

    @Test
    void srLatchSetResetHold() {
        SrLatch latch = new SrLatch();
        assertNotEquals(latch.q.isOn(), latch.qBar.isOn(), "latch must power up in a valid state");

        //set
        latch.s.setOn(true);
        sim.settle();
        assertAll(() -> assertTrue(latch.q.isOn()), () -> assertFalse(latch.qBar.isOn()));

        //hold after set
        latch.s.setOn(false);
        sim.settle();
        assertAll(() -> assertTrue(latch.q.isOn()), () -> assertFalse(latch.qBar.isOn()));

        //reset
        latch.r.setOn(true);
        sim.settle();
        assertAll(() -> assertFalse(latch.q.isOn()), () -> assertTrue(latch.qBar.isOn()));

        //hold after reset
        latch.r.setOn(false);
        sim.settle();
        assertAll(() -> assertFalse(latch.q.isOn()), () -> assertTrue(latch.qBar.isOn()));

        //and set again with a pulse
        latch.pulse(latch.s);
        assertAll(() -> assertTrue(latch.q.isOn()), () -> assertFalse(latch.qBar.isOn()));
        latch.pulse(latch.r);
        assertAll(() -> assertFalse(latch.q.isOn()), () -> assertTrue(latch.qBar.isOn()));
    }

    /**
     * Releasing S and R in the same instant is the textbook metastable case; with zero gate delay the latch
     * oscillates forever, which the simulator must report.
     */
    @Test
    void srLatchReleasingSetAndResetTogetherOscillates() {
        SrLatch latch = new SrLatch();
        latch.s.setOn(true);
        latch.r.setOn(true);
        sim.settle();
        assertAll(() -> assertFalse(latch.q.isOn()), () -> assertFalse(latch.qBar.isOn()));

        latch.s.setOn(false);
        latch.r.setOn(false);
        assertThrows(OscillationException.class, sim::settle);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 5})
    void zeroDelayRingOscillatorThrowsOscillationException(int inverters) {
        Wire loop = new Wire(sim);
        ISignal s = loop;
        for (int i = 0; i < inverters; i++) {
            s = new Inverter(sim, new Bus(s)).getOutputBus().getSignal(0);
        }
        loop.connect(s);
        OscillationException ex = assertThrows(OscillationException.class, sim::settle);
        assertEquals(Simulator.DEFAULT_MAX_DELTA_CYCLES, ex.getDeltaCycles());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 4})
    void evenRingIsStable(int inverters) {
        Wire loop = new Wire(sim);
        ISignal s = loop;
        ISignal first = null;
        for (int i = 0; i < inverters; i++) {
            s = new Inverter(sim, new Bus(s)).getOutputBus().getSignal(0);
            if (first == null) {
                first = s;
            }
        }
        loop.connect(s);
        sim.settle();
        ISignal firstInverter = first;
        assertAll(
                () -> assertTrue(sim.isQuiescent()),
                () -> assertNotEquals(loop.isOn(), firstInverter.isOn())
        );
    }
}
