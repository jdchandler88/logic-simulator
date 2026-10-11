package com.darchan.physical.wave;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Minimal VCD parser for tests: variables with their full dotted names (scopes included), and every value change
 * per variable. Fails loudly on anything malformed (unknown ids, decreasing time, bad widths).
 */
final class VcdReader {

    record Var(String fullName, String id, int width) {
    }

    final String timescale;
    final Map<String, Var> varsByName = new LinkedHashMap<>();
    final List<Long> timestamps = new ArrayList<>();
    private final Map<String, Var> varsById = new LinkedHashMap<>();
    private final Map<String, NavigableMap<Long, String>> changes = new LinkedHashMap<>();
    final List<String> scopes = new ArrayList<>();

    VcdReader(String text) {
        String[] tokens = text.trim().split("\\s+");
        int i = 0;
        String ts = null;
        Deque<String> scope = new ArrayDeque<>();
        // header
        while (true) {
            String t = tokens[i++];
            switch (t) {
                case "$timescale" -> {
                    StringBuilder sb = new StringBuilder();
                    while (!tokens[i].equals("$end")) {
                        sb.append(tokens[i++]);
                    }
                    i++;
                    ts = sb.toString();
                }
                case "$scope" -> {
                    expect(tokens[i++], "module");
                    scope.addLast(tokens[i++]);
                    expect(tokens[i++], "$end");
                    scopes.add(String.join(".", scope));
                }
                case "$upscope" -> {
                    scope.removeLast();
                    expect(tokens[i++], "$end");
                }
                case "$var" -> {
                    expect(tokens[i++], "wire");
                    int width = Integer.parseInt(tokens[i++]);
                    String id = tokens[i++];
                    String name = tokens[i++];
                    if (tokens[i].startsWith("[")) {
                        expect(tokens[i++], "[" + (width - 1) + ":0]");
                    }
                    expect(tokens[i++], "$end");
                    Var v = new Var(String.join(".", scope) + "." + name, id, width);
                    if (varsById.put(id, v) != null || varsByName.put(v.fullName(), v) != null) {
                        throw new IllegalStateException("duplicate var " + v);
                    }
                    changes.put(v.fullName(), new TreeMap<>());
                }
                case "$enddefinitions" -> {
                    expect(tokens[i++], "$end");
                }
                default -> {
                    if (t.startsWith("$")) { // $date, $version, $comment: skip to $end
                        while (!tokens[i].equals("$end")) {
                            i++;
                        }
                        i++;
                    } else {
                        throw new IllegalStateException("unexpected header token " + t);
                    }
                }
            }
            if (t.equals("$enddefinitions")) {
                break;
            }
        }
        if (!scope.isEmpty()) {
            throw new IllegalStateException("unbalanced scopes");
        }
        this.timescale = ts;
        long time = -1;
        while (i < tokens.length) {
            String t = tokens[i++];
            if (t.startsWith("#")) {
                long next = Long.parseLong(t.substring(1));
                if (next <= time) {
                    throw new IllegalStateException("time stamps must increase: " + next + " after " + time);
                }
                time = next;
                timestamps.add(time);
            } else if (t.equals("$dumpvars") || t.equals("$end")) {
                // structure only
            } else if (t.startsWith("b")) {
                String bits = t.substring(1);
                Var v = var(tokens[i++]);
                if (bits.length() != v.width() || !bits.matches("[01xz]+")) {
                    throw new IllegalStateException("bad vector " + t + " for " + v);
                }
                record(v, time, bits);
            } else {
                String bit = t.substring(0, 1);
                Var v = var(t.substring(1));
                if (v.width() != 1 || !bit.matches("[01xz]")) {
                    throw new IllegalStateException("bad scalar " + t);
                }
                record(v, time, bit);
            }
        }
    }

    private Var var(String id) {
        Var v = varsById.get(id);
        if (v == null) {
            throw new IllegalStateException("unknown id " + id);
        }
        return v;
    }

    private void record(Var v, long time, String value) {
        if (time < 0) {
            throw new IllegalStateException("value change before the first time stamp");
        }
        changes.get(v.fullName()).put(time, value);
    }

    private static void expect(String actual, String expected) {
        if (!actual.equals(expected)) {
            throw new IllegalStateException("expected " + expected + " but found " + actual);
        }
    }

    /** the value (bits, MSB first) of {@code name} at {@code time} */
    String valueAt(String name, long time) {
        NavigableMap<Long, String> m = changes.get(name);
        if (m == null) {
            throw new IllegalArgumentException("no var " + name + " in " + varsByName.keySet());
        }
        var e = m.floorEntry(time);
        return e == null ? null : e.getValue();
    }

    /** {@link #valueAt} as an unsigned number (no z/x allowed) */
    long numberAt(String name, long time) {
        return Long.parseLong(valueAt(name, time), 2);
    }

    NavigableMap<Long, String> changes(String name) {
        return changes.get(name);
    }

    long endTime() {
        return timestamps.get(timestamps.size() - 1);
    }
}
