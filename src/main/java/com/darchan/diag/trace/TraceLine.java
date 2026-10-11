package com.darchan.diag.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link TraceFormatter} line split into named columns, for column-aware comparison of traces.
 *
 * <p>Column names: {@code cycle}, {@code instruction}, {@code pc}, {@code bytes}, {@code disassembly}, then one
 * column per {@code NAME=value} token named after the register ({@code A}, {@code SP}, ...) or flag group
 * ({@code SZAPC}). Each column records where its text sits in the line, so differences can be marked under the
 * line. A line that does not look like a trace line (a header, a comment) has a single column {@code line}.
 */
public final class TraceLine {

    /**
     * @param name  column name
     * @param start index of the first character in the line
     * @param end   index after the last character
     * @param text  the column's text (e.g. {@code "3E"} for {@code A=3E}, {@code "MVI A,3FH"} for the disassembly)
     */
    public record Column(String name, int start, int end, String text) {
    }

    private final String line;
    private final Map<String, Column> columns;

    private TraceLine(String line, Map<String, Column> columns) {
        this.line = line;
        this.columns = Collections.unmodifiableMap(columns);
    }

    public static TraceLine parse(String line) {
        Map<String, Column> cols = new LinkedHashMap<>();
        try {
            int pos = 0;
            int sp = line.indexOf(' ', pos);
            put(cols, line, "cycle", pos, sp, true);
            pos = sp + 1;
            sp = line.indexOf(' ', pos);
            put(cols, line, "instruction", pos, sp, true);
            pos = sp + 1;
            int colon = line.indexOf(": ", pos);
            if (colon < 0 || line.substring(pos, colon).contains(" ")) {
                throw new IllegalArgumentException("no pc");
            }
            put(cols, line, "pc", pos, colon, false);
            pos = colon + 2;
            int gap = line.indexOf("  ", pos);
            int bytesEnd = gap < 0 ? line.length() : gap;
            put(cols, line, "bytes", pos, bytesEnd, false);
            int regStart = firstRegister(line, bytesEnd);
            int disStart = bytesEnd;
            while (disStart < regStart && line.charAt(disStart) == ' ') {
                disStart++;
            }
            int disEnd = regStart;
            while (disEnd > disStart && line.charAt(disEnd - 1) == ' ') {
                disEnd--;
            }
            put(cols, line, "disassembly", disStart, disEnd, false);
            pos = regStart;
            while (pos < line.length()) {
                while (pos < line.length() && line.charAt(pos) == ' ') {
                    pos++;
                }
                if (pos >= line.length()) {
                    break;
                }
                int end = line.indexOf(' ', pos);
                end = end < 0 ? line.length() : end;
                int eq = line.indexOf('=', pos);
                if (eq < 0 || eq > end) {
                    throw new IllegalArgumentException("not a NAME=value token");
                }
                put(cols, line, line.substring(pos, eq), eq + 1, end, false);
                pos = end;
            }
        } catch (RuntimeException e) {
            cols.clear();
            cols.put("line", new Column("line", 0, line.length(), line));
        }
        return new TraceLine(line, cols);
    }

    /** index of the first {@code " NAME="} token after {@code from}, or the line length */
    private static int firstRegister(String line, int from) {
        int i = from;
        while (true) {
            int sp = line.indexOf(' ', i);
            if (sp < 0) {
                return line.length();
            }
            int j = sp + 1;
            while (j < line.length() && isNameChar(line.charAt(j))) {
                j++;
            }
            if (j > sp + 1 && j < line.length() && line.charAt(j) == '=') {
                return sp + 1;
            }
            i = sp + 1;
        }
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }

    private static void put(Map<String, Column> cols, String line, String name, int start, int end, boolean digits) {
        if (end < start) {
            throw new IllegalArgumentException("bad column " + name);
        }
        String text = line.substring(start, end);
        if (digits && (text.isEmpty() || !text.chars().allMatch(Character::isDigit))) {
            throw new IllegalArgumentException("not a counter: " + text);
        }
        if (cols.putIfAbsent(name, new Column(name, start, end, text)) != null) {
            throw new IllegalArgumentException("duplicate column " + name);
        }
    }

    /** the original line */
    public String line() {
        return line;
    }

    /** true if the line had the trace format (and so has more than the single {@code line} column) */
    public boolean isTrace() {
        return !columns.containsKey("line");
    }

    public List<Column> columns() {
        return new ArrayList<>(columns.values());
    }

    public Optional<Column> column(String name) {
        return Optional.ofNullable(columns.get(name));
    }

    /** the column's text, or empty */
    public Optional<String> text(String name) {
        return column(name).map(Column::text);
    }
}
