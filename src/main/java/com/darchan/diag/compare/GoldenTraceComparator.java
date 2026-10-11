package com.darchan.diag.compare;

import com.darchan.diag.trace.TraceEvent;
import com.darchan.diag.trace.TraceFormatter;
import com.darchan.diag.trace.TraceLine;
import com.darchan.diag.trace.TraceListener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A {@link TraceListener} that formats every live instruction with {@link TraceFormatter} and compares it with the
 * next line of a golden trace. Lines starting with {@code #} (such as {@link TraceFormatter#header}) and blank lines
 * in the golden file are skipped. After the first mismatch it stops comparing and keeps the
 * {@link TraceMismatch}. Usually driven by {@link GoldenTrace#compare}, which stops the run at the mismatch.
 *
 * <p>With ignored columns (e.g. {@code "cycle"} when the reference emulator counts cycles differently) both lines
 * are parsed with {@link TraceLine} and compared column by column; otherwise lines must be identical.
 */
public final class GoldenTraceComparator implements TraceListener, AutoCloseable {

    /** golden lines shown after the expected one in a mismatch report */
    public static final int FOLLOWING_LINES = 3;

    private final String source;
    private final BufferedReader golden;
    private final Set<String> ignored;
    private final int contextLines;
    private final ArrayDeque<String> context = new ArrayDeque<>();

    private String nextExpected;
    private long nextLineNumber;
    private long lineNumber;
    private long compared;
    private TraceMismatch mismatch;

    GoldenTraceComparator(String source, BufferedReader golden, Set<String> ignoredColumns, int contextLines) {
        this.source = Objects.requireNonNull(source);
        this.golden = Objects.requireNonNull(golden);
        this.ignored = Set.copyOf(ignoredColumns);
        this.contextLines = contextLines;
        readAhead();
    }

    private void readAhead() {
        try {
            String l;
            do {
                l = golden.readLine();
                lineNumber++;
            } while (l != null && (l.isBlank() || l.startsWith("#")));
            nextExpected = l;
            nextLineNumber = lineNumber;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read golden trace " + source, e);
        }
    }

    @Override
    public void onInstruction(TraceEvent event) {
        if (mismatch != null || nextExpected == null) {
            return;
        }
        String actual = TraceFormatter.format(event);
        String expected = nextExpected;
        long line = nextLineNumber;
        if (!matches(expected, actual)) {
            mismatch = describe(line, event.instruction(), expected, actual);
            return;
        }
        compared++;
        if (contextLines > 0) {
            if (context.size() == contextLines) {
                context.removeFirst();
            }
            context.addLast(actual);
        }
        readAhead();
    }

    private boolean matches(String expected, String actual) {
        if (ignored.isEmpty()) {
            return expected.equals(actual);
        }
        return differingColumns(TraceLine.parse(expected), TraceLine.parse(actual)).isEmpty();
    }

    private List<String> differingColumns(TraceLine e, TraceLine a) {
        List<String> out = new ArrayList<>();
        List<String> names = new ArrayList<>();
        e.columns().forEach(c -> names.add(c.name()));
        a.columns().forEach(c -> {
            if (!names.contains(c.name())) {
                names.add(c.name());
            }
        });
        for (String name : names) {
            if (ignored.contains(name)) {
                continue;
            }
            String ev = e.text(name).orElse(null);
            String av = a.text(name).orElse(null);
            if (!Objects.equals(ev, av)) {
                out.add(name + ": expected " + (ev == null ? "<missing>" : ev.isEmpty() ? "<empty>" : ev)
                        + ", actual " + (av == null ? "<missing>" : av.isEmpty() ? "<empty>" : av));
            }
        }
        return out;
    }

    private TraceMismatch describe(long line, long instruction, String expected, String actual) {
        TraceLine e = TraceLine.parse(expected);
        TraceLine a = TraceLine.parse(actual);
        int n = Math.max(expected.length(), actual.length());
        StringBuilder marks = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            boolean differs = i >= expected.length() || i >= actual.length() || expected.charAt(i) != actual.charAt(i);
            marks.append(differs && !inIgnoredColumn(a, i) ? '^' : ' ');
        }
        while (!marks.isEmpty() && marks.charAt(marks.length() - 1) == ' ') {
            marks.setLength(marks.length() - 1);
        }
        List<String> following = new ArrayList<>();
        try {
            for (String l = golden.readLine(); l != null && following.size() < FOLLOWING_LINES; l = golden.readLine()) {
                if (!l.isBlank() && !l.startsWith("#")) {
                    following.add(l);
                }
            }
        } catch (IOException ex) {
            following.add("(cannot read further: " + ex + ")");
        }
        return new TraceMismatch(source, line, instruction, expected, actual, marks.toString(),
                differingColumns(e, a), new ArrayList<>(context), following);
    }

    private boolean inIgnoredColumn(TraceLine line, int index) {
        for (String name : ignored) {
            var c = line.column(name);
            if (c.isPresent() && index >= c.get().start() && index < c.get().end()) {
                return true;
            }
        }
        return false;
    }

    /** true once a mismatch was found */
    public boolean mismatched() {
        return mismatch != null;
    }

    /** true when every golden line has been matched */
    public boolean exhausted() {
        return nextExpected == null;
    }

    /** true when the run should stop: mismatch found or golden trace used up */
    public boolean done() {
        return mismatch != null || nextExpected == null;
    }

    /** lines that matched */
    public long linesCompared() {
        return compared;
    }

    /** the first mismatch, or {@code null} */
    public TraceMismatch mismatch() {
        return mismatch;
    }

    /** the next golden line not yet matched, or {@code null} */
    public String nextExpectedLine() {
        return nextExpected;
    }

    /** line number of {@link #nextExpectedLine()} */
    public long nextExpectedLineNumber() {
        return nextLineNumber;
    }

    public String source() {
        return source;
    }

    @Override
    public void close() {
        try {
            golden.close();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot close golden trace " + source, e);
        }
    }
}
