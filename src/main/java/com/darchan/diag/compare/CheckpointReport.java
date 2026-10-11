package com.darchan.diag.compare;

import com.darchan.diag.run.StopReason;

import java.util.List;
import java.util.Optional;

/**
 * Results of {@link SnapshotCheckpoints#record} or {@link SnapshotCheckpoints#verify}, in the order the checkpoints
 * were reached (unreached ones last).
 *
 * @param results one per checkpoint
 * @param stop    why the run ended
 */
public record CheckpointReport(List<CheckpointResult> results, StopReason stop) {

    public CheckpointReport {
        results = List.copyOf(results);
    }

    /** true if every checkpoint was reached and matched (or recorded) */
    public boolean allPassed() {
        return results.stream().allMatch(r -> r.status() == CheckpointResult.Status.MATCH
                || r.status() == CheckpointResult.Status.RECORDED);
    }

    public Optional<CheckpointResult> firstFailure() {
        return results.stream().filter(r -> r.status() != CheckpointResult.Status.MATCH
                && r.status() != CheckpointResult.Status.RECORDED).findFirst();
    }

    public Optional<CheckpointResult> result(String checkpointName) {
        return results.stream().filter(r -> r.checkpoint().name().equals(checkpointName)).findFirst();
    }

    /** a summary line per checkpoint, then the diff and recent trace of each mismatch */
    public String report() {
        StringBuilder sb = new StringBuilder();
        long passed = results.stream().filter(r -> r.status() == CheckpointResult.Status.MATCH
                || r.status() == CheckpointResult.Status.RECORDED).count();
        sb.append("checkpoints: ").append(passed).append(" of ").append(results.size()).append(" passed; run ended: ")
                .append(stop);
        for (CheckpointResult r : results) {
            sb.append("\n  ").append(r.summary());
        }
        for (CheckpointResult r : results) {
            if (r.status() == CheckpointResult.Status.MISMATCH) {
                sb.append("\n--- ").append(r.checkpoint()).append(" ---\n").append(r.diff().report());
                if (!r.recentTrace().isEmpty()) {
                    sb.append("\n--- last ").append(r.recentTrace().size()).append(" instruction(s) before it ---");
                    r.recentTrace().forEach(l -> sb.append("\n  ").append(l));
                }
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return report();
    }
}
