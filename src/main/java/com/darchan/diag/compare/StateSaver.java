package com.darchan.diag.compare;

import com.darchan.diag.MemoryRegion;
import com.darchan.diag.MemoryView;
import com.darchan.diag.Snapshot;
import com.darchan.diag.Snapshottable;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Saves and restores a model's <em>complete</em> state: everything that influences its later behaviour (registers,
 * counters, the memory it reads and writes, any other device state). {@link Lockstep} uses one per side to narrow a
 * coarse divergence: it saves both sides at every matching comparison and, when a later comparison differs,
 * restores the last saved states and re-runs the window one step at a time.
 *
 * <p>A saver that misses part of the state makes the re-run start from a state that never existed; the narrowing
 * then usually fails to reproduce the divergence (and says so), but it could also report a wrong step. Include
 * everything the model can change.
 *
 * <pre>{@code
 * StateSaver cpuAndRam = StateSaver.of(cpu).withMemory("ram", ram::peek, ram::restore, 0x0000, 0x10000);
 * }</pre>
 */
public interface StateSaver {

    /** Captures the complete state. */
    Snapshot save();

    /** Puts back a state captured by {@link #save()}. */
    void restore(Snapshot saved);

    /**
     * Saves and restores {@code model} through its own {@link Snapshottable#snapshot()} and
     * {@link Snapshottable#restore(Snapshot)}. Complete only if the model's snapshot is
     * ({@link Snapshottable#isSnapshotComplete()}); add memory with {@link #withMemory} otherwise.
     */
    static StateSaver of(Snapshottable model) {
        Objects.requireNonNull(model, "model");
        return new StateSaver() {
            @Override
            public Snapshot save() {
                return model.snapshot();
            }

            @Override
            public void restore(Snapshot saved) {
                model.restore(saved);
            }
        };
    }

    /**
     * This saver plus memory {@code [start, start + length)}: saved as region {@code region} of the snapshot and
     * written back with {@code writer} (e.g. {@code ram::restore}).
     */
    default StateSaver withMemory(String region, MemoryView view, Consumer<MemoryRegion> writer, int start,
                                  int length) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(writer, "writer");
        StateSaver inner = this;
        return new StateSaver() {
            @Override
            public Snapshot save() {
                return inner.save().withRegion(region, view, start, length);
            }

            @Override
            public void restore(Snapshot saved) {
                MemoryRegion r = saved.region(region).orElseThrow(() -> new IllegalArgumentException(
                        "saved state has no memory region " + region + ": " + saved));
                inner.restore(saved);
                writer.accept(r);
            }
        };
    }
}
