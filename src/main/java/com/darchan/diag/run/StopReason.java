package com.darchan.diag.run;

/**
 * Why a {@link Debugger} run returned.
 *
 * @param kind   the category
 * @param detail human-readable detail, e.g. {@code "breakpoint at 0005"} or the watchpoint and access
 * @param cycle  target cycle count when it stopped
 * @param pc     target PC when it stopped (the next instruction to execute)
 */
public record StopReason(Kind kind, String detail, long cycle, int pc) {

    public enum Kind {
        /** the requested number of instructions or cycles was executed */
        STEPPED,
        /** the next instruction is at a PC breakpoint */
        BREAKPOINT,
        /** a watched bus access happened during the last instruction */
        WATCHPOINT,
        /** the cycle count crossed a cycle breakpoint */
        CYCLE_BREAKPOINT,
        /** the run-until condition became true */
        CONDITION,
        /** the cycle budget of {@link Debugger#run(long)} ran out */
        CYCLE_LIMIT,
        /** the target halted (and stop-on-halt is enabled) */
        HALTED
    }

    @Override
    public String toString() {
        return kind + " (" + detail + ") at cycle " + cycle + ", PC=" + String.format("%04X", pc);
    }
}
