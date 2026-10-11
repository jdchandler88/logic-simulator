package com.darchan.physical.sim;

import com.darchan.physical.comp.BusSelector8By8Bit;
import com.darchan.physical.comp.FlipFlop;
import com.darchan.physical.comp.arith.FullAdder;
import com.darchan.physical.comp.arith.RippleCarryAdder;
import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.Clock;
import com.darchan.physical.signal.ConstantSignal;
import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.LogicValue;
import com.darchan.physical.signal.SimpleSignal;
import com.darchan.physical.signal.Wire;
import com.darchan.testutil.Buses;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static com.darchan.physical.logic.Gates.and;
import static com.darchan.physical.signal.LogicValue.ONE;
import static com.darchan.physical.signal.LogicValue.X;
import static com.darchan.physical.signal.LogicValue.Z;
import static com.darchan.physical.signal.LogicValue.ZERO;
import static org.junit.jupiter.api.Assertions.*;

/** Unknown (X) and floating (Z) levels propagate through gates, derived blocks and flip-flops instead of reading as 0. */
class XPropagationTest {

    private final Simulator sim = new Simulator();

    @Test
    void anUndrivenInputMakesTheFullAdderUnknown() {
        Wire floating = new Wire(sim); // no driver: Z
        FullAdder fa = new FullAdder(sim, new Bus(floating, ConstantSignal.ON, ConstantSignal.OFF));
        sim.settle();
        assertEquals(Z, floating.getValue());
        assertEquals(X, fa.getSum().getValue());
        assertEquals(X, fa.getCarryOut().getValue());
        assertFalse(fa.getSum().isOn(), "boolean callers still see 'not on'");
    }

    @Test
    void anUndrivenInputPropagatesThroughTheCarryChain() {
        // A = 1111 with bit 0 floating, B = 0001, carry in 0: bit 0's carry is unknown, so every higher sum bit and
        // the carry out are unknown too
        Wire floating = new Wire(sim);
        ISignal[] in = new ISignal[9];
        in[0] = floating;
        for (int i = 1; i < 4; i++) {
            in[i] = ConstantSignal.ON;
        }
        in[4] = ConstantSignal.ON;
        for (int i = 5; i < 8; i++) {
            in[i] = ConstantSignal.OFF;
        }
        in[8] = ConstantSignal.OFF;
        RippleCarryAdder adder = new RippleCarryAdder(sim, 4, new Bus(in));
        sim.settle();
        for (int i = 0; i < 5; i++) {
            assertEquals(X, adder.getOutputBus().getSignal(i).getValue(), "sum/carry bit " + i);
        }
    }

    @Test
    void aControllingValueMasksAnUnknownInput() {
        // A bit 0 floating but B bit 0 = 0 and carry in = 0: the carry out of bit 0 is 0 (AND with 0), so only sum
        // bit 0 is unknown
        Wire floating = new Wire(sim);
        ISignal[] in = new ISignal[9];
        in[0] = floating;
        for (int i = 1; i < 8; i++) {
            in[i] = i == 1 || i == 5 ? ConstantSignal.ON : ConstantSignal.OFF; // A = 001z, B = 0010
        }
        in[8] = ConstantSignal.OFF;
        RippleCarryAdder adder = new RippleCarryAdder(sim, 4, new Bus(in));
        sim.settle();
        assertEquals(X, adder.getOutputBus().getSignal(0).getValue());
        assertEquals(ZERO, adder.getOutputBus().getSignal(1).getValue(), "1 + 1 = 10: bit 1 is 0, carry 1");
        assertEquals(ONE, adder.getOutputBus().getSignal(2).getValue());
        assertEquals(ZERO, adder.getOutputBus().getSignal(3).getValue());
        assertEquals(ZERO, adder.getOutputBus().getSignal(4).getValue());
    }

    @Test
    void conflictingDriversMakeAnXThatAFlipFlopLatchesOnTheNextEdge() {
        Clock clk = new Clock(sim, false);
        SimpleSignal driverA = new SimpleSignal(sim, false);
        SimpleSignal driverB = new SimpleSignal(sim, false);
        Wire bus = new Wire(sim);
        bus.connect(driverA);
        bus.connect(driverB);
        ISignal gated = and(sim, bus, ConstantSignal.ON);
        FlipFlop ff = new FlipFlop(sim, clk, new Bus(gated), false);
        sim.settle();
        clk.tick();
        clk.tick();
        assertEquals(ZERO, ff.get(0));
        driverB.setOn(true); // A drives 0, B drives 1
        sim.settle();
        assertEquals(X, bus.getValue());
        assertEquals(X, gated.getValue());
        assertEquals(ZERO, ff.get(0), "nothing latched before the edge");
        clk.tick(); // rising edge
        assertEquals(X, ff.get(0));
        driverB.setOn(false);
        sim.settle();
        clk.tick();
        clk.tick();
        assertEquals(ZERO, ff.get(0), "a clean value is latched again on the next edge");
    }

    // ---- flip-flop clock rules -----------------------------------------------------------------------------------

    /** a flip-flop on a directly driven clock, with D and Q set to the given levels */
    private FlipFlop flipFlop(SimpleSignal clk, SimpleSignal d, LogicValue q) {
        FlipFlop ff = new FlipFlop(sim, clk, new Bus(d), false);
        ff.force(0, q);
        sim.settle();
        return ff;
    }

    @ParameterizedTest(name = "D={0} latches as {1}")
    @CsvSource({"0, 0", "1, 1", "Z, X", "X, X"})
    void aRisingEdgeLatchesDWithZAsX(String d, String expected) {
        SimpleSignal clk = new SimpleSignal(sim, false);
        SimpleSignal din = new SimpleSignal(sim, level(d));
        FlipFlop ff = flipFlop(clk, din, ONE.equals(level(d)) ? ZERO : ONE);
        clk.setValue(ONE);
        sim.settle();
        assertEquals(level(expected), ff.get(0));
    }

    @ParameterizedTest(name = "clock {0} -> {1}, D={2}, Q={3}: Q becomes {4}")
    @CsvSource({
            // a transition that may be a rising edge (0->X, 0->Z, X->1, Z->1): Q keeps its value only if D equals it
            "0, X, 1, 1, 1",
            "0, X, 0, 0, 0",
            "0, X, 1, 0, X",
            "0, X, 0, 1, X",
            "0, Z, 1, 0, X",
            "0, Z, 1, 1, 1",
            "X, 1, 0, 1, X",
            "X, 1, 1, 1, 1",
            "Z, 1, 0, 1, X",
            "Z, 1, 0, 0, 0",
            "0, X, X, 0, X",
            // a transition that cannot be a rising edge (1->X, 1->Z, X->0, Z->0, X<->Z): no change
            "1, X, 0, 1, 1",
            "1, Z, 0, 1, 1",
            "X, 0, 1, 0, 0",
            "Z, 0, 1, 0, 0",
            "X, Z, 1, 0, 0",
            "Z, X, 1, 0, 0",
            // ordinary edges
            "0, 1, 1, 0, 1",
            "1, 0, 1, 0, 0",
    })
    void anUnknownClockIsPessimisticOnlyWhereItCouldBeARisingEdge(String from, String to, String d, String q,
                                                                  String expected) {
        SimpleSignal clk = new SimpleSignal(sim, level(from));
        SimpleSignal din = new SimpleSignal(sim, level(d));
        FlipFlop ff = flipFlop(clk, din, level(q));
        assertEquals(level(q), ff.get(0));
        clk.setValue(level(to));
        sim.settle();
        assertEquals(level(expected), ff.get(0));
    }

    private static LogicValue level(String s) {
        return switch (s) {
            case "0" -> ZERO;
            case "1" -> ONE;
            case "Z" -> Z;
            case "X" -> X;
            default -> throw new IllegalArgumentException(s);
        };
    }

    // ---- multiplexer ---------------------------------------------------------------------------------------------

    @Test
    void anUnknownSelectMakesTheBusSelectorUnknown() {
        IBus in = Buses.simpleBus(sim, 72, 0);
        BusSelector8By8Bit sel = new BusSelector8By8Bit(sim, in);
        Buses.drive(in.slice(new com.darchan.physical.signal.SimpleRange(0, 7)), 0x5A);
        ((SimpleSignal) in.getSignal(64)).setOn(true); // select data bus 0
        sim.settle();
        assertEquals(0x5A, Buses.toInt(sel.getOutputBus()));
        ((SimpleSignal) in.getSignal(66)).setValue(X); // a higher select bit is unknown: bus 0 or bus 2?
        sim.settle();
        for (ISignal s : sel.getOutputBus().getSignals()) {
            assertEquals(X, s.getValue());
        }
        ((SimpleSignal) in.getSignal(67)).setOn(true); // a still higher bit is set: it wins whatever bit 66 is
        sim.settle();
        assertEquals(0, Buses.toInt(sel.getOutputBus()));
        assertEquals(ZERO, sel.getOutputBus().getSignal(0).getValue());
    }
}
