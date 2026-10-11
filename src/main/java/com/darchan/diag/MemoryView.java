package com.darchan.diag;

/**
 * Side-effect-free read access to an address space, for debuggers and dumps. Implementations must not trigger
 * watchpoints, I/O side effects or timing; a memory bus typically exposes this as a method reference
 * ({@code ram::peek}).
 */
@FunctionalInterface
public interface MemoryView {

    /** @return the byte at {@code address}, 0..255 */
    int peek(int address);
}
