package com.darchan.physical.probe;

import com.darchan.diag.Snapshot;
import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.sim.Simulator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Named probes on the nets of one {@link Simulator}: the gate-level layer's way of exposing state to the
 * diagnostics layer ({@link #snapshot}, {@link ClockedTarget}) and to waveform dumps
 * ({@link com.darchan.physical.wave.VcdWriter}).
 *
 * <p>Snapshot mapping: every probe becomes a HEX {@code Field} of the probe's name and width whose value has the
 * Z and X bits read as 0. If any bit is Z or X, an extra field {@code NAME.Z} and/or {@code NAME.X} holds the mask
 * of those bits. So a clean two-valued state compares equal to a behavioral model's snapshot, while a floating or
 * contended bus shows up in a {@code SnapshotDiff} as an extra field (e.g. {@code A.Z expected=<missing>
 * actual=FF}) instead of silently reading as 0.
 */
public final class ProbeSet {

    private final Simulator sim;
    private final Map<String, Probe> probes = new LinkedHashMap<>();

    public ProbeSet(Simulator sim) {
        this.sim = Objects.requireNonNull(sim, "sim");
    }

    public Simulator simulator() {
        return sim;
    }

    /** Probes a bus (bit 0 = LSB). */
    public Probe add(String name, IBus bus) {
        return add(name, bus.getSignals());
    }

    /** Probes one signal. */
    public Probe add(String name, ISignal signal) {
        return add(name, List.of(signal));
    }

    public Probe add(String name, List<ISignal> signals) {
        Probe p = new Probe(name, signals);
        for (String existing : probes.keySet()) {
            if (existing.equals(name) || existing.startsWith(name + ".") || name.startsWith(existing + ".")) {
                throw new IllegalArgumentException("probe " + name + " clashes with " + existing
                        + " (a name cannot be both a signal and a scope)");
            }
        }
        if (name.endsWith(".Z") || name.endsWith(".X")) {
            throw new IllegalArgumentException("names ending in .Z or .X are reserved for snapshot masks: " + name);
        }
        probes.put(name, p);
        return p;
    }

    /** all probes, in the order they were added */
    public List<Probe> probes() {
        return Collections.unmodifiableList(new ArrayList<>(probes.values()));
    }

    /** @throws IllegalArgumentException if there is no such probe */
    public Probe get(String name) {
        Probe p = probes.get(name);
        if (p == null) {
            throw new IllegalArgumentException("no probe " + name + "; probes: " + probes.keySet());
        }
        return p;
    }

    public boolean contains(String name) {
        return probes.containsKey(name);
    }

    public ProbeValue read(String name) {
        return get(name).read();
    }

    /** the probe's value with Z/X bits read as 0 */
    public long value(String name) {
        return read(name).value();
    }

    /** Adds every probe to {@code builder} as described in the class comment. */
    public Snapshot.Builder addTo(Snapshot.Builder builder) {
        for (Probe p : probes.values()) {
            ProbeValue v = p.read();
            builder.hex(p.name(), p.width(), v.value());
            if (v.z() != 0) {
                builder.hex(p.name() + ".Z", p.width(), v.z());
            }
            if (v.x() != 0) {
                builder.hex(p.name() + ".X", p.width(), v.x());
            }
        }
        return builder;
    }

    /** A snapshot of every probe (see the class comment). */
    public Snapshot snapshot(String machine) {
        return addTo(Snapshot.builder(machine)).build();
    }

    /** {@code name=value} for every probe, e.g. {@code count=3 bus=Z} */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Probe p : probes.values()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(p.name()).append('=').append(p.read());
        }
        return sb.toString();
    }
}
