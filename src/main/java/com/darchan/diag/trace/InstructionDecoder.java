package com.darchan.diag.trace;

/** Turns instruction bytes into assembly text; each CPU's disassembler implements it. */
@FunctionalInterface
public interface InstructionDecoder {

    /**
     * @param address where the instruction starts (for PC-relative operands)
     * @param bytes   the instruction bytes; only the first {@code length} are valid
     * @param length  number of valid bytes
     * @return the instruction in assembly syntax, e.g. {@code "LXI SP,0700H"}
     */
    String disassemble(int address, int[] bytes, int length);
}
