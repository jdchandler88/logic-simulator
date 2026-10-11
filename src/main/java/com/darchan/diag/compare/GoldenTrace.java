package com.darchan.diag.compare;

import com.darchan.diag.run.Debugger;
import com.darchan.diag.run.StopReason;
import com.darchan.diag.trace.TextTraceWriter;
import com.darchan.diag.trace.TraceEvent;
import com.darchan.diag.trace.TraceFormatter;
import com.darchan.diag.trace.TraceLayout;
import com.darchan.diag.trace.TraceListener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Golden-trace workflow: record a trace of a trusted run once ({@link #record}), keep the file, and later compare a
 * live run against it line by line ({@link #compare}). The file is plain {@link TraceFormatter} text, so it can
 * also come from another emulator that writes the same format, and it diffs well in version control.
 *
 * <pre>{@code
 * // once, with a known-good core:
 * GoldenTrace.record(goodMachine.debugger(), Path.of("tst8080.trace"), 1_000_000);
 * // later, after changing the core:
 * GoldenTraceResult r = GoldenTrace.of(Path.of("tst8080.trace")).compare(machine.debugger(), 1_000_000);
 * if (!r.matched()) System.out.println(r.report());
 * }</pre>
 *
 * The run stops right after the instruction following the first mismatching line (the line shows the state
 * <em>before</em> an instruction, so a wrong register at line {@code n} was produced by the instruction at line
 * {@code n - 1}, which is the last context line in the report).
 */
public final class GoldenTrace {

    /** default number of matching lines shown before a mismatch */
    public static final int DEFAULT_CONTEXT_LINES = 8;

    private final String source;
    private final Supplier<Reader> opener;
    private final Set<String> ignored = new LinkedHashSet<>();
    private int contextLines = DEFAULT_CONTEXT_LINES;

    private GoldenTrace(String source, Supplier<Reader> opener) {
        this.source = source;
        this.opener = opener;
    }

    /** A golden trace stored in {@code file} (opened when a comparison starts). */
    public static GoldenTrace of(Path file) {
        Objects.requireNonNull(file);
        return new GoldenTrace(file.getFileName().toString(), () -> {
            try {
                return Files.newBufferedReader(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot open golden trace " + file, e);
            }
        });
    }

    /** A golden trace held in memory (lines separated by {@code '\n'}). */
    public static GoldenTrace ofText(String name, String text) {
        Objects.requireNonNull(text);
        return new GoldenTrace(name, () -> new StringReader(text));
    }

    /** A golden trace from any reader; it can be compared once. */
    public static GoldenTrace of(String name, Reader reader) {
        Objects.requireNonNull(reader);
        return new GoldenTrace(name, () -> reader);
    }

    /**
     * Columns to leave out of the comparison (see {@link com.darchan.diag.trace.TraceLine} for the names:
     * {@code cycle}, {@code instruction}, {@code pc}, {@code bytes}, {@code disassembly}, register names, flag
     * groups such as {@code SZAPC}).
     */
    public GoldenTrace ignoreColumns(String... columns) {
        ignored.addAll(List.of(columns));
        return this;
    }

    /** matching lines shown before a mismatch (default 8) */
    public GoldenTrace contextLines(int lines) {
        if (lines < 0) {
            throw new IllegalArgumentException("lines must be >= 0");
        }
        this.contextLines = lines;
        return this;
    }

    /** A comparator to attach yourself (e.g. with your own run loop); close it when done. */
    public GoldenTraceComparator comparator() {
        Reader r = opener.get();
        BufferedReader br = r instanceof BufferedReader b ? b : new BufferedReader(r);
        return new GoldenTraceComparator(source, br, ignored, contextLines);
    }

    /**
     * Runs {@code debugger}'s target, comparing every instruction with the golden trace, until the first mismatch,
     * the end of the golden trace, any other debugger stop (breakpoint, watchpoint, halt) or {@code maxCycles}.
     */
    public GoldenTraceResult compare(Debugger debugger, long maxCycles) {
        try (GoldenTraceComparator c = comparator()) {
            if (c.exhausted()) {
                return new GoldenTraceResult(source, 0, true,
                        new StopReason(StopReason.Kind.STEPPED, "golden trace is empty", debugger.target().cycleCount(),
                                debugger.target().programCounter()), null, null);
            }
            debugger.addTraceListener(c);
            StopReason stop;
            try {
                stop = debugger.runUntil(c::done, maxCycles);
            } finally {
                debugger.removeTraceListener(c);
            }
            return new GoldenTraceResult(source, c.linesCompared(), c.exhausted(), stop, c.mismatch(),
                    c.exhausted() || c.mismatched() ? null : c.nextExpectedLine());
        }
    }

    /**
     * Records a golden trace: a {@link TraceFormatter#header} line followed by one line per instruction, until any
     * debugger stop or {@code maxCycles}.
     */
    public static StopReason record(Debugger debugger, Path file, long maxCycles) {
        try (TextTraceWriter writer = TextTraceWriter.toFile(file)) {
            HeaderOnFirstEvent listener = new HeaderOnFirstEvent(writer);
            debugger.addTraceListener(listener);
            try {
                return debugger.run(maxCycles);
            } finally {
                debugger.removeTraceListener(listener);
            }
        }
    }

    /** writes the header for the layout of the first event, then forwards */
    private static final class HeaderOnFirstEvent implements TraceListener {
        private final TextTraceWriter writer;
        private TraceLayout layout;

        HeaderOnFirstEvent(TextTraceWriter writer) {
            this.writer = writer;
        }

        @Override
        public void onInstruction(TraceEvent event) {
            if (layout == null) {
                layout = event.layout();
                writer.writeLine(TraceFormatter.header(layout));
            }
            writer.onInstruction(event);
        }
    }

    /** the trace name used in reports */
    public String source() {
        return source;
    }
}
