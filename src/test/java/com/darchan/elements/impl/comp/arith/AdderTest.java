package com.darchan.elements.impl.comp.arith;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.impl.SimpleRange;
import com.darchan.sim.Simulator;
import com.darchan.testutil.Buses;
import com.darchan.validation.UnexpectedBusWidthException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class AdderTest {

    private final Simulator sim = new Simulator();

    @Test
    void halfAdderTruthTable() {
        IBus in = Buses.simpleBus(sim, 2, 0);
        HalfAdder ha = new HalfAdder(sim, in);
        for (int v = 0; v < 4; v++) {
            Buses.drive(in, v);
            sim.settle();
            int a = v & 1;
            int b = v >> 1;
            //output [0] = sum, [1] = carry, i.e. the output reads as the integer a + b
            assertEquals(a + b, Buses.toInt(ha.getOutputBus()), "a=" + a + " b=" + b);
        }
    }

    @Test
    void fullAdderTruthTable() {
        IBus in = Buses.simpleBus(sim, 3, 0);
        FullAdder fa = new FullAdder(sim, in);
        for (int v = 0; v < 8; v++) {
            Buses.drive(in, v);
            sim.settle();
            int sum = (v & 1) + ((v >> 1) & 1) + (v >> 2);
            //output [0] = sum, [1] = carry out
            assertEquals(sum, Buses.toInt(fa.getOutputBus()), "inputs " + Integer.toBinaryString(v));
        }
    }

    @Test
    void halfAndFullAdderRejectWrongWidths() {
        assertAll(
                () -> assertThrows(UnexpectedBusWidthException.class, () -> new HalfAdder(sim, Buses.simpleBus(sim, 1, 0))),
                () -> assertThrows(UnexpectedBusWidthException.class, () -> new HalfAdder(sim, Buses.simpleBus(sim, 3, 0))),
                () -> assertThrows(UnexpectedBusWidthException.class, () -> new FullAdder(sim, Buses.simpleBus(sim, 2, 0))),
                () -> assertThrows(UnexpectedBusWidthException.class, () -> new FullAdder(sim, Buses.simpleBus(sim, 4, 0)))
        );
    }

    /**
     * Exhaustive for widths up to 8: every a, b and carry-in.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8})
    void rippleCarryAdderAddsExhaustively(int n) {
        IBus in = Buses.simpleBus(sim, 2 * n + 1, 0);
        IBus a = in.slice(new SimpleRange(0, n - 1));
        IBus b = in.slice(new SimpleRange(n, 2 * n - 1));
        IBus cin = in.slice(new SimpleRange(2 * n, 2 * n));
        RippleCarryAdder adder = new RippleCarryAdder(sim, n, in);
        assertEquals(n + 1, adder.getOutputBus().getSignals().size());
        int mask = (1 << n) - 1;

        int failures = 0;
        StringBuilder firstFailures = new StringBuilder();
        for (int c = 0; c <= 1; c++) {
            Buses.drive(cin, c);
            for (int x = 0; x <= mask; x++) {
                Buses.drive(a, x);
                for (int y = 0; y <= mask; y++) {
                    Buses.drive(b, y);
                    sim.settle();
                    //output [n-1:0] = sum, [n] = carry out: reads as the integer x + y + c
                    int expected = x + y + c;
                    int actual = Buses.toInt(adder.getOutputBus());
                    //carry out of bit i is set iff the low i+1 bits overflow
                    int expectedCarries = 0;
                    for (int i = 0; i < n; i++) {
                        int lowMask = (1 << (i + 1)) - 1;
                        if ((x & lowMask) + (y & lowMask) + c > lowMask) {
                            expectedCarries |= 1 << i;
                        }
                    }
                    int carries = Buses.toInt(adder.getCarries());
                    if (actual != expected || carries != expectedCarries) {
                        if (failures++ < 5) {
                            firstFailures.append(String.format("%d+%d+%d: got %d carries %s; ", x, y, c, actual,
                                    Integer.toBinaryString(carries)));
                        }
                    }
                }
            }
        }
        assertEquals(0, failures, firstFailures.toString());
    }

    @Test
    void rippleCarryAdderRejectsWrongWidths() {
        assertAll(
                () -> assertThrows(UnexpectedBusWidthException.class,
                        () -> new RippleCarryAdder(sim, 8, Buses.simpleBus(sim, 16, 0))),
                () -> assertThrows(UnexpectedBusWidthException.class,
                        () -> new RippleCarryAdder(sim, 8, Buses.simpleBus(sim, 18, 0))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RippleCarryAdder(sim, 0, Buses.simpleBus(sim, 1, 0)))
        );
    }
}
