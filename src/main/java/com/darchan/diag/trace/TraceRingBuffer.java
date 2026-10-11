package com.darchan.diag.trace;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the last N trace events, cheaply: each event's values are copied into preallocated primitive arrays (no
 * allocation, no formatting per instruction). Lines are formatted only when {@link #lines()} or {@link #dump()} is
 * called, typically after a failure. The disassembly is recomputed then from the stored bytes, so it reflects the
 * instruction as executed even if the code was later overwritten.
 */
public final class TraceRingBuffer implements TraceListener {

    private final int capacity;
    private TraceLayout layout;
    private InstructionDecoder decoder;
    private int maxLen;
    private int regCount;

    private final long[] cycles;
    private final long[] instructions;
    private final int[] pcs;
    private final boolean[] interrupts;
    private final int[] lengths;
    private int[] bytes = new int[0];
    private int[] registers = new int[0];

    private int next;   // slot for the next event
    private long total; // events seen since the last clear

    public TraceRingBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1: " + capacity);
        }
        this.capacity = capacity;
        this.cycles = new long[capacity];
        this.instructions = new long[capacity];
        this.pcs = new int[capacity];
        this.interrupts = new boolean[capacity];
        this.lengths = new int[capacity];
    }

    @Override
    public void onInstruction(TraceEvent e) {
        if (e.layout() != layout) {
            adopt(e);
        }
        int slot = next;
        cycles[slot] = e.cycle();
        instructions[slot] = e.instruction();
        pcs[slot] = e.pc();
        interrupts[slot] = e.isInterrupt();
        int len = e.length();
        lengths[slot] = len;
        System.arraycopy(e.rawBytes(), 0, bytes, slot * maxLen, len);
        System.arraycopy(e.rawRegisters(), 0, registers, slot * regCount, regCount);
        next = slot + 1 == capacity ? 0 : slot + 1;
        total++;
    }

    private void adopt(TraceEvent e) {
        layout = e.layout();
        decoder = e.decoder();
        maxLen = layout.maxInstructionLength();
        regCount = layout.registers().size();
        bytes = new int[capacity * maxLen];
        registers = new int[capacity * regCount];
        next = 0;
        total = 0;
    }

    public int capacity() {
        return capacity;
    }

    /** number of events held (at most the capacity) */
    public int size() {
        return (int) Math.min(total, capacity);
    }

    /** events seen since creation or the last {@link #clear()} */
    public long totalSeen() {
        return total;
    }

    public void clear() {
        next = 0;
        total = 0;
    }

    /** the held events as trace lines, oldest first */
    public List<String> lines() {
        int n = size();
        List<String> out = new ArrayList<>(n);
        int[] scratch = new int[Math.max(1, maxLen)];
        StringBuilder sb = new StringBuilder(128);
        for (int i = 0; i < n; i++) {
            int slot = Math.floorMod(next - n + i, capacity);
            System.arraycopy(bytes, slot * maxLen, scratch, 0, lengths[slot]);
            String disassembly = decoder.disassemble(pcs[slot], scratch, lengths[slot]);
            sb.setLength(0);
            TraceFormatter.append(sb, layout, cycles[slot], instructions[slot], pcs[slot], interrupts[slot],
                    bytes, slot * maxLen, lengths[slot], disassembly, registers, slot * regCount);
            out.add(sb.toString());
        }
        return out;
    }

    /** A titled block of the held lines, for failure reports. */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("--- last ").append(size()).append(" of ").append(total).append(" traced instruction(s)");
        if (layout != null) {
            sb.append(" (").append(layout.cpu()).append(")");
        }
        sb.append(" ---");
        for (String line : lines()) {
            sb.append('\n').append(line);
        }
        return sb.toString();
    }
}
