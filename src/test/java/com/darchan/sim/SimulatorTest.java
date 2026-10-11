package com.darchan.sim;

import com.darchan.elements.iface.ISignal;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.LogicSignal;
import com.darchan.elements.impl.SimpleSignal;
import com.darchan.elements.impl.Wire;
import com.darchan.elements.impl.logic.InverterLogicUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SimulatorTest {

    private final Simulator sim = new Simulator();

    @Test
    void scheduledChangeIsNotVisibleUntilSettle() {
        SimpleSignal sig = new SimpleSignal(sim, false);
        sig.setOn(true);
        assertFalse(sig.isOn());
        sim.settle();
        assertTrue(sig.isOn());
    }

    @Test
    void settleWithNothingScheduledIsANoOp() {
        long before = sim.deltaCycle();
        sim.settle();
        assertAll(
                () -> assertEquals(before, sim.deltaCycle()),
                () -> assertEquals(0, sim.now()),
                () -> assertTrue(sim.isQuiescent())
        );
    }

    @Test
    void lastWriteInADeltaWins() {
        SimpleSignal sig = new SimpleSignal(sim, false);
        List<Boolean> seen = new ArrayList<>();
        sig.addSignalChangeListener((o, n) -> seen.add(n));
        sig.setOn(true);
        sig.setOn(false);
        sim.settle();
        //net value never changed, so there is no event
        assertAll(
                () -> assertFalse(sig.isOn()),
                () -> assertEquals(List.of(), seen)
        );
    }

    /**
     * Two processes triggered in the same delta both read the values of that delta; their writes only take
     * effect in the next delta. With immediate (synchronous) updates the second process would see the first
     * one's write and both signals would end up equal.
     */
    @Test
    void processesTriggeredInTheSameDeltaReadPreDeltaValues() {
        SimpleSignal trigger = new SimpleSignal(sim, false);
        SimpleSignal a = new SimpleSignal(sim, false);
        SimpleSignal b = new SimpleSignal(sim, true);
        trigger.addSignalChangeListener((o, n) -> a.setOn(b.isOn()));
        trigger.addSignalChangeListener((o, n) -> b.setOn(a.isOn()));

        trigger.setOn(true);
        sim.settle();

        //swapped
        assertAll(
                () -> assertTrue(a.isOn()),
                () -> assertFalse(b.isOn())
        );
    }

    @Test
    void eachGateLevelTakesOneDeltaCycle() {
        SimpleSignal in = new SimpleSignal(sim, false);
        ISignal s = in;
        int levels = 5;
        for (int i = 0; i < levels; i++) {
            s = new LogicSignal(sim, new Bus(s), new InverterLogicUnit());
        }
        ISignal out = s;
        assertTrue(out.isOn());

        long before = sim.deltaCycle();
        in.setOn(true);
        sim.settle();
        //one delta to commit the input, then one per inverter
        assertAll(
                () -> assertFalse(out.isOn()),
                () -> assertEquals(levels + 1, sim.deltaCycle() - before),
                () -> assertEquals(0, sim.now())
        );
    }

    @Test
    void settleFromInsideAListenerIsRejected() {
        SimpleSignal sig = new SimpleSignal(sim, false);
        sig.addSignalChangeListener((o, n) -> sim.settle());
        sig.setOn(true);
        assertThrows(IllegalStateException.class, sim::settle);
    }

    @Test
    void circuitNeedingMoreDeltasThanTheLimitThrows() {
        Simulator small = new Simulator(3);
        SimpleSignal in = new SimpleSignal(small, false);
        ISignal s = in;
        for (int i = 0; i < 5; i++) {
            s = new LogicSignal(small, new Bus(s), new InverterLogicUnit());
        }
        in.setOn(true);
        OscillationException ex = assertThrows(OscillationException.class, small::settle);
        assertEquals(3, ex.getDeltaCycles());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void maxDeltaCyclesMustBePositive(int max) {
        assertThrows(IllegalArgumentException.class, () -> new Simulator(max));
    }

    @Test
    void negativeDelayIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new LogicSignal(sim, new Bus(new SimpleSignal(sim, false)), new InverterLogicUnit(), -1));
    }

    @Test
    void delayedGateChangesOutputAfterItsDelay() {
        SimpleSignal in = new SimpleSignal(sim, false);
        LogicSignal out = new LogicSignal(sim, new Bus(in), new InverterLogicUnit(), 5);
        assertTrue(out.isOn());

        in.setOn(true);
        sim.runUntil(4);
        assertAll(
                () -> assertEquals(4, sim.now()),
                () -> assertTrue(in.isOn()),
                () -> assertTrue(out.isOn())
        );
        sim.runUntil(5);
        assertAll(
                () -> assertEquals(5, sim.now()),
                () -> assertFalse(out.isOn())
        );
    }

    @Test
    void settleAdvancesTimeUntilQuiescent() {
        SimpleSignal in = new SimpleSignal(sim, false);
        LogicSignal mid = new LogicSignal(sim, new Bus(in), new InverterLogicUnit(), 3);
        LogicSignal out = new LogicSignal(sim, new Bus(mid), new InverterLogicUnit(), 4);
        in.setOn(true);
        sim.settle();
        assertAll(
                () -> assertEquals(7, sim.now()),
                () -> assertTrue(out.isOn()),
                () -> assertTrue(sim.isQuiescent())
        );
    }

    @Test
    void ringOscillatorWithDelayOscillatesInSimulatedTime() {
        //three inverters, delay 1 each: period is 2 * 3 = 6 time units
        Wire loop = new Wire(sim);
        LogicSignal i1 = new LogicSignal(sim, new Bus(loop), new InverterLogicUnit(), 1);
        LogicSignal i2 = new LogicSignal(sim, new Bus(i1), new InverterLogicUnit(), 1);
        LogicSignal i3 = new LogicSignal(sim, new Bus(i2), new InverterLogicUnit(), 1);
        loop.connect(i3);

        List<Long> risingEdges = new ArrayList<>();
        i3.addSignalChangeListener((o, n) -> {
            if (n) {
                risingEdges.add(sim.now());
            }
        });

        sim.runUntil(40);

        assertTrue(risingEdges.size() >= 5, "expected oscillation, edges: " + risingEdges);
        for (int i = 1; i < risingEdges.size(); i++) {
            assertEquals(6, risingEdges.get(i) - risingEdges.get(i - 1), "edges: " + risingEdges);
        }
        //it never becomes quiescent, so settle() must give up rather than hang
        assertThrows(OscillationException.class, sim::settle);
    }

    @Test
    void runUntilInThePastIsRejected() {
        sim.runUntil(10);
        assertThrows(IllegalArgumentException.class, () -> sim.runUntil(9));
    }
}
