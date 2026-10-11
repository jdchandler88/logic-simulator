package com.darchan.diag.trace;

import java.util.List;
import java.util.Objects;

/**
 * Describes a CPU's trace columns so {@link TraceFormatter} can stay CPU-agnostic.
 *
 * @param cpu                  name, e.g. {@code "i8080"}
 * @param pcWidth              bits in the program counter (hex digits = {@code ceil(pcWidth / 4)})
 * @param maxInstructionLength longest instruction in bytes (sizes the bytes column)
 * @param disassemblyWidth     the disassembly column is padded to this many characters
 * @param registers            register columns, in order (the PC is its own column and is not listed here)
 */
public record TraceLayout(String cpu, int pcWidth, int maxInstructionLength, int disassemblyWidth,
                          List<RegisterSpec> registers) {

    public TraceLayout {
        Objects.requireNonNull(cpu, "cpu");
        registers = List.copyOf(registers);
        if (pcWidth < 1 || pcWidth > 32 || maxInstructionLength < 1 || disassemblyWidth < 0) {
            throw new IllegalArgumentException("bad trace layout for " + cpu);
        }
    }
}
