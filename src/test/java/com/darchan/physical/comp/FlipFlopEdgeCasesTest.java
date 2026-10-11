package com.darchan.physical.comp;

import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.Clock;
import com.darchan.physical.signal.LogicValue;
import com.darchan.physical.signal.SimpleSignal;
import com.darchan.physical.sim.Simulator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlipFlopEdgeCasesTest {

    private final Simulator sim = new Simulator();
    private final Clock clk = new Clock(sim, false);
    private final SimpleSignal d = new SimpleSignal(sim, false);

    @Test
    void aFlipFlopHasExactlyOneBit() {
        FlipFlop ff = new FlipFlop(sim, clk, new Bus(d), false);
        assertEquals(1, ff.width());
        assertEquals(LogicValue.ZERO, ff.get(0));
        assertThrows(IndexOutOfBoundsException.class, () -> ff.get(1));
        assertThrows(IndexOutOfBoundsException.class, () -> ff.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> ff.force(1, LogicValue.ONE));
        assertThrows(IndexOutOfBoundsException.class, () -> ff.force(-1, LogicValue.ONE));
        ff.force(0, LogicValue.ONE);
        sim.settle();
        assertEquals(LogicValue.ONE, ff.get(0));
    }

    @Test
    void theWriteEnableIsExposedWhenThereIsOne() {
        SimpleSignal en = new SimpleSignal(sim, true);
        assertNull(new FlipFlop(sim, clk, new Bus(d), false).getWriteEnable());
        assertSame(en, new FlipFlop(sim, clk, new Bus(d), en, false).getWriteEnable());
    }

    @Test
    void registersAndCountersCheckTheirBitIndexToo() {
        Register reg = new Register(sim, clk, new Bus(d, new SimpleSignal(sim, true)), false);
        assertEquals(2, reg.width());
        assertThrows(IndexOutOfBoundsException.class, () -> reg.get(2));
        assertThrows(IndexOutOfBoundsException.class, () -> reg.force(2, LogicValue.ONE));
        Counter counter = new Counter(sim, clk, 3);
        assertEquals(3, counter.width());
        assertThrows(IndexOutOfBoundsException.class, () -> counter.get(3));
        assertThrows(IndexOutOfBoundsException.class, () -> counter.force(3, LogicValue.ONE));
        assertThrows(IllegalArgumentException.class, () -> new Counter(sim, clk, 0));
        assertThrows(IllegalArgumentException.class, () -> new Counter(sim, clk, 64));
        new Counter(sim, clk, 63);
        new Counter(sim, clk, 1);
    }
}
