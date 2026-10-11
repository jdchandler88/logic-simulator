package com.darchan.physical.logic;

import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.ILogicUnit;
import com.darchan.physical.signal.IRange;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.LogicSignal;
import com.darchan.physical.signal.LogicValue;
import com.darchan.physical.signal.SimpleRange;
import com.darchan.physical.signal.SimpleSignal;
import com.darchan.physical.sim.Simulator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static com.darchan.physical.signal.LogicValue.ONE;
import static com.darchan.physical.signal.LogicValue.X;
import static com.darchan.physical.signal.LogicValue.Z;
import static com.darchan.physical.signal.LogicValue.ZERO;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Four-valued (0 1 Z X) truth tables of every gate, with pessimistic X semantics: a Z input behaves like X (a
 * floating CMOS input), a controlling value (0 for AND/NAND, 1 for OR/NOR) decides the output regardless of unknown
 * inputs, and anything else unknown gives X.
 */
class FourValuedGateTest {

    private static final LogicValue[] ALL = {ZERO, ONE, Z, X};

    private static boolean unknown(LogicValue v) {
        return v == Z || v == X;
    }

    private static LogicValue expectedAnd(List<LogicValue> in) {
        if (in.contains(ZERO)) {
            return ZERO;
        }
        return in.stream().allMatch(v -> v == ONE) ? ONE : X;
    }

    private static LogicValue expectedOr(List<LogicValue> in) {
        if (in.contains(ONE)) {
            return ONE;
        }
        return in.stream().allMatch(v -> v == ZERO) ? ZERO : X;
    }

    private static LogicValue not(LogicValue v) {
        return v == ZERO ? ONE : v == ONE ? ZERO : X;
    }

    private static LogicValue expectedXor(List<LogicValue> in) {
        if (in.stream().anyMatch(FourValuedGateTest::unknown)) {
            return X;
        }
        return LogicValue.of(in.stream().filter(v -> v == ONE).count() % 2 == 1);
    }

    /** every combination of {@code n} inputs over 0 1 Z X */
    private static List<List<LogicValue>> combinations(int n) {
        List<List<LogicValue>> out = new ArrayList<>();
        out.add(new ArrayList<>());
        for (int i = 0; i < n; i++) {
            List<List<LogicValue>> next = new ArrayList<>();
            for (List<LogicValue> prefix : out) {
                for (LogicValue v : ALL) {
                    List<LogicValue> c = new ArrayList<>(prefix);
                    c.add(v);
                    next.add(c);
                }
            }
            out = next;
        }
        return out;
    }

    /** checks {@code gate} on 2 and 3 inputs, both by direct evaluation and wired into a simulated circuit */
    private static void check(ILogicUnit gate, Function<List<LogicValue>, LogicValue> expected) {
        for (int n = 2; n <= 3; n++) {
            for (List<LogicValue> in : combinations(n)) {
                checkOne(gate, in, expected.apply(in));
            }
        }
    }

    private static void checkOne(ILogicUnit gate, List<LogicValue> in, LogicValue expected) {
        checkOne(gate, in, expected, true);
    }

    /** @param booleanViewIsOn the unit's {@code evaluate} is {@code isOn()} of its four-valued result (built-ins) */
    private static void checkOne(ILogicUnit gate, List<LogicValue> in, LogicValue expected, boolean booleanViewIsOn) {
        Simulator sim = new Simulator();
        SimpleSignal[] signals = new SimpleSignal[in.size()];
        for (int i = 0; i < signals.length; i++) {
            signals[i] = new SimpleSignal(sim, ZERO);
        }
        IBus bus = new Bus(signals);
        LogicSignal out = new LogicSignal(sim, bus, gate);
        for (int i = 0; i < signals.length; i++) {
            signals[i].setValue(in.get(i));
        }
        sim.settle();
        String what = gate.getClass().getSimpleName() + in;
        assertEquals(expected, gate.evaluateValue(bus), what);
        assertEquals(expected, out.getValue(), what + " in a circuit");
        if (booleanViewIsOn) {
            assertEquals(expected == ONE, gate.evaluate(bus), what + ": the boolean view is isOn() of the result");
        }
        assertEquals(expected == ONE, out.isOn(), what);
    }

    @Test
    void and() {
        check(new AndGate(), FourValuedGateTest::expectedAnd);
    }

    @Test
    void or() {
        check(new OrGate(), FourValuedGateTest::expectedOr);
    }

    @Test
    void nand() {
        check(new NandGate(), in -> not(expectedAnd(in)));
    }

    @Test
    void nor() {
        check(new NorGate(), in -> not(expectedOr(in)));
    }

    @Test
    void xor() {
        check(new XorGate(), FourValuedGateTest::expectedXor);
    }

    @Test
    void inverter() {
        for (LogicValue v : ALL) {
            checkOne(new InverterLogicUnit(), List.of(v), not(v));
        }
        assertEquals(X, not(Z), "a floating input inverts to X, not to 1");
    }

    @Test
    void constantUnitsIgnoreTheirInputs() {
        for (LogicValue v : ALL) {
            checkOne(new LogicalTrueLogicUnit(), List.of(v), ONE);
            checkOne(new LogicalFalseLogicUnit(), List.of(v), ZERO);
        }
    }

    @Test
    void aBooleanOnlyUnitIsPessimisticAboutUnknownInputs() {
        // a unit that only defines the two-valued function: any Z/X input gives X
        ILogicUnit majority = new ILogicUnit() {
            @Override
            public boolean evaluate(IBus inputBus) {
                int ones = 0;
                for (ISignal s : inputBus.getSignals()) {
                    ones += s.isOn() ? 1 : 0;
                }
                return ones >= 2;
            }

            @Override
            public IRange getAllowedInputBusWidth() {
                return new SimpleRange(3, 3);
            }
        };
        checkOne(majority, List.of(ONE, ONE, ZERO), ONE);
        checkOne(majority, List.of(ZERO, ONE, ZERO), ZERO);
        checkOne(majority, List.of(ONE, ONE, X), X, false);
        checkOne(majority, List.of(ZERO, ZERO, Z), X, false);
    }

    @Test
    void logicValueOperatorsMatchTheGates() {
        for (LogicValue a : ALL) {
            assertEquals(not(a), a.not());
            for (LogicValue b : ALL) {
                assertEquals(expectedAnd(List.of(a, b)), a.and(b), a + " and " + b);
                assertEquals(expectedOr(List.of(a, b)), a.or(b), a + " or " + b);
                assertEquals(expectedXor(List.of(a, b)), a.xor(b), a + " xor " + b);
            }
        }
    }
}
