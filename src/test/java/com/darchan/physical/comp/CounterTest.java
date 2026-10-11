package com.darchan.physical.comp;

import com.darchan.physical.signal.Clock;
import com.darchan.physical.sim.Simulator;
import com.darchan.testutil.Buses;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CounterTest {

    @Test
    void countsOncePerRisingEdgeAndWraps() {
        Simulator sim = new Simulator();
        Clock clk = new Clock(sim, false);
        Counter counter = new Counter(sim, clk, 4);
        sim.settle();
        assertEquals(0, Buses.toInt(counter.getOutputBus()));
        assertEquals(1, Buses.toInt(counter.getNextState()));
        for (int edges = 1; edges <= 40; edges++) {
            clk.tick();
            assertEquals(edges % 16, Buses.toInt(counter.getOutputBus()), "after " + edges + " rising edges");
            clk.tick();
            assertEquals(edges % 16, Buses.toInt(counter.getOutputBus()), "falling edges do not count");
        }
    }

    @Test
    void widthIsChecked() {
        Simulator sim = new Simulator();
        Clock clk = new Clock(sim, false);
        assertThrows(IllegalArgumentException.class, () -> new Counter(sim, clk, 0));
        assertEquals(1, new Counter(sim, clk, 1).getOutputBus().getSignals().size());
    }
}
