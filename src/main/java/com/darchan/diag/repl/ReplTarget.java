package com.darchan.diag.repl;

import com.darchan.diag.MemoryView;
import com.darchan.diag.Snapshot;
import com.darchan.diag.run.Debugger;

import java.util.Map;

/**
 * What the {@link DebuggerRepl} needs to know about a machine beyond its {@link Debugger}: memory, how to
 * disassemble, and how to capture and restore the whole state (registers and memory) for snapshot files. Each CPU
 * provides one (the 8080's is {@code com.darchan.behavioral.i8080.ReplTarget8080}).
 */
public interface ReplTarget {

    /** a short name for banners, e.g. {@code "i8080"} */
    String name();

    /** the debugger driving the machine (owns breakpoints, watchpoints, tracing and the ring buffer) */
    Debugger debugger();

    /** side-effect-free memory reads */
    MemoryView memory();

    /** address width in bits (16 for the 8080): addresses are shown with {@code ceil(bits / 4)} hex digits */
    int addressBits();

    /** longest instruction in bytes (sizes the bytes column of listings) */
    int maxInstructionLength();

    /** length in bytes of the instruction at {@code address} */
    int instructionLength(int address);

    /** the instruction at {@code address} as assembly text */
    String disassemble(int address);

    /** registers plus all memory, for {@code save} and {@code diff} */
    Snapshot fullSnapshot();

    /** restores registers and memory from a {@link #fullSnapshot()} */
    void restoreFull(Snapshot snapshot);

    /** labels for well-known addresses shown with breakpoints, e.g. {@code 0000 -> "program exit"} */
    default Map<Integer, String> addressLabels() {
        return Map.of();
    }
}
