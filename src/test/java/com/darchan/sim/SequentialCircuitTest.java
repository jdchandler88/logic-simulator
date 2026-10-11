package com.darchan.sim;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ISignal;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.Clock;
import com.darchan.elements.impl.LogicSignal;
import com.darchan.elements.impl.SimpleSignal;
import com.darchan.elements.impl.Wire;
import com.darchan.elements.impl.comp.FlipFlop;
import com.darchan.elements.impl.logic.AndGate;
import com.darchan.elements.impl.logic.InverterLogicUnit;
import com.darchan.elements.impl.logic.OrGate;
import com.darchan.testutil.Buses;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge-triggered circuits: flip-flops sharing a clock must all sample their pre-edge inputs.
 */
class SequentialCircuitTest {

    private final Simulator sim = new Simulator();

    private final Clock clk = new Clock(sim, false);

    private static boolean out(FlipFlop ff) {
        return ff.getOutputBus().getSignal(0).isOn();
    }

    private void risingEdge() {
        assertFalse(clk.isOn());
        clk.tick();
    }

    private void fallingEdge() {
        assertTrue(clk.isOn());
        clk.tick();
    }

    @Test
    void threeFlipFlopShiftRegisterShiftsOneStagePerEdge() {
        SimpleSignal in = new SimpleSignal(sim, true);
        FlipFlop a = new FlipFlop(sim, clk, new Bus(in), false);
        FlipFlop b = new FlipFlop(sim, clk, a.getOutputBus(), false);
        FlipFlop c = new FlipFlop(sim, clk, b.getOutputBus(), false);

        //input sequence and the expected A,B,C after each rising edge
        boolean[] input = {true, false, true, true, false, false, false};
        String[] expected = {"100", "010", "101", "110", "011", "001", "000"};

        for (int edge = 0; edge < input.length; edge++) {
            in.setOn(input[edge]);
            sim.settle();
            risingEdge();
            String actual = (out(a) ? "1" : "0") + (out(b) ? "1" : "0") + (out(c) ? "1" : "0");
            assertEquals(expected[edge], actual, "after rising edge " + (edge + 1));
            fallingEdge();
            assertEquals(expected[edge], (out(a) ? "1" : "0") + (out(b) ? "1" : "0") + (out(c) ? "1" : "0"),
                    "falling edge must not change state");
        }
    }

    @Test
    void flipFlopSwapsWithItsNeighbourOnTheSameEdge() {
        //two flip-flops feeding each other swap their values every edge (needs pre-edge sampling)
        Wire aIn = new Wire(sim);
        FlipFlop a = new FlipFlop(sim, clk, new Bus(aIn), true);
        FlipFlop b = new FlipFlop(sim, clk, a.getOutputBus(), false);
        aIn.connect(b.getOutputBus().getSignal(0));
        sim.settle();

        for (int edge = 0; edge < 4; edge++) {
            boolean aBefore = out(a);
            boolean bBefore = out(b);
            risingEdge();
            assertEquals(bBefore, out(a));
            assertEquals(aBefore, out(b));
            fallingEdge();
        }
    }

    /**
     * 4-bit synchronous binary counter: q[i]' = q[i] XOR (q[0] AND ... AND q[i-1]), built from AND/OR/NOT gates.
     */
    @Test
    void fourBitSynchronousCounterCounts() {
        int width = 4;
        Wire[] next = new Wire[width];
        FlipFlop[] ffs = new FlipFlop[width];
        ISignal[] q = new ISignal[width];
        for (int i = 0; i < width; i++) {
            next[i] = new Wire(sim);
            ffs[i] = new FlipFlop(sim, clk, new Bus(next[i]), false);
            q[i] = ffs[i].getOutputBus().getSignal(0);
        }
        ISignal carry = null;
        for (int i = 0; i < width; i++) {
            if (i == 0) {
                next[i].connect(not(q[0]));
                carry = q[0];
            } else {
                next[i].connect(xor(q[i], carry));
                carry = and(carry, q[i]);
            }
        }
        sim.settle();
        IBus count = new Bus(q);

        for (int edges = 1; edges <= 40; edges++) {
            risingEdge();
            assertEquals(edges % 16, Buses.toInt(count), "after " + edges + " rising edges");
            fallingEdge();
            assertEquals(edges % 16, Buses.toInt(count), "falling edge must not count");
        }
    }

    private ISignal not(ISignal a) {
        return new LogicSignal(sim, new Bus(a), new InverterLogicUnit());
    }

    private ISignal and(ISignal a, ISignal b) {
        return new LogicSignal(sim, new Bus(a, b), new AndGate());
    }

    private ISignal or(ISignal a, ISignal b) {
        return new LogicSignal(sim, new Bus(a, b), new OrGate());
    }

    private ISignal xor(ISignal a, ISignal b) {
        return or(and(a, not(b)), and(not(a), b));
    }
}
