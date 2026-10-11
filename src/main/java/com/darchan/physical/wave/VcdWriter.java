package com.darchan.physical.wave;

import com.darchan.physical.probe.Probe;
import com.darchan.physical.probe.ProbeSet;
import com.darchan.physical.probe.ProbeValue;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.ISignalChangeListener;
import com.darchan.physical.signal.LogicValue;
import com.darchan.physical.sim.SimulationObserver;
import com.darchan.physical.sim.Simulator;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes the values of chosen {@link ProbeSet} probes to a Value Change Dump (IEEE 1364 VCD) file for a waveform
 * viewer such as GTKWave or Surfer.
 *
 * <p><b>Structure:</b> dot-separated probe names become nested scopes under a top scope ({@code top} by default):
 * probe {@code alu.result} is variable {@code result} in scope {@code top.alu}. Every probe is a {@code wire}; a
 * multi-bit probe is a vector {@code result [7:0]} written MSB first ({@code b00101100}), a 1-bit probe a scalar.
 * Z and X bits are written as {@code z} and {@code x}.
 *
 * <p><b>Time.</b> The simulator has two clocks: simulated time ({@link Simulator#now()}, advanced only by delayed
 * events and {@code runUntil}) and delta cycles (zero-time evaluation steps). Zero-delay circuits clocked with
 * {@code Clock.tick()} never advance simulated time at all. VCD has a single time axis, so the writer offers three
 * mappings ({@link Timing}):
 * <ul>
 *   <li>{@link Timing#DELTA}: one VCD tick per delta cycle (the time stamp is {@link Simulator#deltaCycle()}).
 *       Every intermediate value is visible, including glitches and the ripple of a carry chain settling, which
 *       is what you want when debugging combinational logic or races. The cost: the time axis is not physical (a
 *       deep path looks "slower" than a shallow one, idle periods take no width) and files grow with logic depth.</li>
 *   <li>{@link Timing#SETTLE} (the default): one tick per {@code settle()}/{@code runUntil()} call, i.e. per
 *       {@code Clock.tick()} or testbench step, showing the settled value. Gives clean cycle-level waveforms
 *       for zero-delay synchronous designs (a clock period is two ticks), like an RTL simulator; glitches inside a
 *       settle are invisible.</li>
 *   <li>{@link Timing#SIM_TIME}: one tick per simulated time unit, showing the value at the end of each time step
 *       (deltas collapsed, as most HDL simulators dump). Right for circuits built with transport delays and driven
 *       by {@code runUntil}; for zero-delay circuits everything piles up at time 0. Changes from several settles at
 *       the same simulated time are written under the same time stamp (viewers show the last one).</li>
 * </ul>
 * The {@code $timescale} (default {@code 1ns}) is only a label for the tick; choose what reads best. When the
 * writer is closed it writes one more time stamp (one tick after the last change, or the current time if later)
 * so the final values have a visible width.
 *
 * <p>Changes are tracked with signal listeners (only probes whose signals changed are re-read) and written when
 * the chosen tick ends. Create the writer after building the circuit and settling it once; the current values are
 * written as {@code $dumpvars}.
 */
public final class VcdWriter implements AutoCloseable {

    /** How simulator activity maps to VCD time; see the class comment. */
    public enum Timing {
        DELTA, SETTLE, SIM_TIME
    }

    private final Simulator sim;
    private final Writer out;
    private final Timing timing;
    private final List<Probe> probes;
    private final String[] ids;
    private final ProbeValue[] written;
    private final boolean[] dirty;
    private final int[] dirtyList;
    private int dirtyCount;
    private final List<Runnable> unhook = new ArrayList<>();
    private final SimulationObserver observer;
    private long settles;
    private long lastTime;
    private boolean closed;
    private long changesWritten;

    private VcdWriter(Builder b, List<Probe> chosen, Writer out) {
        this.sim = b.probeSet.simulator();
        this.out = out;
        this.timing = b.timing;
        this.probes = List.copyOf(chosen);
        int n = probes.size();
        this.ids = new String[n];
        this.written = new ProbeValue[n];
        this.dirty = new boolean[n];
        this.dirtyList = new int[n];
        for (int i = 0; i < n; i++) {
            ids[i] = identifier(i);
        }
        writeHeader(b);
        hookSignals();
        this.observer = new SimulationObserver() {
            @Override
            public void deltaCompleted(Simulator s) {
                if (timing == Timing.DELTA) {
                    flushAt(s.deltaCycle());
                }
            }

            @Override
            public void timeAdvancing(Simulator s, long from, long to) {
                if (timing == Timing.SIM_TIME) {
                    flushAt(from);
                }
            }

            @Override
            public void runCompleted(Simulator s) {
                settles++;
                if (timing == Timing.SETTLE) {
                    flushAt(settles);
                } else if (timing == Timing.SIM_TIME) {
                    flushAt(s.now());
                }
            }
        };
        sim.addObserver(observer);
    }

    public static Builder builder(ProbeSet probes) {
        return new Builder(probes);
    }

    /** Records every probe of {@code probes} to {@code file} with the default settings. */
    public static VcdWriter open(Path file, ProbeSet probes, Timing timing) {
        return builder(probes).timing(timing).open(file);
    }

    /** VCD identifier codes: printable ASCII 33..126, base 94 */
    static String identifier(int index) {
        StringBuilder sb = new StringBuilder();
        int i = index;
        do {
            sb.append((char) ('!' + i % 94));
            i = i / 94 - 1;
        } while (i >= 0);
        return sb.toString();
    }

    private long currentTime() {
        return switch (timing) {
            case DELTA -> sim.deltaCycle();
            case SETTLE -> settles;
            case SIM_TIME -> sim.now();
        };
    }

    private void writeHeader(Builder b) {
        StringBuilder sb = new StringBuilder();
        if (b.date != null) {
            sb.append("$date\n  ").append(b.date).append("\n$end\n");
        }
        sb.append("$version\n  logic-simulator VcdWriter\n$end\n");
        sb.append("$comment\n  time mapping: ").append(switch (timing) {
            case DELTA -> "one tick per delta cycle";
            case SETTLE -> "one tick per settle()/runUntil() call (Clock.tick)";
            case SIM_TIME -> "one tick per simulated time unit";
        }).append("\n$end\n");
        sb.append("$timescale ").append(b.timescale).append(" $end\n");
        // scope tree
        Node root = new Node();
        for (int i = 0; i < probes.size(); i++) {
            Node node = root;
            for (String scope : probes.get(i).scopes()) {
                node = node.children.computeIfAbsent(scope, k -> new Node());
            }
            node.vars.add(i);
        }
        sb.append("$scope module ").append(b.top).append(" $end\n");
        writeScope(sb, root);
        sb.append("$upscope $end\n");
        sb.append("$enddefinitions $end\n");
        lastTime = currentTime();
        sb.append('#').append(lastTime).append('\n');
        sb.append("$dumpvars\n");
        for (int i = 0; i < probes.size(); i++) {
            ProbeValue v = probes.get(i).read();
            written[i] = v;
            appendValue(sb, v, ids[i]);
        }
        sb.append("$end\n");
        write(sb);
    }

    private static final class Node {
        final Map<String, Node> children = new LinkedHashMap<>();
        final List<Integer> vars = new ArrayList<>();
    }

    private void writeScope(StringBuilder sb, Node node) {
        for (int i : node.vars) {
            Probe p = probes.get(i);
            sb.append("$var wire ").append(p.width()).append(' ').append(ids[i]).append(' ').append(p.leafName());
            if (p.width() > 1) {
                sb.append(" [").append(p.width() - 1).append(":0]");
            }
            sb.append(" $end\n");
        }
        for (Map.Entry<String, Node> e : node.children.entrySet()) {
            sb.append("$scope module ").append(e.getKey()).append(" $end\n");
            writeScope(sb, e.getValue());
            sb.append("$upscope $end\n");
        }
    }

    private void hookSignals() {
        for (int i = 0; i < probes.size(); i++) {
            final int index = i;
            ISignalChangeListener listener = new ISignalChangeListener() {
                @Override
                public void signalChanged(boolean oldValue, boolean newValue) {
                }

                @Override
                public void valueChanged(LogicValue oldValue, LogicValue newValue) {
                    if (!dirty[index]) {
                        dirty[index] = true;
                        dirtyList[dirtyCount++] = index;
                    }
                }
            };
            for (ISignal s : probes.get(i).signals()) {
                s.addSignalChangeListener(listener);
                unhook.add(() -> s.removeSignalChangeListener(listener));
            }
        }
    }

    private void flushAt(long time) {
        if (dirtyCount == 0 || closed) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        boolean stamped = false;
        for (int k = 0; k < dirtyCount; k++) {
            int i = dirtyList[k];
            dirty[i] = false;
            ProbeValue v = probes.get(i).read();
            if (v.equals(written[i])) {
                continue; // changed and changed back within the tick
            }
            if (!stamped) {
                if (time > lastTime) {
                    sb.append('#').append(time).append('\n');
                    lastTime = time;
                }
                stamped = true;
            }
            written[i] = v;
            appendValue(sb, v, ids[i]);
            changesWritten++;
        }
        dirtyCount = 0;
        if (!sb.isEmpty()) {
            write(sb);
        }
    }

    private static void appendValue(StringBuilder sb, ProbeValue v, String id) {
        if (v.width() == 1) {
            sb.append(v.bits()).append(id).append('\n');
        } else {
            sb.append('b').append(v.bits()).append(' ').append(id).append('\n');
        }
    }

    private void write(CharSequence text) {
        try {
            out.append(text);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write VCD", e);
        }
    }

    /** the time mapping in use */
    public Timing timing() {
        return timing;
    }

    /** the last time stamp written */
    public long lastTime() {
        return lastTime;
    }

    /** value changes written after the initial dump */
    public long changesWritten() {
        return changesWritten;
    }

    public void flush() {
        try {
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot flush VCD", e);
        }
    }

    /**
     * Writes pending changes and a final time stamp, detaches from the simulator and closes the output.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        flushAt(currentTime());
        closed = true;
        sim.removeObserver(observer);
        unhook.forEach(Runnable::run);
        long end = Math.max(lastTime + 1, currentTime());
        write("#" + end + "\n");
        try {
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot close VCD", e);
        }
    }

    /** Configures a {@link VcdWriter}. */
    public static final class Builder {
        private final ProbeSet probeSet;
        private final List<String> names = new ArrayList<>();
        private Timing timing = Timing.SETTLE;
        private String timescale = "1ns";
        private String top = "top";
        private String date;

        private Builder(ProbeSet probes) {
            this.probeSet = Objects.requireNonNull(probes);
        }

        /** record only these probes (default: all) */
        public Builder probes(String... probeNames) {
            names.addAll(List.of(probeNames));
            return this;
        }

        public Builder timing(Timing timing) {
            this.timing = Objects.requireNonNull(timing);
            return this;
        }

        /** the {@code $timescale} label, e.g. {@code "1ns"} or {@code "10 ps"} */
        public Builder timescale(String timescale) {
            if (!timescale.matches("(1|10|100) ?(s|ms|us|ns|ps|fs)")) {
                throw new IllegalArgumentException("timescale must be 1/10/100 followed by s, ms, us, ns, ps or fs: "
                        + timescale);
            }
            this.timescale = timescale;
            return this;
        }

        /** name of the outermost scope (default {@code top}) */
        public Builder topScope(String name) {
            if (!name.matches("[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException("bad scope name " + name);
            }
            this.top = name;
            return this;
        }

        /** text for the optional {@code $date} section (omitted by default so output is reproducible) */
        public Builder date(String date) {
            this.date = date;
            return this;
        }

        /**
         * Creates {@code file} and starts recording. The probe selection is validated first, so a bad probe name
         * throws {@link IllegalArgumentException} without creating the file; if writing the header fails, the file
         * is closed before the exception propagates.
         */
        public VcdWriter open(Path file) {
            List<Probe> chosen = chosenProbes();
            Writer writer;
            try {
                writer = Files.newBufferedWriter(file, StandardCharsets.US_ASCII);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot open " + file, e);
            }
            try {
                return new VcdWriter(this, chosen, writer);
            } catch (RuntimeException e) {
                try {
                    writer.close();
                } catch (IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            }
        }

        /**
         * writes to {@code writer}; closing the VCD writer closes it (a bad probe selection throws before anything
         * is written, and the writer is then left open for the caller to close)
         */
        public VcdWriter to(Writer writer) {
            List<Probe> chosen = chosenProbes();
            return new VcdWriter(this, chosen, writer instanceof BufferedWriter ? writer : new BufferedWriter(writer));
        }

        /** the probes to record, validated: all of them, or the named ones (unknown names throw) */
        private List<Probe> chosenProbes() {
            List<Probe> chosen = new ArrayList<>();
            if (names.isEmpty()) {
                chosen.addAll(probeSet.probes());
            } else {
                for (String n : names) {
                    chosen.add(probeSet.get(n));
                }
            }
            if (chosen.isEmpty()) {
                throw new IllegalArgumentException("no probes to record");
            }
            return chosen;
        }
    }
}
