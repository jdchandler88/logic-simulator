package com.darchan.diag.run;

/** Kinds of bus access reported to a {@link BusAccessListener}. */
public enum AccessType {
    /** instruction fetch (opcode or operand bytes) from memory */
    FETCH,
    /** data read from memory */
    READ,
    /** write to memory */
    WRITE,
    /** read from an I/O port (8080 {@code IN}) */
    IO_IN,
    /** write to an I/O port (8080 {@code OUT}) */
    IO_OUT;

    public boolean isMemory() {
        return this == FETCH || this == READ || this == WRITE;
    }
}
