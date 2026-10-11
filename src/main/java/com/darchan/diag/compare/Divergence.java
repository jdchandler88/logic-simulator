package com.darchan.diag.compare;

import com.darchan.diag.SnapshotDiff;

import java.util.List;
import java.util.Optional;

/**
 * Where and how two models in a {@link Lockstep} run first disagreed.
 *
 * @param step             reference steps executed when the difference was seen (the instruction that caused it is
 *                         the last one in {@link SideState#recentTrace()}; with step granularity 1 it is exactly
 *                         instruction {@code step - 1}, counting from 0)
 * @param comparison       1-based number of the comparison that failed (0 = the initial state before any step)
 * @param lastMatchingStep reference steps executed at the last comparison that matched, or -1 if none did
 * @param reference        the reference side when the difference was seen
 * @param candidate        the candidate side
 * @param diff             field/memory differences (expected = reference); {@code null} when a side threw
 * @param failure          what a side threw, or {@code null}
 */
public record Divergence(long step, long comparison, long lastMatchingStep, SideState reference, SideState candidate,
                         SnapshotDiff diff, String failure) {

    /**
     * One side's position and recent history.
     *
     * @param name         as given to the {@link Lockstep}
     * @param instructions the target's instruction count
     * @param cycles       the target's cycle count
     * @param pc           the target's program counter (next instruction)
     * @param recentTrace  last trace lines from the side's ring buffer, oldest first (empty if it has none)
     */
    public record SideState(String name, long instructions, long cycles, int pc, List<String> recentTrace) {
        public SideState {
            recentTrace = List.copyOf(recentTrace);
        }

        /** the instruction executed last before the comparison, if traced */
        public Optional<String> lastInstruction() {
            return recentTrace.isEmpty() ? Optional.empty() : Optional.of(recentTrace.get(recentTrace.size() - 1));
        }
    }

    public Optional<SnapshotDiff> snapshotDiff() {
        return Optional.ofNullable(diff);
    }

    public Optional<String> failureMessage() {
        return Optional.ofNullable(failure);
    }

    /**
     * A multi-line report: position, the state diff (or the exception), and both sides' recent trace with the last
     * executed instruction marked {@code >}.
     */
    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("LOCKSTEP DIVERGENCE ");
        if (comparison == 0) {
            sb.append("in the initial state (before any step)");
        } else {
            sb.append("after step ").append(step).append(" (comparison #").append(comparison).append(", ")
                    .append(lastMatchingStep < 0 ? "no earlier match" : "last match after step " + lastMatchingStep)
                    .append(')');
        }
        side(sb, "reference", reference);
        side(sb, "candidate", candidate);
        if (failure != null) {
            sb.append("\n--- failure ---\n").append(failure);
        }
        if (diff != null) {
            sb.append("\n--- state diff (expected = reference, actual = candidate) ---\n").append(diff.report());
        }
        trace(sb, "reference", reference);
        trace(sb, "candidate", candidate);
        return sb.toString();
    }

    private static void side(StringBuilder sb, String role, SideState s) {
        sb.append(String.format("%n  %s %-14s instruction %d, cycle %d, PC=%04X", role, "\"" + s.name() + "\":",
                s.instructions(), s.cycles(), s.pc()));
    }

    private static void trace(StringBuilder sb, String role, SideState s) {
        List<String> lines = s.recentTrace();
        sb.append("\n--- ").append(role).append(" \"").append(s.name()).append("\": ");
        if (lines.isEmpty()) {
            sb.append("no trace (ring buffer disabled) ---");
            return;
        }
        sb.append("last ").append(lines.size()).append(" instruction(s), '>' = executed last ---");
        for (int i = 0; i < lines.size(); i++) {
            sb.append('\n').append(i == lines.size() - 1 ? "> " : "  ").append(lines.get(i));
        }
    }

    @Override
    public String toString() {
        return report();
    }
}
