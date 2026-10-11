package com.darchan.diag.run;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Triggers when a bus access of one of the given types touches an address (or I/O port) in {@code [from, to]}
 * (inclusive). Note that {@link AccessType#READ} does not include instruction fetches; use
 * {@link AccessType#FETCH} (or a PC breakpoint) for those.
 */
public record Watchpoint(Set<AccessType> types, int from, int to) {

    public Watchpoint {
        types = types.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(types));
        if (types.isEmpty()) {
            throw new IllegalArgumentException("a watchpoint needs at least one access type");
        }
        if (from < 0 || to < from) {
            throw new IllegalArgumentException("bad range " + from + ".." + to);
        }
    }

    public static Watchpoint memoryRead(int address) {
        return memoryRead(address, address);
    }

    public static Watchpoint memoryRead(int from, int to) {
        return new Watchpoint(Set.of(AccessType.READ), from, to);
    }

    public static Watchpoint memoryWrite(int address) {
        return memoryWrite(address, address);
    }

    public static Watchpoint memoryWrite(int from, int to) {
        return new Watchpoint(Set.of(AccessType.WRITE), from, to);
    }

    /** data reads and writes */
    public static Watchpoint memoryAccess(int from, int to) {
        return new Watchpoint(Set.of(AccessType.READ, AccessType.WRITE), from, to);
    }

    public static Watchpoint ioIn(int port) {
        return new Watchpoint(Set.of(AccessType.IO_IN), port, port);
    }

    public static Watchpoint ioOut(int port) {
        return new Watchpoint(Set.of(AccessType.IO_OUT), port, port);
    }

    /** {@code IN} or {@code OUT} on {@code port} */
    public static Watchpoint io(int port) {
        return new Watchpoint(Set.of(AccessType.IO_IN, AccessType.IO_OUT), port, port);
    }

    public boolean matches(AccessType type, int address) {
        return address >= from && address <= to && types.contains(Objects.requireNonNull(type));
    }

    @Override
    public String toString() {
        String range = from == to ? hex(from) : hex(from) + ".." + hex(to);
        return "watch " + EnumSet.copyOf(types) + " " + range;
    }

    private static String hex(int v) {
        return String.format("%04X", v);
    }
}
