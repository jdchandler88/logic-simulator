package com.darchan.diag;

import java.util.Arrays;
import java.util.Objects;

/** An immutable copy of a contiguous block of memory, part of a {@link Snapshot}. */
public final class MemoryRegion {

    private final String name;
    private final int start;
    private final byte[] data;

    public MemoryRegion(String name, int start, byte[] data) {
        this.name = Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("region name must not be blank");
        }
        if (start < 0) {
            throw new IllegalArgumentException("start must be >= 0: " + start);
        }
        if ((long) start + data.length > Integer.MAX_VALUE) {
            // end() would overflow int, so contains(), diffs and the JSON loader (which rejects it) would disagree
            throw new IllegalArgumentException("region " + name + " of " + data.length + " bytes at " + start
                    + " extends past the largest address " + Integer.MAX_VALUE);
        }
        this.start = start;
        this.data = data.clone();
    }

    /** Copies {@code length} bytes starting at {@code start} out of {@code memory}. */
    public static MemoryRegion capture(String name, MemoryView memory, int start, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must be >= 0: " + length);
        }
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) memory.peek(start + i);
        }
        return new MemoryRegion(name, start, bytes);
    }

    public String name() {
        return name;
    }

    /** first address */
    public int start() {
        return start;
    }

    public int length() {
        return data.length;
    }

    /** one past the last address */
    public int end() {
        return start + data.length;
    }

    public boolean contains(int address) {
        return address >= start && address < end();
    }

    /** @return the unsigned byte at absolute {@code address} */
    public int byteAt(int address) {
        if (!contains(address)) {
            throw new IndexOutOfBoundsException("address " + Integer.toHexString(address) + " is outside " + this);
        }
        return data[address - start] & 0xFF;
    }

    /** a copy of the bytes */
    public byte[] bytes() {
        return data.clone();
    }

    /** A {@link MemoryView} over this region (addresses outside it read as 0). */
    public MemoryView view() {
        return address -> contains(address) ? data[address - start] & 0xFF : 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MemoryRegion r && r.name.equals(name) && r.start == start && Arrays.equals(r.data, data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, start, Arrays.hashCode(data));
    }

    @Override
    public String toString() {
        return name + "[" + Integer.toHexString(start).toUpperCase() + "+" + data.length + "]";
    }
}
