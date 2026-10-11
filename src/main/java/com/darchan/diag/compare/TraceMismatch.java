package com.darchan.diag.compare;

import java.util.List;

/**
 * The first line where a live trace differs from a golden trace.
 *
 * @param source           the golden trace's name (usually its file name)
 * @param lineNumber       1-based line number in the golden file (header and comment lines count)
 * @param instruction      the live instruction count at that line (0-based instruction number)
 * @param expected         the golden line
 * @param actual           the live line
 * @param markers          a line of {@code ^} under the characters that differ (aligned with the two lines)
 * @param differingColumns one entry per differing column, e.g. {@code "A: expected FF, actual FE"}
 * @param context          the live lines just before the mismatch (all of which matched), oldest first
 * @param following        the next few golden lines after the expected one (what the golden run did next)
 * @param note             how the mismatch was found when it was not a live trace line, else {@code null}: when a
 *                         run stops before the golden trace ends, the state it stopped in is compared with the next
 *                         golden line, and {@code actual} is then rebuilt from that state (columns that cannot be
 *                         rebuilt, such as the instruction bytes and disassembly, show {@code ?})
 */
public record TraceMismatch(String source, long lineNumber, long instruction, String expected, String actual,
                            String markers, List<String> differingColumns, List<String> context,
                            List<String> following, String note) {

    public TraceMismatch {
        differingColumns = List.copyOf(differingColumns);
        context = List.copyOf(context);
        following = List.copyOf(following);
    }

    /** a mismatch between two trace lines (no note) */
    public TraceMismatch(String source, long lineNumber, long instruction, String expected, String actual,
                         String markers, List<String> differingColumns, List<String> context, List<String> following) {
        this(source, lineNumber, instruction, expected, actual, markers, differingColumns, context, following, null);
    }

    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("GOLDEN TRACE MISMATCH at line ").append(lineNumber).append(" of ").append(source)
                .append(" (instruction ").append(instruction).append(")");
        if (note != null) {
            sb.append("\n  (").append(note).append(")");
        }
        sb.append("\n  expected: ").append(expected);
        sb.append("\n  actual:   ").append(actual);
        sb.append("\n            ").append(markers);
        sb.append("\n  differing columns: ").append(differingColumns.isEmpty() ? "(none parsed)" : String.join("; ", differingColumns));
        sb.append("\n--- context: ").append(context.size()).append(" matching line(s) before ---");
        for (String l : context) {
            sb.append("\n  ").append(l);
        }
        sb.append("\n--- the golden trace continues with ---");
        if (following.isEmpty()) {
            sb.append("\n  (end of file)");
        }
        for (String l : following) {
            sb.append("\n  ").append(l);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return report();
    }
}
