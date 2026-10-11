package com.darchan.diag.trace;

import java.util.ArrayList;
import java.util.Arrays;
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
    private boolean masks;

    /** per slot: cycle, instruction */
    private final long[] counters;
    /**
     * per slot ({@code stride} ints): pc, length (negative for an interrupt), packed bytes, registers, then (layouts
     * with unknown bits only) the registers' Z masks and X masks
     */
    private int[] data = new int[0];
    private int stride;

    private int next;   // slot for the next event
    private long total; // events seen since the last clear
    private int held;   // events held (at most the capacity)

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
        if (masks) {
            System.arraycopy(e.rawZ(), 0, d, at + regCount, regCount);
            System.arraycopy(e.rawX(), 0, d, at + 2 * regCount, regCount);
        }
        next = slot + 1 == capacity ? 0 : slot + 1;
        total++;
        if (held < capacity) {
            held++;
        }
    }

    private void adopt(TraceEvent e) {
        layout = e.layout();
        decoder = e.decoder();
        regCount = layout.registers().size();
        masks = layout.unknownBits();
        stride = 3 + regCount * (masks ? 3 : 1);
        data = new int[capacity * stride];
        next = 0;
        total = 0;
        held = 0;
    }

    public int capacity() {
        return capacity;
    }

    /** number of events held (at most the capacity) */
    public int size() {
        return held;
    }

    /** events seen since creation or the last {@link #clear()} */
    public long totalSeen() {
        return total;
    }

    public void clear() {
        next = 0;
        total = 0;
        held = 0;
    }

    /**
     * Forgets the events recorded after the {@code mark}-th one ({@code mark} as returned by {@link #totalSeen()}
     * earlier), as if they had never happened: used when a model is rewound to an earlier state and re-run, so the
     * re-executed instructions are not shown twice. Events from before the mark that were already overwritten
     * cannot come back, so fewer lines may be held afterwards.
     *
     * @throws IllegalArgumentException if {@code mark} is negative or after {@link #totalSeen()}
     */
    public void rollback(long mark) {
        if (mark < 0 || mark > total) {
            throw new IllegalArgumentException("mark " + mark + " is not in 0.." + total);
        }
        long dropped = total - mark;
        int droppedHeld = (int) Math.min(dropped, held);
        next = Math.floorMod(next - droppedHeld, capacity);
        held -= droppedHeld; // 0 if the dropped events had overwritten everything from before the mark
        total = mark;
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
            if (masks) {
                // the masks sit regCount and 2 * regCount after the registers: pass shifted views of the slot
                int[] z = Arrays.copyOfRange(data, base + 3 + regCount, base + 3 + 2 * regCount);
                int[] x = Arrays.copyOfRange(data, base + 3 + 2 * regCount, base + 3 + 3 * regCount);
                int[] regs = Arrays.copyOfRange(data, base + 3, base + 3 + regCount);
                TraceFormatter.append(sb, layout, counters[2 * slot], counters[2 * slot + 1], pc, interrupt,
                        packed, len, disassembly, regs, z, x, 0);
            } else {
                TraceFormatter.append(sb, layout, counters[2 * slot], counters[2 * slot + 1], pc, interrupt,
                        packed, len, disassembly, data, base + 3);
            }
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
