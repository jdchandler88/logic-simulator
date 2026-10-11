package com.darchan.physical.sim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Event-driven simulation context with VHDL/Verilog-style delta cycles.
 *
 * <p>Every signal belongs to exactly one simulator, passed to it at construction. Writing a signal never changes
 * its value immediately; the write is <em>scheduled</em> and becomes visible in the next delta cycle. Each delta
 * cycle has two phases:
 * <ol>
 *     <li><b>Update</b>: every net written during the previous delta commits its pending value (if a net was written
 *     several times, the last write wins). Nets whose value actually changed have an <em>event</em>.</li>
 *     <li><b>Evaluate</b>: listeners of every net with an event are notified. They all read the values committed in
 *     the update phase, and anything they write is scheduled for the next delta.</li>
 * </ol>
 * Because all processes triggered in a delta see the same values, edge-triggered elements sample their pre-edge
 * inputs: a flip-flop's new output appears one delta after the clock edge, after every other flip-flop on that
 * clock has already sampled.
 *
 * <p>Gates have zero delay by default (a level of logic costs one delta cycle but no simulated time). A write may
 * also be scheduled at a later simulated time with {@link #scheduleAfter(long, Runnable)} (transport delay); time
 * only advances once no delta cycles are pending at the current time.
 *
 * <p>A zero-delay loop that never stabilises would run forever, so {@link #settle()} gives up with an
 * {@link OscillationException} after {@link #getMaxDeltaCycles()} delta cycles. The simulator is single-threaded
 * and not re-entrant: listeners must not call {@link #settle()} or {@link #runUntil(long)}.
 *
 * <p>The simulator also keeps the registry of the circuit's storage elements ({@link #stateElements()}): every
 * flip-flop, register and counter registers itself at construction, so the complete state of a circuit can be
 * captured and restored (see {@link StateElement}).
 */
public final class Simulator {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*");

    /**
     * Default limit on delta cycles per {@link #settle()} call (and per time step in {@link #runUntil(long)}).
     */
    public static final int DEFAULT_MAX_DELTA_CYCLES = 10_000;

    private final int maxDeltaCycles;

    /**
     * nets written during the current evaluate phase (or by the testbench); committed in the next delta
     */
    private ArrayList<Schedulable> pending = new ArrayList<>();

    /**
     * recycled list, swapped with {@link #pending} each delta to avoid allocation
     */
    private ArrayList<Schedulable> spare = new ArrayList<>();

    /**
     * nets with an event in the delta being executed
     */
    private final ArrayList<Schedulable> changed = new ArrayList<>();

    /**
     * actions scheduled for a future simulated time
     */
    private final TreeMap<Long, ArrayList<Runnable>> timed = new TreeMap<>();

    private long now;

    private long deltaCycle;

    private boolean running;

    private SimulationObserver[] observers = new SimulationObserver[0];

    private final List<StateElement> stateElements = new ArrayList<>();
    private final Set<String> elementNames = new HashSet<>();
    /** names of registered state elements and names handed out by {@link #uniqueName}, and all their scopes */
    private final Set<String> names = new HashSet<>();

    //statistics
    private long statRuns;
    private long statDeltas;
    private long statEvents;
    private long statUpdates;
    private long statTimeSteps;
    private long statMinDeltas = Long.MAX_VALUE;
    private long statMaxDeltas;
    private long statMinEvents = Long.MAX_VALUE;
    private long statMaxEvents;
    private long runStartDeltas;
    private long runStartEvents;

    public Simulator() {
        this(DEFAULT_MAX_DELTA_CYCLES);
    }

    /**
     * @param maxDeltaCycles delta cycles allowed per {@link #settle()} call (and per time step in
     *                       {@link #runUntil(long)}) before an {@link OscillationException} is thrown
     */
    public Simulator(int maxDeltaCycles) {
        if (maxDeltaCycles < 1) {
            throw new IllegalArgumentException("maxDeltaCycles must be positive but was " + maxDeltaCycles);
        }
        this.maxDeltaCycles = maxDeltaCycles;
    }

    public int getMaxDeltaCycles() {
        return maxDeltaCycles;
    }

    /**
     * @return current simulated time
     */
    public long now() {
        return now;
    }

    /**
     * @return number of delta cycles executed so far. Constant while the listeners of one delta run, so it can be
     * used to recognise repeated notifications within the same delta.
     */
    public long deltaCycle() {
        return deltaCycle;
    }

    /**
     * Adds an observer (e.g. a waveform writer). Observers may be added and removed between runs.
     */
    public void addObserver(SimulationObserver observer) {
        if (observer == null) {
            throw new IllegalArgumentException("observer must not be null");
        }
        SimulationObserver[] grown = Arrays.copyOf(observers, observers.length + 1);
        grown[observers.length] = observer;
        observers = grown;
    }

    public void removeObserver(SimulationObserver observer) {
        observers = Arrays.stream(observers).filter(o -> o != observer).toArray(SimulationObserver[]::new);
    }

    // ---- storage elements ----------------------------------------------------------------------------------

    /**
     * Registers a storage element (called by storage components at construction).
     *
     * @throws IllegalArgumentException if its name is malformed, ends in a segment {@code Z} or {@code X}, or is
     *                                  already used in this simulator
     */
    public void register(StateElement element) {
        String name = element.name();
        checkName(name);
        if (name.equals("Z") || name.equals("X") || name.endsWith(".Z") || name.endsWith(".X")) {
            throw new IllegalArgumentException("a storage element's last name segment may not be Z or X (reserved for"
                    + " the Z/X mask fields of snapshots): " + name);
        }
        if (elementNames.contains(name)) {
            throw new IllegalArgumentException("a storage element called " + name + " already exists");
        }
        if (element.width() < 1 || element.width() > 64) {
            throw new IllegalArgumentException("a storage element has 1..64 bits: " + name + " has " + element.width());
        }
        elementNames.add(name);
        claim(name);
        stateElements.add(element);
    }

    /** every registered storage element, in construction order */
    public List<StateElement> stateElements() {
        return Collections.unmodifiableList(stateElements);
    }

    /**
     * A name {@code prefix + n} not used yet in this simulator, with the smallest such {@code n} from 0; the name is
     * reserved. Components built without a name use it ({@code uniqueName("reg")} gives {@code reg0}, then
     * {@code reg1} ...), so names depend only on construction order.
     */
    public String uniqueName(String prefix) {
        checkName(prefix);
        for (int n = 0; ; n++) {
            String candidate = prefix + n;
            if (!names.contains(candidate)) {
                claim(candidate);
                return candidate;
            }
        }
    }

    /** records {@code name} and each of its enclosing scopes ({@code a} and {@code a.b} for {@code a.b.c}) */
    private void claim(String name) {
        names.add(name);
        for (int dot = name.indexOf('.'); dot >= 0; dot = name.indexOf('.', dot + 1)) {
            names.add(name.substring(0, dot));
        }
    }

    private static void checkName(String name) {
        Objects.requireNonNull(name, "name");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("names are dot-separated [A-Za-z0-9_] segments: '" + name + "'");
        }
    }

    /**
     * @return activity counters since creation or the last {@link #resetStats()}
     */
    public SimulatorStats stats() {
        return new SimulatorStats(statRuns, statDeltas, statRuns == 0 ? 0 : statMinDeltas, statMaxDeltas, statEvents,
                statRuns == 0 ? 0 : statMinEvents, statMaxEvents, statUpdates, statTimeSteps);
    }

    /**
     * Zeroes the {@link #stats()} counters (not {@link #deltaCycle()} or {@link #now()}).
     */
    public void resetStats() {
        statRuns = 0;
        statDeltas = 0;
        statEvents = 0;
        statUpdates = 0;
        statTimeSteps = 0;
        statMinDeltas = Long.MAX_VALUE;
        statMaxDeltas = 0;
        statMinEvents = Long.MAX_VALUE;
        statMaxEvents = 0;
    }

    /** time, delta count and the {@link #stats()} report */
    @Override
    public String toString() {
        return "Simulator at time " + now + ", delta " + deltaCycle + "\n" + stats().report();
    }

    /**
     * @return {@code true} if nothing is scheduled, now or in the future
     */
    public boolean isQuiescent() {
        return pending.isEmpty() && timed.isEmpty();
    }

    /**
     * Schedules a net to commit its pending value in the next delta cycle. Implementations call this at most
     * once per delta (see {@link Schedulable}).
     */
    public void scheduleCommit(Schedulable net) {
        pending.add(net);
    }

    /**
     * Runs {@code action} (typically a signal write) {@code delay} time units from now.
     *
     * @param delay simulated time units, at least 1
     */
    public void scheduleAfter(long delay, Runnable action) {
        if (delay < 1) {
            throw new IllegalArgumentException("delay must be at least 1 but was " + delay);
        }
        timed.computeIfAbsent(now + delay, t -> new ArrayList<>()).add(action);
    }

    /**
     * Runs delta cycles, advancing simulated time through any delayed events, until nothing is scheduled.
     *
     * @throws OscillationException if the circuit is still changing after {@link #getMaxDeltaCycles()} delta
     *                              cycles. The remaining events stay scheduled.
     */
    public void settle() {
        begin();
        try {
            int deltas = 0;
            while (true) {
                if (pending.isEmpty()) {
                    if (timed.isEmpty()) {
                        completed();
                        return;
                    }
                    advanceTo(timed.firstKey());
                    continue;
                }
                if (deltas == maxDeltaCycles) {
                    throw new OscillationException(maxDeltaCycles, now,
                            "zero-delay combinational loop or a free-running oscillator"
                                    + " (use runUntil for circuits that oscillate in simulated time)");
                }
                deltas++;
                runDelta();
            }
        } finally {
            running = false;
        }
    }

    /**
     * Runs all events up to and including simulated time {@code time}, then sets the current time to
     * {@code time}. Unlike {@link #settle()} this suits circuits that never become quiescent, such as a ring
     * oscillator with delays.
     *
     * @throws OscillationException if more than {@link #getMaxDeltaCycles()} delta cycles occur at one time step
     */
    public void runUntil(long time) {
        if (time < now) {
            throw new IllegalArgumentException("cannot run back to time " + time + " from " + now);
        }
        begin();
        try {
            int deltasAtThisTime = 0;
            while (true) {
                if (pending.isEmpty()) {
                    if (timed.isEmpty() || timed.firstKey() > time) {
                        break;
                    }
                    advanceTo(timed.firstKey());
                    deltasAtThisTime = 0;
                    continue;
                }
                if (deltasAtThisTime == maxDeltaCycles) {
                    throw new OscillationException(maxDeltaCycles, now, "zero-delay combinational loop");
                }
                deltasAtThisTime++;
                runDelta();
            }
            if (time > now) {
                timeAdvancing(time);
            }
            now = time;
            completed();
        } finally {
            running = false;
        }
    }

    private void begin() {
        if (running) {
            throw new IllegalStateException("Simulator is not re-entrant: settle/runUntil called from a listener");
        }
        running = true;
        runStartDeltas = statDeltas;
        runStartEvents = statEvents;
    }

    private void completed() {
        statRuns++;
        long d = statDeltas - runStartDeltas;
        long e = statEvents - runStartEvents;
        statMinDeltas = Math.min(statMinDeltas, d);
        statMaxDeltas = Math.max(statMaxDeltas, d);
        statMinEvents = Math.min(statMinEvents, e);
        statMaxEvents = Math.max(statMaxEvents, e);
        for (SimulationObserver o : observers) {
            o.runCompleted(this);
        }
    }

    private void timeAdvancing(long to) {
        statTimeSteps++;
        for (SimulationObserver o : observers) {
            o.timeAdvancing(this, now, to);
        }
    }

    private void advanceTo(long time) {
        Map.Entry<Long, ArrayList<Runnable>> entry = timed.pollFirstEntry();
        timeAdvancing(time);
        now = time;
        for (Runnable action : entry.getValue()) {
            action.run();
        }
    }

    private void runDelta() {
        deltaCycle++;
        statDeltas++;
        ArrayList<Schedulable> current = pending;
        pending = spare;
        statUpdates += current.size();
        try {
            //update phase
            for (int i = 0; i < current.size(); i++) {
                Schedulable net = current.get(i);
                if (net.commit()) {
                    changed.add(net);
                }
            }
            statEvents += changed.size();
            //evaluate phase: writes made here land in 'pending' for the next delta
            for (int i = 0; i < changed.size(); i++) {
                changed.get(i).fireEvent();
            }
            if (observers.length != 0) {
                for (SimulationObserver o : observers) {
                    o.deltaCompleted(this);
                }
            }
        } finally {
            current.clear();
            spare = current;
            changed.clear();
        }
    }
}
