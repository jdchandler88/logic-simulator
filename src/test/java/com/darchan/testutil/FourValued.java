package com.darchan.testutil;

import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.LogicValue;
import com.darchan.physical.signal.SimpleSignal;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Helpers for property tests that drive a combinational circuit with some inputs unknown (X) or floating (Z) and
 * check the pessimistic-but-sound rule: a known (0 or 1) output bit must hold for <em>every</em> way of resolving
 * the unknown inputs to 0 or 1. An output that varies with an unknown input must therefore be X.
 */
public final class FourValued {

    private FourValued() {}

    /**
     * An input vector: {@code bits} gives the known levels, and the inputs listed in {@code unknown} (at most
     * {@link #MAX_UNKNOWN}) are Z or X instead.
     */
    public record Stimulus(int width, long bits, List<Integer> unknown, List<LogicValue> levels) {
        public Stimulus {
            unknown = List.copyOf(unknown);
            levels = List.copyOf(levels);
        }

        /** the {@code i}th unknown input's level */
        public LogicValue level(int i) {
            return levels.get(i);
        }

        /** the vector with the unknown inputs resolved by the low bits of {@code choice} */
        public long resolved(int choice) {
            long v = bits;
            for (int j = 0; j < unknown.size(); j++) {
                long mask = 1L << unknown.get(j);
                v = (choice >> j & 1) != 0 ? v | mask : v & ~mask;
            }
            return v;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (int i = width - 1; i >= 0; i--) {
                int idx = unknown.indexOf(i);
                sb.append(idx >= 0 ? levels.get(idx).toString().toLowerCase() : (bits >> i & 1) != 0 ? "1" : "0");
            }
            return sb.toString();
        }
    }

    public static final int MAX_UNKNOWN = 6;

    /**
     * Random stimuli over {@code width} inputs with 0 to {@link #MAX_UNKNOWN} unknown inputs, chosen among the
     * first {@code unknownLimit} inputs (so a test can keep, say, operation-select bits known).
     */
    public static Arbitrary<Stimulus> stimuli(int width, int unknownLimit) {
        Arbitrary<Long> bits = Arbitraries.longs().between(0, (1L << width) - 1);
        Arbitrary<List<Integer>> positions = Arbitraries.integers().between(0, unknownLimit - 1).set()
                .ofMaxSize(MAX_UNKNOWN).map(s -> new ArrayList<>(s));
        Arbitrary<List<LogicValue>> levels = Arbitraries.of(LogicValue.Z, LogicValue.X).list().ofSize(MAX_UNKNOWN);
        return Combinators.combine(bits, positions, levels).as((b, p, l) -> new Stimulus(width, b, p, l));
    }

    /** schedules the stimulus onto a bus of {@link SimpleSignal}s; call {@code settle()} afterwards */
    public static void drive(IBus bus, int offset, Stimulus s, int from, int count) {
        for (int i = 0; i < count; i++) {
            int input = from + i;
            int u = s.unknown().indexOf(input);
            SimpleSignal sig = (SimpleSignal) bus.getSignal(offset + i);
            if (u >= 0) {
                sig.setValue(s.level(u));
            } else {
                sig.setOn((s.bits() >> input & 1) != 0);
            }
        }
    }

    /**
     * Checks every known output bit against {@code reference} over all resolutions of the unknown inputs, and that
     * with no unknown input the outputs equal the reference exactly. Outputs are never Z (a gate cannot float).
     *
     * @param reference maps a fully known input vector to the expected output bits
     */
    public static void assertSound(IBus out, int outWidth, Stimulus s, java.util.function.LongToIntFunction reference) {
        int combos = 1 << s.unknown().size();
        int[] expected = new int[combos];
        for (int c = 0; c < combos; c++) {
            expected[c] = reference.applyAsInt(s.resolved(c));
        }
        for (int bit = 0; bit < outWidth; bit++) {
            LogicValue v = out.getSignal(bit).getValue();
            assertTrue(v != LogicValue.Z, () -> "a gate output is never Z: " + s);
            if (!v.isKnown()) {
                continue; // X is always allowed (pessimism)
            }
            int want = v == LogicValue.ONE ? 1 : 0;
            for (int c = 0; c < combos; c++) {
                int b = bit;
                int cc = c;
                assertTrue((expected[c] >> bit & 1) == want,
                        () -> "output bit " + b + " reads " + v + " for input " + s + " but resolving the unknown"
                                + " inputs as " + Integer.toBinaryString(cc) + " gives " + (expected[cc] >> b & 1));
            }
        }
        if (s.unknown().isEmpty()) {
            for (int bit = 0; bit < outWidth; bit++) {
                int b = bit;
                assertTrue(out.getSignal(bit).getValue().isKnown(), () -> "known inputs " + s + " must give a known bit " + b);
            }
        }
    }

    /** {@code IntUnaryOperator} adapter kept for readability in tests */
    public static java.util.function.LongToIntFunction ofInt(IntUnaryOperator f) {
        return v -> f.applyAsInt((int) v);
    }
}
