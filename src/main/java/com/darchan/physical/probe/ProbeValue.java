package com.darchan.physical.probe;

import com.darchan.physical.signal.LogicValue;

/**
 * The four-valued value of a probed bus (bit 0 = least significant), packed into three bit masks.
 *
 * @param width bits, 1..64
 * @param ones  bits that are {@link LogicValue#ONE}
 * @param z     bits that are high impedance ({@link LogicValue#Z})
 * @param x     bits that are unknown ({@link LogicValue#X})
 */
public record ProbeValue(int width, long ones, long z, long x) {

    public ProbeValue {
        if (width < 1 || width > 64) {
            throw new IllegalArgumentException("width must be 1..64: " + width);
        }
        long mask = mask(width);
        if ((ones & ~mask) != 0 || (z & ~mask) != 0 || (x & ~mask) != 0 || (ones & z) != 0 || (ones & x) != 0
                || (z & x) != 0) {
            throw new IllegalArgumentException("inconsistent masks");
        }
    }

    static long mask(int width) {
        return width == 64 ? -1L : (1L << width) - 1;
    }

    /** the value as an unsigned integer with Z and X bits read as 0 (like {@code ISignal.isOn()}) */
    public long value() {
        return ones;
    }

    /** true if no bit is Z or X */
    public boolean isKnown() {
        return (z | x) == 0;
    }

    /** the level of bit {@code i} */
    public LogicValue bit(int i) {
        if (i < 0 || i >= width) {
            throw new IndexOutOfBoundsException(i);
        }
        long m = 1L << i;
        return (ones & m) != 0 ? LogicValue.ONE : (z & m) != 0 ? LogicValue.Z : (x & m) != 0 ? LogicValue.X
                : LogicValue.ZERO;
    }

    /** most significant bit first, one of {@code 0 1 z x} per bit (the VCD vector syntax) */
    public String bits() {
        StringBuilder sb = new StringBuilder(width);
        for (int i = width - 1; i >= 0; i--) {
            sb.append(switch (bit(i)) {
                case ZERO -> '0';
                case ONE -> '1';
                case Z -> 'z';
                case X -> 'x';
            });
        }
        return sb.toString();
    }

    /**
     * Hex when every bit is known ({@code 3F}); {@code Z} or {@code X} when all bits are; otherwise the bits, MSB
     * first ({@code 0101zzzz}).
     */
    @Override
    public String toString() {
        if (isKnown()) {
            return String.format("%0" + ((width + 3) / 4) + "X", ones);
        }
        long all = mask(width);
        if (z == all) {
            return "Z";
        }
        if (x == all) {
            return "X";
        }
        return bits();
    }
}
