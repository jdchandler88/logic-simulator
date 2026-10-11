package com.darchan.diag.trace;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the last N trace events, cheaply: each event's values are copied into two preallocated primitive arrays
 * (no allocation, no formatting per instruction). Lines are formatted only when {@link #lines()} or {@link #dump()}
 * is called, typically after a failure. The disassembly is recomputed then from the stored bytes, so it reflects
 * the instruction as executed even if the code was later overwritten.
 */
public final class TraceRingBuffer implements TraceListener {

    private final int capacity;
    private TraceLayout layout;
    private InstructionDecoder decoder;
    private int regCount;

    /** per slot: cycle, instruction */
    private final long[] counters;
    /** per slot ({@code stride} ints): pc, length (negative for an interrupt), packed bytes, registers */
    private int[] data = new int[0];
    private int stride;

    private int next;   // slot for the next event
    private long total; // events seen since the last clear

    public TraceRingBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1: " + capacity);
        }
        this.capacity = capacity;
        this.counters = new long[2 * capacity];
    }

    @Override
    public void onInstruction(TraceEvent e) {
        if (e.layout() != layout) {
            adopt(e);
        }
        int slot = next;
        counters[2 * slot] = e.cycle();
        counters[2 * slot + 1] = e.instruction();
        int[] d = data;
        int base = slot * stride;
        int len = e.length();
        d[base] = e.pc();
        d[base + 1] = e.isInterrupt() ? -1 - len : len;
        d[base + 2] = e.packedBytes();
        int[] src = e.rawRegisters();
        int at = base + 3;
        System.arraycopy(src, 0, d, at, regCount);
        next = slot + 1 == capacity ? 0 : slot + 1;
        total++;
    }

    private void adopt(TraceEvent e) {
        layout = e.layout();
        decoder = e.decoder();
        regCount = layout.registers().size();
        stride = 3 + regCount;
        data = new int[capacity * stride];
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
        StringBuilder sb = new StringBuilder(128);
        for (int i = 0; i < n; i++) {
            int slot = Math.floorMod(next - n + i, capacity);
            int base = slot * stride;
            int pc = data[base];
            boolean interrupt = data[base + 1] < 0;
            int len = interrupt ? -1 - data[base + 1] : data[base + 1];
            int packed = data[base + 2];
            String disassembly = TraceEvent.decode(decoder, pc, packed, len);
            sb.setLength(0);
            TraceFormatter.append(sb, layout, counters[2 * slot], counters[2 * slot + 1], pc, interrupt,
                    packed, len, disassembly, data, base + 3);
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
