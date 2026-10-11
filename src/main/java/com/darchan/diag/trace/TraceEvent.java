package com.darchan.diag.trace;

import java.util.Arrays;
import java.util.Objects;

/**
 * The state of a CPU just before it executes one instruction: cycle and instruction counters, PC, the instruction
 * bytes, and the register values described by a {@link TraceLayout}.
 *
 * <p>For speed a CPU fills <em>one</em> event object over and over (producer methods {@link #begin},
 * {@link #setBytes} or {@link #addByte}, {@link #setRegister}); listeners see it only for the duration of the callback. Use
 * {@link #copy()} to keep one. The disassembly is computed lazily, so listeners that do not need text (e.g. the
 * ring buffer) never pay for it.
 */
public final class TraceEvent {

    /** {@code BYTE_MASKS[n]} keeps the low {@code n} bytes of a packed int */
    private static final int[] BYTE_MASKS = {0, 0xFF, 0xFFFF, 0xFFFFFF, 0xFFFFFFFF};

    private final TraceLayout layout;
    private final InstructionDecoder decoder;
    private final int[] registers;
    /** instruction bytes packed little-endian: byte i in bits 8i..8i+7 */
    private int bytes;
    private long cycle;
    private long instruction;
    private int pc;
    private boolean interrupt;
    private int length;
    private String disassembly;

    public TraceEvent(TraceLayout layout, InstructionDecoder decoder) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
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
        this.bytes = 0;
        this.disassembly = null;
    }

    /** Appends one instruction byte. */
    public void addByte(int value) {
        if (length >= layout.maxInstructionLength()) {
            throw new IllegalStateException("more than " + layout.maxInstructionLength() + " instruction bytes");
        }
        bytes |= (value & 0xFF) << (8 * length);
        length++;
    }

    /**
     * Sets all instruction bytes at once (cheaper than {@link #addByte} in a loop): byte {@code i} in bits
     * {@code 8i..8i+7} of {@code packed}; bytes beyond {@code length} are ignored.
     */
    public void setBytes(int packed, int length) {
        if (length < 0 || length > layout.maxInstructionLength()) {
            throw new IllegalArgumentException("bad instruction length " + length);
        }
        this.bytes = packed & BYTE_MASKS[length];
        this.length = length;
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
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException(index);
        }
        return bytes >>> (8 * index) & 0xFF;
    }

    /** the instruction bytes packed little-endian (byte {@code i} in bits {@code 8i..8i+7}) */
    public int packedBytes() {
        return bytes;
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
            disassembly = decode(decoder, pc, bytes, length);
        }
        return disassembly;
    }

    /** An independent copy that later {@link #begin} calls on this event do not affect. */
    public TraceEvent copy() {
        TraceEvent c = new TraceEvent(layout, decoder);
        c.begin(cycle, instruction, pc, interrupt);
        c.bytes = bytes;
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

    /** Unpacks the bytes and calls the decoder. */
    static String decode(InstructionDecoder decoder, int pc, int packed, int length) {
        int[] unpacked = new int[Math.max(1, length)];
        for (int i = 0; i < length; i++) {
            unpacked[i] = packed >>> (8 * i) & 0xFF;
        }
        return decoder.disassemble(pc, unpacked, length);
    }

    int[] rawRegisters() {
        return registers;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TraceEvent e && e.cycle == cycle && e.instruction == instruction && e.pc == pc
                && e.interrupt == interrupt && e.length == length && e.layout.equals(layout)
                && e.bytes == bytes && Arrays.equals(e.registers, registers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cycle, instruction, pc, length);
    }
}
