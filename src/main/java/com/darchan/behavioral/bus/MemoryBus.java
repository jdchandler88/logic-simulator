package com.darchan.behavioral.bus;

/**
 * A 16-bit address space of bytes as seen by a CPU. Addresses passed in are already in range (0..0xFFFF) and
 * values are 0..255.
 */
public interface MemoryBus {

    /** Data read (may have side effects, e.g. a memory-mapped register that clears on read). */
    int read(int address);

    void write(int address, int value);

    /** Instruction fetch (opcode or operand byte). Same as {@link #read} unless a decorator wants to tell them apart. */
    default int fetch(int address) {
        return read(address);
    }

    /** Debugger read: must have no side effects. Override when {@link #read} has some. */
    default int peek(int address) {
        return read(address);
    }
}
