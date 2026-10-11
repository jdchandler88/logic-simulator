package com.darchan.physical.sim;

import com.darchan.physical.comp.Counter;
import com.darchan.physical.signal.Clock;
import com.darchan.physical.signal.SimpleSignal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SimulatorStatsTest {

    @Test
    void countsRunsDeltasAndEvents() {
        Simulator sim = new Simulator();
        Clock clk = new Clock(sim, false);
        new Counter(sim, clk, 4);
        sim.settle();
        sim.resetStats();
        assertEquals(new SimulatorStats(0, 0, 0, 0, 0, 0, 0, 0, 0), sim.stats());
        for (int i = 0; i < 32; i++) {
            clk.tick();
        }
        SimulatorStats s = sim.stats();
        assertEquals(32, s.runs());
        assertTrue(s.minDeltasPerRun() >= 1);
        assertEquals(1, s.minDeltasPerRun(), "a falling edge changes only the clock");
        assertTrue(s.maxDeltasPerRun() > 3, "a rising edge ripples through the next-state logic: " + s);
        assertTrue(s.events() >= s.runs());
        assertTrue(s.updates() >= s.events());
        assertEquals(0, s.timeSteps());
        assertTrue(s.maxEventsPerRun() > s.minEventsPerRun());
        assertEquals((double) s.deltaCycles() / 32, s.meanDeltasPerRun(), 1e-9);
        String report = s.report();
        assertTrue(report.startsWith("simulator: 32 run(s) (settle/runUntil), 0 time step(s)"), report);
        assertTrue(report.contains("delta cycles: " + s.deltaCycles() + " total, per run min 1 / mean"), report);
        assertTrue(sim.toString().contains("events:"), sim.toString());
    }

    @Test
    void timeStepsAndObservers() {
        Simulator sim = new Simulator();
        SimpleSignal a = new SimpleSignal(sim, false);
        List<String> seen = new ArrayList<>();
        SimulationObserver o = new SimulationObserver() {
            @Override
            public void deltaCompleted(Simulator s) {
                seen.add("delta " + s.deltaCycle());
            }

            @Override
            public void timeAdvancing(Simulator s, long from, long to) {
                seen.add("time " + from + "->" + to);
            }

            @Override
            public void runCompleted(Simulator s) {
                seen.add("done at " + s.now());
            }
        };
        sim.addObserver(o);
        sim.scheduleAfter(3, () -> a.setOn(true));
        sim.settle();
        assertEquals(List.of("time 0->3", "delta 1", "done at 3"), seen);
        seen.clear();
        sim.runUntil(10);
        assertEquals(List.of("time 3->10", "done at 10"), seen);
        assertEquals(2, sim.stats().timeSteps());
        sim.removeObserver(o);
        seen.clear();
        a.setOn(false);
        sim.settle();
        assertTrue(seen.isEmpty());
    }
}
