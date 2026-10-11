package com.darchan.testutil;

import com.darchan.diag.Field;
import com.darchan.diag.MemoryRegion;
import com.darchan.diag.Snapshot;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * jqwik generator for diagnostics snapshots, including awkward names (quotes, backslashes, control characters).
 * Built directly from jqwik's random source ({@link Arbitraries#randomValue}) because composing the same thing
 * from combinators is an order of magnitude slower; failures stay reproducible from jqwik's seed, but do not
 * shrink.
 */
public final class Snapshots {

    private Snapshots() {}

    /** pieces that make names awkward for a JSON writer */
    private static final List<String> PIECES = List.of("A", "z", "0", "_", ".", "-", "\"", "\\", "/", "\t", "\n", "\r",
            "\u0001", "\u001f", "\u007f", "é", "€", " ", "😀", "[", "]", "{", "}", ":", ",",
            "\\u0041", "\\n", "'");

    private static String pieces(Random r, int min, int max) {
        int n = min + r.nextInt(max - min + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(PIECES.get(r.nextInt(PIECES.size())));
        }
        return sb.toString();
    }

    /** any char at all, lone surrogates included */
    private static String chaos(Random r, int max) {
        int n = r.nextInt(max + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append((char) (r.nextInt(4) == 0 ? r.nextInt(0x10000) : r.nextInt(0x80)));
        }
        return sb.toString();
    }

    /** a valid {@link Field} name: a token with no spaces or '=', not blank, no leading or trailing whitespace */
    static String fieldName(Random r) {
        while (true) {
            String s = pieces(r, 1, 6);
            if (!s.isBlank() && s.equals(s.strip()) && !s.contains(" ") && !s.contains("=")) {
                return s;
            }
        }
    }

    /** any non-blank string (machine and region names) */
    static String nonBlank(Random r) {
        while (true) {
            String s = r.nextBoolean() ? pieces(r, 1, 6) : chaos(r, 8);
            if (!s.isBlank()) {
                return s;
            }
        }
    }

    /** any string, including the empty one (labels) */
    static String anyString(Random r) {
        return r.nextBoolean() ? pieces(r, 0, 6) : chaos(r, 10);
    }

    public static Arbitrary<String> anyStrings() {
        return Arbitraries.randomValue(Snapshots::anyString);
    }

    static Field field(Random r, String name) {
        Field.Format format = Field.Format.values()[r.nextInt(3)];
        int width = format == Field.Format.BOOL ? 1 : r.nextInt(5) == 0 ? new int[]{8, 16, 32, 64}[r.nextInt(4)]
                : 1 + r.nextInt(64);
        long value = r.nextLong();
        if (width < 64) {
            value &= (1L << width) - 1;
        }
        return new Field(name, width, value, format);
    }

    static MemoryRegion region(Random r, String name) {
        int start = r.nextInt(8) == 0 ? Integer.MAX_VALUE - 200 + r.nextInt(100) : r.nextInt(5) == 0
                ? 0xFFF0 + r.nextInt(32) : r.nextInt(0x20000);
        int length = r.nextInt(4) == 0 ? 100 + r.nextInt(100) : r.nextInt(40);
        length = Math.min(length, Integer.MAX_VALUE - start); // a region may not extend past the largest address
        byte[] bytes = new byte[length];
        r.nextBytes(bytes);
        return new MemoryRegion(name, start, bytes);
    }

    /** snapshots with 0 to 8 fields and 0 to 3 memory regions (some of them longer than a hex line), distinct names */
    public static Arbitrary<Snapshot> snapshots() {
        return Arbitraries.randomValue(r -> {
            Snapshot.Builder b = Snapshot.builder(nonBlank(r)).label(anyString(r));
            Set<String> used = new HashSet<>();
            for (int n = r.nextInt(9); n > 0; n--) {
                String name = fieldName(r);
                if (used.add(name)) {
                    b.field(field(r, name));
                }
            }
            used.clear();
            for (int n = r.nextInt(4); n > 0; n--) {
                String name = nonBlank(r);
                if (used.add(name)) {
                    b.region(region(r, name));
                }
            }
            return b.build();
        });
    }
}
