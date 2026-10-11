package com.darchan.diag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The differences between an expected and an actual {@link Snapshot}: fields that differ or exist on only one
 * side, and memory bytes that differ in regions present on both sides.
 *
 * <p>{@link #report()} renders one line per difference, e.g.
 * <pre>
 * 2 field(s) and 1 memory byte(s) differ (expected "golden" vs actual "run")
 *   PC          expected=0100  actual=0103
 *   A           expected=3E  actual=00
 *   ram[2000]   expected=00  actual=FF
 * </pre>
 */
public final class SnapshotDiff {

    /** How many byte differences are kept for the report; {@link #byteDifferenceCount()} counts all of them. */
    public static final int MAX_LISTED_BYTES = 64;

    /** {@code expected} or {@code actual} is empty when the field exists on one side only. */
    public record FieldDifference(String name, Optional<Field> expected, Optional<Field> actual) {
        @Override
        public String toString() {
            return name + " expected=" + expected.map(Field::formattedValue).orElse("<missing>")
                    + "  actual=" + actual.map(Field::formattedValue).orElse("<missing>");
        }
    }

    /** One differing byte; {@code address} is absolute. */
    public record ByteDifference(String region, int address, int expected, int actual) {
    }

    private final Snapshot expected;
    private final Snapshot actual;
    private final List<FieldDifference> fieldDifferences;
    private final List<ByteDifference> byteDifferences;
    private final long byteDifferenceCount;
    private final List<String> regionProblems;

    private SnapshotDiff(Snapshot expected, Snapshot actual, List<FieldDifference> fields, List<ByteDifference> bytes,
                         long byteCount, List<String> regionProblems) {
        this.expected = expected;
        this.actual = actual;
        this.fieldDifferences = List.copyOf(fields);
        this.byteDifferences = List.copyOf(bytes);
        this.byteDifferenceCount = byteCount;
        this.regionProblems = List.copyOf(regionProblems);
    }

    public static SnapshotDiff between(Snapshot expected, Snapshot actual) {
        return between(expected, actual, Set.of());
    }

    public static SnapshotDiff between(Snapshot expected, Snapshot actual, Set<String> ignoredFields) {
        List<FieldDifference> fields = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>(expected.fieldNames());
        names.addAll(actual.fieldNames());
        for (String name : names) {
            if (ignoredFields.contains(name)) {
                continue;
            }
            Optional<Field> e = expected.field(name);
            Optional<Field> a = actual.field(name);
            boolean same = e.isPresent() && a.isPresent() && e.get().value() == a.get().value();
            if (!same) {
                fields.add(new FieldDifference(name, e, a));
            }
        }

        List<ByteDifference> bytes = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        if (!expected.machine().equals(actual.machine())) {
            problems.add("machine: expected=" + expected.machine() + "  actual=" + actual.machine());
        }
        long count = 0;
        for (MemoryRegion e : expected.regions()) {
            Optional<MemoryRegion> maybeA = actual.region(e.name());
            if (maybeA.isEmpty()) {
                problems.add("region " + e + " missing from actual");
                continue;
            }
            MemoryRegion a = maybeA.get();
            if (a.start() != e.start() || a.length() != e.length()) {
                problems.add("region " + e.name() + ": expected " + e + "  actual " + a + " (comparing the overlap)");
            }
            int from = Math.max(e.start(), a.start());
            int to = Math.min(e.end(), a.end());
            for (int addr = from; addr < to; addr++) {
                int eb = e.byteAt(addr);
                int ab = a.byteAt(addr);
                if (eb != ab) {
                    if (count < MAX_LISTED_BYTES) {
                        bytes.add(new ByteDifference(e.name(), addr, eb, ab));
                    }
                    count++;
                }
            }
        }
        for (MemoryRegion a : actual.regions()) {
            if (expected.region(a.name()).isEmpty()) {
                problems.add("region " + a + " missing from expected");
            }
        }
        return new SnapshotDiff(expected, actual, fields, bytes, count, problems);
    }

    public boolean isEmpty() {
        return fieldDifferences.isEmpty() && byteDifferenceCount == 0 && regionProblems.isEmpty();
    }

    public List<FieldDifference> fieldDifferences() {
        return fieldDifferences;
    }

    /** the first {@link #MAX_LISTED_BYTES} differing bytes, in region and address order */
    public List<ByteDifference> byteDifferences() {
        return byteDifferences;
    }

    public long byteDifferenceCount() {
        return byteDifferenceCount;
    }

    /** region-level problems: machine mismatch, regions missing on one side, different bounds */
    public List<String> regionProblems() {
        return regionProblems;
    }

    /** A human-readable report, one difference per line (see the class comment). */
    public String report() {
        StringBuilder sb = new StringBuilder();
        String sides = "(expected" + quoted(expected.label()) + " vs actual" + quoted(actual.label()) + ")";
        if (isEmpty()) {
            return sb.append("snapshots are identical ").append(sides).toString();
        }
        sb.append(fieldDifferences.size()).append(" field(s) and ").append(byteDifferenceCount)
                .append(" memory byte(s) differ ").append(sides);
        for (String p : regionProblems) {
            sb.append("\n  ").append(p);
        }
        for (FieldDifference d : fieldDifferences) {
            sb.append("\n  ").append(pad(d.name(), 12))
                    .append("expected=").append(d.expected().map(Field::formattedValue).orElse("<missing>"))
                    .append("  actual=").append(d.actual().map(Field::formattedValue).orElse("<missing>"));
        }
        for (ByteDifference d : byteDifferences) {
            String where = d.region() + "[" + Field.hex(d.address(), 4) + "]";
            sb.append("\n  ").append(pad(where, 12))
                    .append("expected=").append(Field.hex(d.expected(), 2))
                    .append("  actual=").append(Field.hex(d.actual(), 2));
        }
        if (byteDifferenceCount > byteDifferences.size()) {
            sb.append("\n  ... and ").append(byteDifferenceCount - byteDifferences.size()).append(" more byte(s)");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return report();
    }

    private static String quoted(String label) {
        return label.isEmpty() ? "" : " \"" + label + "\"";
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s + " " : s + " ".repeat(width - s.length());
    }
}
