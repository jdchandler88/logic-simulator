package com.darchan.diag.compare;

import com.darchan.diag.SnapshotDiff;

import java.util.List;
import java.util.Optional;

/**
 * What happened at one {@link Checkpoint}.
 *
 * @param checkpoint  the checkpoint
 * @param status      outcome
 * @param instruction target instruction count when reached (-1 if not reached)
 * @param cycle       target cycle count when reached (-1 if not reached)
 * @param pc          target PC when reached (-1 if not reached)
 * @param diff        for {@link Status#MISMATCH} (and {@link Status#MATCH}): expected = file, actual = live
 * @param detail      extra text (file problems, why it was not reached)
 * @param recentTrace for a mismatch: the last trace lines before the checkpoint
 */
public record CheckpointResult(Checkpoint checkpoint, Status status, long instruction, long cycle, int pc,
                               SnapshotDiff diff, String detail, List<String> recentTrace) {

    public enum Status {
        /** the live snapshot equals the saved one */
        MATCH,
        /** the live snapshot differs from the saved one */
        MISMATCH,
        /** the snapshot was saved (record mode) */
        RECORDED,
        /** the snapshot file is missing or unreadable */
        NO_FILE,
        /** the run ended (or an earlier checkpoint failed) before this one was reached */
        NOT_REACHED
    }

    public CheckpointResult {
        recentTrace = List.copyOf(recentTrace);
    }

    public Optional<SnapshotDiff> snapshotDiff() {
        return Optional.ofNullable(diff);
    }

    /** one line, e.g. {@code MISMATCH  boot @ instruction 1000  (instruction 1000, cycle 7012, PC=01C2): 2 field(s)...} */
    public String summary() {
        StringBuilder sb = new StringBuilder(String.format("%-11s %s", status, checkpoint));
        if (instruction >= 0) {
            sb.append(String.format("  (instruction %d, cycle %d, PC=%04X)", instruction, cycle, pc));
        }
        if (detail != null) {
            sb.append(": ").append(detail);
        }
        return sb.toString();
    }
}
