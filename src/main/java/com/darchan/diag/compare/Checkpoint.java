package com.darchan.diag.compare;

import com.darchan.diag.run.DebugTarget;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A named point in a run where {@link SnapshotCheckpoints} records or verifies a snapshot.
 *
 * @param name    unique within a set; also the snapshot file name ({@code <name>.json}): letters, digits,
 *                {@code . _ -}
 * @param trigger when the checkpoint is reached
 */
public record Checkpoint(String name, Trigger trigger) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");

    public Checkpoint {
        Objects.requireNonNull(trigger, "trigger");
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("checkpoint names use letters, digits and . _ - only: " + name);
        }
    }

    /** When a checkpoint is reached; evaluated at instruction boundaries. */
    public sealed interface Trigger {
        /**
         * Called once at the start of the run and then once after every step.
         *
         * @return true when the checkpoint is reached at the current boundary
         */
        boolean reached(DebugTarget target);

        /** a fresh copy with any counting state reset (for running the same set again) */
        Trigger fresh();
    }

    /** After exactly {@code count} instructions. */
    public record AtInstruction(long count) implements Trigger {
        @Override
        public boolean reached(DebugTarget t) {
            return t.instructionCount() >= count;
        }

        @Override
        public Trigger fresh() {
            return this;
        }

        @Override
        public String toString() {
            return "instruction " + count;
        }
    }

    /** At the first instruction boundary where the cycle count is at least {@code cycle}. */
    public record AtCycle(long cycle) implements Trigger {
        @Override
        public boolean reached(DebugTarget t) {
            return t.cycleCount() >= cycle;
        }

        @Override
        public Trigger fresh() {
            return this;
        }

        @Override
        public String toString() {
            return "cycle " + cycle;
        }
    }

    /** The {@code occurrence}-th time (1-based) the next instruction is at {@code address}, before executing it. */
    public static final class AtPc implements Trigger {
        private final int address;
        private final int occurrence;
        private int seen;

        public AtPc(int address, int occurrence) {
            if (occurrence < 1) {
                throw new IllegalArgumentException("occurrence is 1-based: " + occurrence);
            }
            this.address = address;
            this.occurrence = occurrence;
        }

        public int address() {
            return address;
        }

        public int occurrence() {
            return occurrence;
        }

        @Override
        public boolean reached(DebugTarget t) {
            return t.programCounter() == address && ++seen == occurrence;
        }

        @Override
        public Trigger fresh() {
            return new AtPc(address, occurrence);
        }

        @Override
        public String toString() {
            return String.format("PC=%04X", address) + (occurrence == 1 ? "" : " (#" + occurrence + ")");
        }
    }

    public static Checkpoint atInstruction(String name, long count) {
        return new Checkpoint(name, new AtInstruction(count));
    }

    public static Checkpoint atCycle(String name, long cycle) {
        return new Checkpoint(name, new AtCycle(cycle));
    }

    public static Checkpoint atPc(String name, int address) {
        return atPc(name, address, 1);
    }

    public static Checkpoint atPc(String name, int address, int occurrence) {
        return new Checkpoint(name, new AtPc(address, occurrence));
    }

    @Override
    public String toString() {
        return name + " @ " + trigger;
    }
}
