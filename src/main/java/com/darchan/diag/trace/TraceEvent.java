package com.darchan.diag.trace;

import java.util.Arrays;
import java.util.Objects;

/**
 * The state of a CPU just before it executes one instruction: cycle and instruction counters, PC, the instruction
 * bytes, and the register values described by a {@link TraceLayout}.
 *
 * <p>For speed a CPU fills <em>one</em> event object over and over (producer methods {@link #begin},
 * {@link #addByte}, {@link #setRegister}); listeners see it only for the duration of the callback. Use
 * {@link #copy()} to keep one. The disassembly is computed lazily, so listeners that do not need text (e.g. the
 * ring buffer) never pay for it.
 */
public final class TraceEvent {

    private final TraceLayout layout;
    private final InstructionDecoder decoder;
    private final int[] bytes;
    private final int[] registers;
    private long cycle;
    private long instruction;
    private int pc;
    private boolean interrupt;
    private int length;
    private String disassembly;

    public TraceEvent(TraceLayout layout, InstructionDecoder decoder) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.bytes = new int[layout.maxInstructionLength()];
        this.registers = new int[layout.registers().size()];
    }

    // ---- producer side -------------------------------------------------------------------------------------

    /**
     * Starts describing a new instruction; clears the bytes.
     *
     * @param cycle       cycles executed before this instruction
     * @param instruction instructions executed before this one
     * @param pc          address of the instruction
     * @param interrupt   true if the instruction was injected by an interrupt acknowledge rather than fetched
     */
    public void begin(long cycle, long instruction, int pc, boolean interrupt) {
        this.cycle = cycle;
        this.instruction = instruction;
        this.pc = pc;
        this.interrupt = interrupt;
        this.length = 0;
        this.disassembly = null;
    }

    public void addByte(int value) {
        bytes[length++] = value & 0xFF;
    }

    public void setRegister(int index, int value) {
        registers[index] = value;
    }

    // ---- consumer side -------------------------------------------------------------------------------------

    public TraceLayout layout() {
        return layout;
    }

    public InstructionDecoder decoder() {
        return decoder;
    }

    public long cycle() {
        return cycle;
    }

    public long instruction() {
        return instruction;
    }

    public int pc() {
        return pc;
    }

    public boolean isInterrupt() {
        return interrupt;
    }

    public int length() {
        return length;
    }

    public int byteAt(int index) {
        if (index >= length) {
            throw new IndexOutOfBoundsException(index);
        }
        return bytes[index];
    }

    public int registerCount() {
        return registers.length;
    }

    /** value of register {@code index} in {@link TraceLayout#registers()} order */
    public int register(int index) {
        return registers[index];
    }

    /** value of the register with this name */
    public int register(String name) {
        for (int i = 0; i < registers.length; i++) {
            if (layout.registers().get(i).name().equals(name)) {
                return registers[i];
            }
        }
        throw new IllegalArgumentException("no register " + name + " in " + layout.cpu() + " trace");
    }

    public String disassembly() {
        if (disassembly == null) {
            disassembly = decoder.disassemble(pc, bytes, length);
        }
        return disassembly;
    }

    /** An independent copy that later {@link #begin} calls on this event do not affect. */
    public TraceEvent copy() {
        TraceEvent c = new TraceEvent(layout, decoder);
        c.begin(cycle, instruction, pc, interrupt);
        System.arraycopy(bytes, 0, c.bytes, 0, length);
        c.length = length;
        System.arraycopy(registers, 0, c.registers, 0, registers.length);
        c.disassembly = disassembly;
        return c;
    }

    /** the line {@link TraceFormatter#format} produces */
    @Override
    public String toString() {
        return TraceFormatter.format(this);
    }

    int[] rawBytes() {
        return bytes;
    }

    int[] rawRegisters() {
        return registers;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TraceEvent e && e.cycle == cycle && e.instruction == instruction && e.pc == pc
                && e.interrupt == interrupt && e.length == length && e.layout.equals(layout)
                && Arrays.equals(e.bytes, 0, length, bytes, 0, length) && Arrays.equals(e.registers, registers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cycle, instruction, pc, length);
    }
}
