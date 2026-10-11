package com.darchan.physical.sim;

import com.darchan.physical.comp.i8080.RegisterFile;
import com.darchan.physical.logic.NandGate;
import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.Clock;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.comp.Inverter;
import com.darchan.physical.signal.LogicSignal;
import com.darchan.physical.signal.LogicValue;
import com.darchan.physical.signal.SimpleSignal;
import com.darchan.physical.signal.Wire;
import com.darchan.testutil.Buses;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Boundaries and bookkeeping of the simulator core that the behavioural tests do not pin down. */
class SimulatorEdgeCasesTest {

    private final Simulator sim = new Simulator();

    /** a minimal storage element */
    private record Element(String name, int width) implements StateElement {
        @Override
        public LogicValue get(int bit) {
            return LogicValue.ZERO;
        }

        @Override
        public void force(int bit, LogicValue value) {
        }
    }

    // ---- construction ---------------------------------------------------------------------------------------

    @Test
    void theDeltaLimitMustBePositiveAndIsReported() {
        assertEquals(1, new Simulator(1).getMaxDeltaCycles(), "1 is the smallest limit");
        assertEquals(10_000, new Simulator().getMaxDeltaCycles());
        assertEquals(10_000, Simulator.DEFAULT_MAX_DELTA_CYCLES);
        assertThrows(IllegalArgumentException.class, () -> new Simulator(0));
        assertThrows(IllegalArgumentException.class, () -> new Simulator(-5));
    }

    @Test
    void aLimitOfOneStillSettlesAOneDeltaChangeButNotTwo() {
        Simulator tight = new Simulator(1);
        SimpleSignal in = new SimpleSignal(tight, false);
        in.setOn(true);
        tight.settle(); // one delta
        assertTrue(in.isOn());
        // an inverter reacts one delta after its input: two deltas in all
        SimpleSignal a = new SimpleSignal(tight, false);
        new Inverter(tight, new Bus(a));
        a.setOn(true);
        assertThrows(OscillationException.class, tight::settle);
    }

    // ---- observers ------------------------------------------------------------------------------------------

    @Test
    void aNullObserverIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> sim.addObserver(null));
    }

    @Test
    void removingAnObserverLeavesTheOthers() {
        List<String> calls = new ArrayList<>();
        SimulationObserver first = new SimulationObserver() {
            @Override
            public void runCompleted(Simulator s) {
                calls.add("first");
            }
        };
        SimulationObserver second = new SimulationObserver() {
            @Override
            public void runCompleted(Simulator s) {
                calls.add("second");
            }
        };
        sim.addObserver(first);
        sim.addObserver(second);
        sim.settle();
        assertEquals(List.of("first", "second"), calls);
        calls.clear();
        sim.removeObserver(first);
        sim.settle();
        assertEquals(List.of("second"), calls);
        sim.removeObserver(first); // removing twice is harmless
        calls.clear();
        sim.settle();
        assertEquals(List.of("second"), calls);
    }

    // ---- storage element registry -------------------------------------------------------------------------------

    @Test
    void aStorageElementMayNotBeCalledZOrXInAnySegmentPosition() {
        for (String bad : new String[]{"Z", "X", "a.Z", "a.b.X"}) {
            assertThrows(IllegalArgumentException.class, () -> sim.register(new Element(bad, 1)), bad);
        }
        sim.register(new Element("ZZ", 1)); // only a whole last segment is reserved
        sim.register(new Element("Z.a", 1));
        sim.register(new Element("a.Xy", 1));
        assertEquals(3, sim.stateElements().size());
    }

    @Test
    void aStorageElementHasOneToSixtyFourBits() {
        assertThrows(IllegalArgumentException.class, () -> sim.register(new Element("w0", 0)));
        assertThrows(IllegalArgumentException.class, () -> sim.register(new Element("w65", 65)));
        sim.register(new Element("w1", 1));
        sim.register(new Element("w64", 64));
        assertEquals(2, sim.stateElements().size());
        assertThrows(IllegalArgumentException.class, () -> sim.register(new Element("w1", 1)), "duplicate name");
    }

    @Test
    void uniqueNamesAreValidatedAndReservedSoTheyAreNotHandedOutTwice() {
        assertThrows(IllegalArgumentException.class, () -> sim.uniqueName("a b"));
        assertThrows(IllegalArgumentException.class, () -> sim.uniqueName(""));
        assertEquals("ff0", sim.uniqueName("ff"));
        assertEquals("ff1", sim.uniqueName("ff"), "the first name is reserved even though nothing registered under it");
        assertEquals("reg0", sim.uniqueName("reg"));
    }

    @Test
    void anAutomaticNameSkipsTheScopeOfAnExplicitlyNamedComponent() {
        Clock clk = new Clock(sim, false);
        // "regfile0.L" ... exist, so "regfile0" is taken as a scope even though no element is called regfile0
        new RegisterFile(sim, clk, Buses.simpleBus(sim, 16, 0), "regfile0");
        assertEquals("regfile1", sim.uniqueName("regfile"));
        // every enclosing scope is claimed, not only the outermost: "a.b0" is a scope of "a.b0.file.L"
        new RegisterFile(sim, clk, Buses.simpleBus(sim, 16, 0), "a.b0.file");
        assertEquals("a.b1", sim.uniqueName("a.b"));
        assertEquals("a.b2", sim.uniqueName("a.b"));
    }

    // ---- time --------------------------------------------------------------------------------------------------

    @Test
    void quiescenceCoversBothTheCurrentDeltaAndFutureEvents() {
        assertTrue(sim.isQuiescent());
        SimpleSignal s = new SimpleSignal(sim, false);
        s.setOn(true);
        assertFalse(sim.isQuiescent(), "a write is pending");
        sim.settle();
        assertTrue(sim.isQuiescent());
        sim.scheduleAfter(5, () -> s.setOn(false));
        assertFalse(sim.isQuiescent(), "a delayed event is pending");
        sim.runUntil(4);
        assertFalse(sim.isQuiescent());
        sim.runUntil(5);
        assertTrue(sim.isQuiescent(), "the delayed write at time 5 has been committed too");
        assertFalse(s.isOn());
    }

    @Test
    void aDelayOfAtLeastOneIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> sim.scheduleAfter(0, () -> { }));
        assertThrows(IllegalArgumentException.class, () -> sim.scheduleAfter(-3, () -> { }));
        List<Long> times = new ArrayList<>();
        sim.scheduleAfter(1, () -> times.add(sim.now()));
        sim.settle();
        assertEquals(List.of(1L), times);
    }

    @Test
    void runUntilTheCurrentTimeIsAllowedAndAdvancesNothing() {
        List<String> seen = new ArrayList<>();
        sim.addObserver(new SimulationObserver() {
            @Override
            public void timeAdvancing(Simulator s, long from, long to) {
                seen.add(from + "->" + to);
            }
        });
        sim.runUntil(0);
        sim.runUntil(7);
        sim.runUntil(7);
        assertEquals(List.of("0->7"), seen, "only a real advance is reported");
        assertEquals(7, sim.now());
        assertEquals(1, sim.stats().timeSteps());
        assertEquals(3, sim.stats().runs());
    }

    @Test
    void anEventExactlyAtTheEndTimeRunsButOneAfterItDoesNot() {
        List<String> ran = new ArrayList<>();
        sim.scheduleAfter(5, () -> ran.add("at 5"));
        sim.scheduleAfter(6, () -> ran.add("at 6"));
        sim.runUntil(5);
        assertEquals(List.of("at 5"), ran);
        sim.runUntil(6);
        assertEquals(List.of("at 5", "at 6"), ran);
    }

    @Test
    void aZeroDelayLoopInRunUntilIsReportedAtTheTimeItHappens() {
        SimpleSignal enable = new SimpleSignal(sim, false);
        Wire loop = new Wire(sim);
        ISignal nand = new LogicSignal(sim, new Bus(enable, loop), new NandGate());
        loop.connect(nand);
        sim.runUntil(3);
        sim.scheduleAfter(2, () -> enable.setOn(true));
        OscillationException e = assertThrows(OscillationException.class, () -> sim.runUntil(10));
        assertEquals(5, e.getTime());
        assertEquals(Simulator.DEFAULT_MAX_DELTA_CYCLES, e.getDeltaCycles());
        assertFalse(sim.isQuiescent(), "the remaining events stay scheduled");
    }

    @Test
    void theSimulatorIsNotReentrantEvenFromRunUntil() {
        SimpleSignal s = new SimpleSignal(sim, false);
        s.addSignalChangeListener((o, n) -> sim.runUntil(sim.now() + 1));
        s.setOn(true);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> sim.runUntil(2));
        assertTrue(e.getMessage().contains("re-entrant"), e.getMessage());
        // and the simulator is usable again afterwards
        Simulator other = new Simulator();
        other.runUntil(1);
        assertEquals(1, other.now());
    }

    @Test
    void settleIsNotReentrantEitherAndRecoversAfterTheFailure() {
        SimpleSignal s = new SimpleSignal(sim, false);
        s.addSignalChangeListener((o, n) -> sim.settle());
        s.setOn(true);
        assertThrows(IllegalStateException.class, sim::settle);
    }

    // ---- statistics per run -------------------------------------------------------------------------------------

    @Test
    void perRunStatisticsCoverOnlyThatRun() {
        SimpleSignal a = new SimpleSignal(sim, false);
        new Inverter(sim, new Bus(a)); // a change on a takes two deltas and two events
        sim.resetStats();
        a.setOn(true);
        sim.settle(); // run 1: 2 deltas, 2 events
        sim.runUntil(5); // run 2: nothing happens
        SimpleSignal b = new SimpleSignal(sim, false);
        b.setOn(true);
        sim.settle(); // run 3: 1 delta, 1 event
        SimulatorStats s = sim.stats();
        assertEquals(3, s.runs());
        assertEquals(3, s.deltaCycles());
        assertEquals(0, s.minDeltasPerRun());
        assertEquals(2, s.maxDeltasPerRun());
        assertEquals(3, s.events());
        assertEquals(0, s.minEventsPerRun());
        assertEquals(2, s.maxEventsPerRun());
        assertEquals(1, s.timeSteps());
        sim.resetStats();
        assertEquals(new SimulatorStats(0, 0, 0, 0, 0, 0, 0, 0, 0), sim.stats());
        b.setOn(false);
        sim.settle();
        assertEquals(1, sim.stats().minDeltasPerRun(), "min/max start afresh after a reset");
        assertEquals(1, sim.stats().maxDeltasPerRun());
    }

    @Test
    void statsArithmeticAndReportText() {
        SimulatorStats none = new SimulatorStats(0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(0.0, none.meanDeltasPerRun());
        assertEquals(0.0, none.meanEventsPerRun());
        SimulatorStats s = new SimulatorStats(4, 10, 1, 4, 6, 0, 3, 9, 2);
        assertEquals(2.5, s.meanDeltasPerRun());
        assertEquals(1.5, s.meanEventsPerRun());
        String expected = "simulator: 4 run(s) (settle/runUntil), 2 time step(s)\n"
                + "  delta cycles: 10 total, per run min 1 / mean 2.5 / max 4\n"
                + "  events:       6 total, per run min 0 / mean 1.5 / max 3\n"
                + "  updates:      9 (3 without a value change)";
        assertEquals(expected, s.report());
        assertEquals(expected, s.toString());
        assertTrue(sim.toString().startsWith("Simulator at time 0, delta 0\nsimulator: 0 run(s)"), sim.toString());
        sim.runUntil(4);
        assertTrue(sim.toString().startsWith("Simulator at time 4, delta 0\n"), sim.toString());
    }
}
