package com.darchan.diag;

import java.util.Objects;

/**
 * One named value in a {@link Snapshot}: a register, a flag, a counter, a signal...
 *
 * @param name   unique within its snapshot, e.g. {@code "PC"}, {@code "A"}, {@code "CYCLES"}
 * @param width  width in bits, 1..64; the value must fit (it is treated as unsigned)
 * @param value  the value, zero-extended
 * @param format how the value is shown in dumps, diffs and JSON
 */
public record Field(String name, int width, long value, Format format) {

    /** Display format of a field's value. */
    public enum Format {
        /** zero-padded upper-case hex, {@code ceil(width / 4)} digits (registers, addresses) */
        HEX,
        /** unsigned decimal (counters such as cycles and instructions) */
        DEC,
        /** {@code 0} or {@code 1} (single-bit state such as "interrupts enabled") */
        BOOL
    }

    public Field {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(format, "format");
        if (name.isBlank() || !name.equals(name.strip()) || name.contains(" ") || name.contains("=")) {
            throw new IllegalArgumentException("field name must be a non-blank token without spaces or '=': '" + name + "'");
        }
        if (width < 1 || width > 64) {
            throw new IllegalArgumentException("width must be 1..64: " + width);
        }
        if (width < 64 && (value >>> width) != 0) {
            throw new IllegalArgumentException(name + " = " + Long.toHexString(value) + " does not fit in " + width + " bits");
        }
        if (format == Format.BOOL && width != 1) {
            throw new IllegalArgumentException("BOOL fields are 1 bit wide: " + name);
        }
    }

    /** A register-like value shown in hex. */
    public static Field hex(String name, int width, long value) {
        return new Field(name, width, value, Format.HEX);
    }

    /** A 64-bit counter shown in decimal. */
    public static Field dec(String name, long value) {
        return new Field(name, 64, value, Format.DEC);
    }

    /** A single-bit flag. */
    public static Field bool(String name, boolean value) {
        return new Field(name, 1, value ? 1 : 0, Format.BOOL);
    }

    public boolean asBoolean() {
        return value != 0;
    }

    /** The value as shown in dumps: {@code 0100} for a 16-bit HEX field, {@code 4924} for DEC, {@code 1} for BOOL. */
    public String formattedValue() {
        return switch (format) {
            case HEX -> hex(value, (width + 3) / 4);
            case DEC -> Long.toUnsignedString(value);
            case BOOL -> value != 0 ? "1" : "0";
        };
    }

    /** {@code NAME=value} */
    @Override
    public String toString() {
        return name + "=" + formattedValue();
    }

    static String hex(long value, int digits) {
        String s = Long.toHexString(value).toUpperCase();
        if (s.length() >= digits) {
            return s;
        }
        return "0".repeat(digits - s.length()) + s;
    }
}
