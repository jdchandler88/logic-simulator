package com.darchan.diag.trace;

import java.util.List;
import java.util.Objects;

/**
 * Describes a CPU's trace columns so {@link TraceFormatter} can stay CPU-agnostic.
 *
 * @param cpu                  name, e.g. {@code "i8080"}
 * @param pcWidth              bits in the program counter (hex digits = {@code ceil(pcWidth / 4)})
 * @param maxInstructionLength longest instruction in bytes, 1..4 (sizes the bytes column)
 * @param disassemblyWidth     the disassembly column is padded to this many characters
 * @param registers            register columns, in order (the PC is its own column and is not listed here)
 * @param unknownBits          registers may have high-impedance (Z) or unknown (X) bits, given as masks with
 *                             {@link TraceEvent#setRegister(int, int, int, int)} and shown as described in
 *                             {@link TraceFormatter} (gate-level models); {@code false} for a CPU model, whose
 *                             events then carry no masks at all
 */
public record TraceLayout(String cpu, int pcWidth, int maxInstructionLength, int disassemblyWidth,
                          List<RegisterSpec> registers, boolean unknownBits) {

    /** A layout without Z/X masks (a CPU model). */
    public TraceLayout(String cpu, int pcWidth, int maxInstructionLength, int disassemblyWidth,
                       List<RegisterSpec> registers) {
        this(cpu, pcWidth, maxInstructionLength, disassemblyWidth, registers, false);
    }

    public TraceLayout {
        Objects.requireNonNull(cpu, "cpu");
        registers = List.copyOf(registers);
        if (pcWidth < 1 || pcWidth > 32 || maxInstructionLength < 1 || maxInstructionLength > 4
                || disassemblyWidth < 0) {
            throw new IllegalArgumentException("bad trace layout for " + cpu);
        }
    }
}
