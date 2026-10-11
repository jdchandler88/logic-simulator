package com.darchan.diag.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Collects formatted trace lines in memory (tests, short runs). Stops collecting after {@code maxLines}. */
public final class InMemoryTrace implements TraceListener {

    private final int maxLines;
    private final List<String> lines = new ArrayList<>();
    private long dropped;

    public InMemoryTrace() {
        this(Integer.MAX_VALUE);
    }

    public InMemoryTrace(int maxLines) {
        this.maxLines = maxLines;
    }

    @Override
    public void onInstruction(TraceEvent event) {
        if (lines.size() < maxLines) {
            lines.add(TraceFormatter.format(event));
        } else {
            dropped++;
        }
    }

    public List<String> lines() {
        return Collections.unmodifiableList(lines);
    }

    /** events that arrived after the line limit was reached */
    public long dropped() {
        return dropped;
    }

    /** all lines joined with {@code '\n'}, plus a trailing newline when not empty */
    public String text() {
        return lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
    }
}
