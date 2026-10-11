package com.darchan.physical.probe;

import com.darchan.physical.signal.ISignal;

import java.util.List;
import java.util.Objects;

/**
 * A named view of one signal or a bus (bit 0 = least significant), read as a {@link ProbeValue}.
 *
 * @param name    hierarchical name, dot-separated segments of letters, digits and {@code _} (e.g.
 *                {@code alu.result}); a VCD writer turns the segments before the last into scopes
 * @param signals the probed signals, LSB first
 */
public record Probe(String name, List<ISignal> signals) {

    public Probe {
        checkName(name);
        signals = List.copyOf(signals);
        if (signals.isEmpty() || signals.size() > 64) {
            throw new IllegalArgumentException("a probe has 1..64 signals: " + name + " has " + signals.size());
        }
    }

    /** @throws IllegalArgumentException unless {@code name} is dot-separated {@code [A-Za-z0-9_]} segments */
    static void checkName(String name) {
        Objects.requireNonNull(name, "name");
        if (!name.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")) {
            throw new IllegalArgumentException("probe names are dot-separated [A-Za-z0-9_] segments: '" + name + "'");
        }
    }

    public int width() {
        return signals.size();
    }

    /** the last name segment ({@code result} for {@code alu.result}) */
    public String leafName() {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /** the scope segments ({@code ["alu"]} for {@code alu.result}) */
    public List<String> scopes() {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? List.of() : List.of(name.substring(0, dot).split("\\."));
    }

    public ProbeValue read() {
        long ones = 0;
        long z = 0;
        long x = 0;
        for (int i = 0; i < signals.size(); i++) {
            switch (signals.get(i).getValue()) {
                case ONE -> ones |= 1L << i;
                case Z -> z |= 1L << i;
                case X -> x |= 1L << i;
                case ZERO -> {
                }
            }
        }
        return new ProbeValue(signals.size(), ones, z, x);
    }
}
