package com.darchan.behavioral.cpm;

import com.darchan.behavioral.bus.MemoryBus;

import java.util.function.LongSupplier;

/**
 * A deliberately faulty memory bus for testing divergence detection: during instruction number
 * {@code atInstruction} (0-based, as counted by the CPU) the first data read or write is corrupted by XOR-ing it
 * with {@code mask}. Fetches and peeks pass through untouched, so traces still show the real code.
 *
 * <p>The fault is deterministic: it fires every time instruction {@code atInstruction} executes, so a run that is
 * restored to an earlier state (or replayed) and executes that instruction again sees the same corruption, as a
 * real logic fault would.
 */
final class FaultInjectingBus implements MemoryBus {

    enum Kind { READ, WRITE }

    private final MemoryBus inner;
    private final LongSupplier instructionCounter;
    private final Kind kind;
    private final long atInstruction;
    private final int mask;
    private boolean fired;
    /** the fault already fired during the current execution of instruction {@code atInstruction} */
    private boolean firedThisTime;

    FaultInjectingBus(MemoryBus inner, LongSupplier instructionCounter, Kind kind, long atInstruction, int mask) {
        this.inner = inner;
        this.instructionCounter = instructionCounter;
        this.kind = kind;
        this.atInstruction = atInstruction;
        this.mask = mask;
    }

    /** Installs a fault into {@code machine}'s CPU (on top of the debugger's observing bus). */
    static FaultInjectingBus install(CpmMachine machine, Kind kind, long atInstruction, int mask) {
        FaultInjectingBus bus = new FaultInjectingBus(machine.cpu().memory(), machine.cpu()::instructionCount, kind,
                atInstruction, mask);
        machine.cpu().setMemory(bus);
        return bus;
    }

    boolean fired() {
        return fired;
    }

    private boolean trigger(Kind k) {
        if (instructionCounter.getAsLong() != atInstruction) {
            firedThisTime = false;
            return false;
        }
        if (!firedThisTime && k == kind) {
            fired = true;
            firedThisTime = true;
            return true;
        }
        return false;
    }

    @Override
    public int read(int address) {
        int v = inner.read(address);
        return trigger(Kind.READ) ? v ^ mask : v;
    }

    @Override
    public void write(int address, int value) {
        inner.write(address, trigger(Kind.WRITE) ? value ^ mask : value);
    }

    @Override
    public int fetch(int address) {
        if (instructionCounter.getAsLong() != atInstruction) {
            firedThisTime = false;
        }
        return inner.fetch(address);
    }

    @Override
    public int peek(int address) {
        return inner.peek(address);
    }
}
