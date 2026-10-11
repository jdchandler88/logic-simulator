package com.darchan.behavioral.i8080;

import com.darchan.behavioral.bus.Ram;
import com.darchan.diag.MemoryView;
import com.darchan.diag.Snapshot;
import com.darchan.diag.repl.ReplTarget;
import com.darchan.diag.run.Debugger;

import java.util.Map;
import java.util.Objects;

/**
 * Connects an 8080 with 64 KiB of {@link Ram} to the {@link com.darchan.diag.repl.DebuggerRepl}: Intel-syntax
 * disassembly, and snapshots of the registers plus all of RAM (region {@code ram}).
 */
public final class ReplTarget8080 implements ReplTarget {

    private final Cpu8080 cpu;
    private final Ram ram;
    private final Debugger debugger;
    private final Map<Integer, String> labels;

    /**
     * @param debugger a debugger attached to {@code cpu} (normally {@link Debug8080#attach}, so watchpoints work)
     * @param labels   names for well-known addresses (e.g. {@code 0000 -> "program exit"}); may be empty
     */
    public ReplTarget8080(Cpu8080 cpu, Ram ram, Debugger debugger, Map<Integer, String> labels) {
        this.cpu = Objects.requireNonNull(cpu);
        this.ram = Objects.requireNonNull(ram);
        this.debugger = Objects.requireNonNull(debugger);
        this.labels = Map.copyOf(labels);
        if (debugger.target() != cpu) {
            throw new IllegalArgumentException("the debugger must be attached to this CPU");
        }
    }

    @Override
    public String name() {
        return Cpu8080.MACHINE;
    }

    @Override
    public Debugger debugger() {
        return debugger;
    }

    @Override
    public MemoryView memory() {
        return ram::peek;
    }

    @Override
    public int addressBits() {
        return 16;
    }

    @Override
    public int maxInstructionLength() {
        return 3;
    }

    @Override
    public int instructionLength(int address) {
        return Disassembler8080.length(ram.peek(address & 0xFFFF));
    }

    @Override
    public String disassemble(int address) {
        return Disassembler8080.disassemble(ram::peek, address);
    }

    @Override
    public Snapshot fullSnapshot() {
        return cpu.snapshot().withRegion("ram", ram::peek, 0, Ram.SIZE);
    }

    @Override
    public void restoreFull(Snapshot snapshot) {
        cpu.restore(snapshot);
        snapshot.region("ram").ifPresent(ram::restore);
    }

    @Override
    public Map<Integer, String> addressLabels() {
        return labels;
    }
}
