package com.darchan.diag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * An immutable, machine-agnostic picture of a model's state: an ordered list of named {@link Field}s (registers,
 * flags, counters) plus optional {@link MemoryRegion}s.
 *
 * <p>The {@code machine} string (e.g. {@code "i8080"}) says which model produced it, so a snapshot can only be
 * restored into a compatible model. The {@code label} is free text ("after 1000 cycles", "golden").
 *
 * <p>Snapshots can be compared ({@link #diff}), printed ({@link RegisterDump}, {@link HexDump}) and saved as JSON
 * ({@link SnapshotJson}).
 */
public final class Snapshot {

    private final String machine;
    private final String label;
    private final Map<String, Field> fields;
    private final Map<String, MemoryRegion> regions;

    private Snapshot(String machine, String label, Map<String, Field> fields, Map<String, MemoryRegion> regions) {
        this.machine = machine;
        this.label = label;
        this.fields = Collections.unmodifiableMap(fields);
        this.regions = Collections.unmodifiableMap(regions);
    }

    public static Builder builder(String machine) {
        return new Builder(machine);
    }

    public String machine() {
        return machine;
    }

    /** free-text label, never null (may be empty) */
    public String label() {
        return label;
    }

    /** the fields, in the order the model defined them */
    public List<Field> fields() {
        return List.copyOf(fields.values());
    }

    public Optional<Field> field(String name) {
        return Optional.ofNullable(fields.get(name));
    }

    /** @throws IllegalArgumentException if there is no such field */
    public long value(String name) {
        Field f = fields.get(name);
        if (f == null) {
            throw new IllegalArgumentException("snapshot of " + machine + " has no field " + name + "; fields: " + fields.keySet());
        }
        return f.value();
    }

    public List<MemoryRegion> regions() {
        return List.copyOf(regions.values());
    }

    public Optional<MemoryRegion> region(String name) {
        return Optional.ofNullable(regions.get(name));
    }

    public Snapshot withLabel(String newLabel) {
        return new Snapshot(machine, Objects.requireNonNull(newLabel), new LinkedHashMap<>(fields), new LinkedHashMap<>(regions));
    }

    /** A copy with {@code region} added (replacing a region of the same name). */
    public Snapshot withRegion(MemoryRegion region) {
        Map<String, MemoryRegion> r = new LinkedHashMap<>(regions);
        r.put(region.name(), region);
        return new Snapshot(machine, label, new LinkedHashMap<>(fields), r);
    }

    /** A copy with {@code memory[start, start + length)} captured as region {@code name}. */
    public Snapshot withRegion(String name, MemoryView memory, int start, int length) {
        return withRegion(MemoryRegion.capture(name, memory, start, length));
    }

    /** Field-by-field (and byte-by-byte) comparison; {@code this} is the expected side. */
    public SnapshotDiff diff(Snapshot actual) {
        return SnapshotDiff.between(this, actual);
    }

    /** Like {@link #diff(Snapshot)} but ignoring the named fields (e.g. {@code "CYCLES"}). */
    public SnapshotDiff diff(Snapshot actual, Set<String> ignoredFields) {
        return SnapshotDiff.between(this, actual, ignoredFields);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Snapshot s && s.machine.equals(machine) && s.label.equals(label)
                && List.copyOf(s.fields.values()).equals(List.copyOf(fields.values()))
                && List.copyOf(s.regions.values()).equals(List.copyOf(regions.values()));
    }

    @Override
    public int hashCode() {
        return Objects.hash(machine, label, List.copyOf(fields.values()), List.copyOf(regions.values()));
    }

    /** {@code i8080 "label": PC=0100 SP=FFFF A=00 ...} plus region names */
    @Override
    public String toString() {
        String s = machine + (label.isEmpty() ? "" : " \"" + label + "\"") + ": " + RegisterDump.format(this);
        return regions.isEmpty() ? s : s + " " + regions.values();
    }

    /** Builds a snapshot; field order is insertion order and names must be unique. */
    public static final class Builder {
        private final String machine;
        private String label = "";
        private final Map<String, Field> fields = new LinkedHashMap<>();
        private final Map<String, MemoryRegion> regions = new LinkedHashMap<>();

        private Builder(String machine) {
            this.machine = Objects.requireNonNull(machine, "machine");
            if (machine.isBlank()) {
                throw new IllegalArgumentException("machine must not be blank");
            }
        }

        public Builder label(String label) {
            this.label = Objects.requireNonNull(label, "label");
            return this;
        }

        public Builder field(Field field) {
            if (fields.putIfAbsent(field.name(), field) != null) {
                throw new IllegalArgumentException("duplicate field " + field.name());
            }
            return this;
        }

        public Builder fields(Collection<Field> all) {
            all.forEach(this::field);
            return this;
        }

        public Builder hex(String name, int width, long value) {
            return field(Field.hex(name, width, value));
        }

        public Builder dec(String name, long value) {
            return field(Field.dec(name, value));
        }

        public Builder bool(String name, boolean value) {
            return field(Field.bool(name, value));
        }

        public Builder region(MemoryRegion region) {
            if (regions.putIfAbsent(region.name(), region) != null) {
                throw new IllegalArgumentException("duplicate region " + region.name());
            }
            return this;
        }

        public Builder region(String name, MemoryView memory, int start, int length) {
            return region(MemoryRegion.capture(name, memory, start, length));
        }

        public Snapshot build() {
            return new Snapshot(machine, label, new LinkedHashMap<>(fields), new LinkedHashMap<>(regions));
        }
    }

    /** Names of all fields, in order (handy for building ignore sets). */
    public List<String> fieldNames() {
        return new ArrayList<>(fields.keySet());
    }
}
