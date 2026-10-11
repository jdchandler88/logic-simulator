package com.darchan.diag.trace;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes one {@link TraceFormatter} line per instruction to a file or stream ({@code '\n'} line endings).
 * Close it (or at least {@link #flush()}) when done; {@link #toFile} buffers.
 */
public final class TextTraceWriter implements TraceListener, AutoCloseable {

    private final Writer out;
    private final boolean closeUnderlying;
    private final StringBuilder line = new StringBuilder(128);
    private long linesWritten;

    private TextTraceWriter(Writer out, boolean closeUnderlying) {
        this.out = out;
        this.closeUnderlying = closeUnderlying;
    }

    public static TextTraceWriter toFile(Path file) {
        try {
            return new TextTraceWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8), true);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open trace file " + file, e);
        }
    }

    /** Writes to {@code stream} (e.g. {@code System.out}); closing the writer flushes but does not close it. */
    public static TextTraceWriter to(PrintStream stream) {
        return new TextTraceWriter(new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8)), false);
    }

    /** Writes to {@code writer}; closing this writer closes it. */
    public static TextTraceWriter to(Writer writer) {
        return new TextTraceWriter(writer, true);
    }

    @Override
    public void onInstruction(TraceEvent event) {
        line.setLength(0);
        TraceFormatter.append(line, event);
        line.append('\n');
        try {
            out.append(line);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write trace", e);
        }
        linesWritten++;
    }

    /** Writes a raw line, e.g. {@link TraceFormatter#header} or a comment. */
    public void writeLine(String text) {
        try {
            out.append(text).append('\n');
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write trace", e);
        }
    }

    public long linesWritten() {
        return linesWritten;
    }

    public void flush() {
        try {
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot flush trace", e);
        }
    }

    @Override
    public void close() {
        try {
            if (closeUnderlying) {
                out.close();
            } else {
                out.flush();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot close trace", e);
        }
    }
}
