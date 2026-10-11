package com.darchan.elements.impl.comp.cpu.intel8080;

/**
 * Behavioral (int-based) reference for the Intel 8080 ALU operations, used as the golden model for the gate-level
 * {@link Alu}. Deliberately written in the most direct way, independent of how the gates compute anything.
 *
 * <p>Flags are returned in 8080 F-register layout: {@code S Z 0 AC 0 P 1 CY} (bit 7 to bit 0).
 *
 * <p>The two 8080-specific auxiliary-carry rules live in {@link #subtractAuxCarry} and {@link #andAuxCarry} so they
 * are easy to find and change.
 */
final class Alu8080Reference {

    private Alu8080Reference() {}

    static final int CY = 1;
    static final int P = 1 << 2;
    static final int AC = 1 << 4;
    static final int Z = 1 << 6;
    static final int S = 1 << 7;
    /** bit 1 of F always reads 1 on the 8080; bits 3 and 5 always read 0 */
    static final int ALWAYS_ONE = 1 << 1;

    /**
     * @return {@code (flags << 8) | result}
     */
    static int compute(Alu.Op op, int a, int b, boolean carryIn) {
        int cy = carryIn ? 1 : 0;
        switch (op) {
            case ADD: return add(a, b, 0);
            case ADC: return add(a, b, cy);
            case SUB: return subtract(a, b, 0);
            case SBB: return subtract(a, b, cy);
            case AND: return logical(a & b, andAuxCarry(a, b));
            case XOR: return logical(a ^ b, false);
            case OR:  return logical(a | b, false);
            case CMP: return subtract(a, b, 0); //flags as SUB; the result (A - B) is not written back by a CPU
            default: throw new IllegalArgumentException(op.toString());
        }
    }

    private static int add(int a, int b, int carryIn) {
        int sum = a + b + carryIn;
        boolean carry = sum > 0xFF;
        boolean aux = (a & 0xF) + (b & 0xF) + carryIn > 0xF;
        return pack(sum & 0xFF, carry, aux);
    }

    /** CY is set on a borrow, i.e. when A < B + borrowIn as unsigned numbers. */
    private static int subtract(int a, int b, int borrowIn) {
        int difference = a - b - borrowIn;
        boolean borrow = difference < 0;
        return pack(difference & 0xFF, borrow, subtractAuxCarry(a, b, borrowIn));
    }

    /**
     * 8080 rule: subtraction is done as {@code A + ~B + !borrowIn}, and AC is the carry out of bit 3 of that
     * addition -- it is NOT inverted like CY. So AC is set when the low nibble does <em>not</em> borrow.
     * (Intel's example: {@code SUB A} gives AC = 1.)
     */
    static boolean subtractAuxCarry(int a, int b, int borrowIn) {
        return (a & 0xF) - (b & 0xF) - borrowIn >= 0;
    }

    /**
     * 8080 rule for ANA/ANI: AC is the OR of bit 3 of the two operands. (The 8085 sets AC to 1 instead; XRA/ORA
     * clear AC on both.)
     */
    static boolean andAuxCarry(int a, int b) {
        return ((a | b) & 0x08) != 0;
    }

    /** logical operations always clear CY */
    private static int logical(int result, boolean aux) {
        return pack(result, false, aux);
    }

    private static int pack(int result, boolean carry, boolean aux) {
        int flags = ALWAYS_ONE;
        if ((result & 0x80) != 0) flags |= S;
        if (result == 0) flags |= Z;
        if (aux) flags |= AC;
        if (Integer.bitCount(result) % 2 == 0) flags |= P;
        if (carry) flags |= CY;
        return flags << 8 | result;
    }
}
