package com.darchan.behavioral.i8080;

/**
 * Behavioral (int-based) reference for the Intel 8080 ALU operations, used as the golden model for the gate-level
 * ALU (com.darchan.physical.comp.i8080.Alu). Deliberately written in the most direct way, independent of how the gates compute anything.
 *
 * <p>Flags are returned in 8080 F-register layout: {@code S Z 0 AC 0 P 1 CY} (bit 7 to bit 0).
 *
 * <p>The two 8080-specific auxiliary-carry rules live in {@link #subtractAuxCarry} and {@link #andAuxCarry} so they
 * are easy to find and change.
 *
 * <p>Besides the eight ALU operations of the {@code 10 ooo sss} instructions (which the gate-level ALU implements)
 * this class holds the flag logic for the other flag-affecting instructions, so the behavioral CPU
 * ({@link Cpu8080}) computes every flag here: {@link #increment INR}, {@link #decrement DCR},
 * {@link #decimalAdjust DAA}, the four rotates and {@link #addWord DAD}. Those helpers take the current F and
 * return the new one with the flags they do not affect unchanged.
 */
public final class Alu8080 {

    private Alu8080() {}

    /** ALU operations, in 8080 encoding order (mirrors the gate-level operation codes). */
    public enum Op {
        ADD, ADC, SUB, SBB, AND, XOR, OR, CMP
    }

    public static final int CY = 1;
    public static final int P = 1 << 2;
    public static final int AC = 1 << 4;
    public static final int Z = 1 << 6;
    public static final int S = 1 << 7;
    /** bit 1 of F always reads 1 on the 8080; bits 3 and 5 always read 0 */
    public static final int ALWAYS_ONE = 1 << 1;

    /**
     * @return {@code (flags << 8) | result}
     */
    public static int compute(Op op, int a, int b, boolean carryIn) {
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
    public static boolean subtractAuxCarry(int a, int b, int borrowIn) {
        return (a & 0xF) - (b & 0xF) - borrowIn >= 0;
    }

    /**
     * 8080 rule for ANA/ANI: AC is the OR of bit 3 of the two operands. (The 8085 sets AC to 1 instead; XRA/ORA
     * clear AC on both.)
     */
    public static boolean andAuxCarry(int a, int b) {
        return ((a | b) & 0x08) != 0;
    }

    /** logical operations always clear CY */
    private static int logical(int result, boolean aux) {
        return pack(result, false, aux);
    }

    private static int pack(int result, boolean carry, boolean aux) {
        int flags = szp(result);
        if (aux) flags |= AC;
        if (carry) flags |= CY;
        return flags << 8 | result;
    }

    /** S, Z and P for an 8-bit {@code result}, plus the always-one bit; AC and CY clear. */
    public static int szp(int result) {
        int flags = ALWAYS_ONE;
        if ((result & 0x80) != 0) flags |= S;
        if (result == 0) flags |= Z;
        if (Integer.bitCount(result) % 2 == 0) flags |= P;
        return flags;
    }

    /**
     * INR: {@code value + 1}. Sets S Z AC P; CY is unchanged. AC is the carry out of bit 3 (low nibble was F).
     *
     * @return {@code (newF << 8) | result}
     */
    public static int increment(int value, int f) {
        int result = (value + 1) & 0xFF;
        int flags = szp(result) | (f & CY);
        if ((value & 0x0F) == 0x0F) flags |= AC;
        return flags << 8 | result;
    }

    /**
     * DCR: {@code value - 1}. Sets S Z AC P; CY is unchanged. Like SUB, the 8080 adds the two's complement
     * ({@code + 0xFF}), so AC is set unless the low nibble borrows, i.e. unless the result's low nibble is F.
     *
     * @return {@code (newF << 8) | result}
     */
    public static int decrement(int value, int f) {
        int result = (value - 1) & 0xFF;
        int flags = szp(result) | (f & CY);
        if ((result & 0x0F) != 0x0F) flags |= AC;
        return flags << 8 | result;
    }

    /**
     * DAA: adds 06 if the low nibble is above 9 or AC is set, and 60 if the high nibble is above 9 (or would be
     * after the first correction) or CY is set. S Z AC P come from that addition; CY is set if the high correction
     * was applied and is otherwise left as it was (DAA never clears CY).
     *
     * @return {@code (newF << 8) | result}
     */
    public static int decimalAdjust(int a, int f) {
        int lsb = a & 0x0F;
        int msb = a >> 4;
        int correction = 0;
        boolean carry = (f & CY) != 0;
        if ((f & AC) != 0 || lsb > 9) {
            correction |= 0x06;
        }
        if (carry || msb > 9 || (msb >= 9 && lsb > 9)) {
            correction |= 0x60;
            carry = true;
        }
        int r = add(a, correction, 0);
        int flags = (r >> 8) & ~CY | (carry ? CY : 0);
        return flags << 8 | (r & 0xFF);
    }

    /** RLC: rotate left, bit 7 to bit 0 and to CY. Only CY changes. @return {@code (newF << 8) | result} */
    public static int rotateLeft(int a, int f) {
        int bit7 = a >> 7 & 1;
        return ((f & ~CY) | bit7) << 8 | ((a << 1) & 0xFF) | bit7;
    }

    /** RRC: rotate right, bit 0 to bit 7 and to CY. Only CY changes. @return {@code (newF << 8) | result} */
    public static int rotateRight(int a, int f) {
        int bit0 = a & 1;
        return ((f & ~CY) | bit0) << 8 | (a >> 1) | bit0 << 7;
    }

    /** RAL: rotate left through carry. Only CY changes. @return {@code (newF << 8) | result} */
    public static int rotateLeftThroughCarry(int a, int f) {
        int bit7 = a >> 7 & 1;
        return ((f & ~CY) | bit7) << 8 | ((a << 1) & 0xFF) | (f & CY);
    }

    /** RAR: rotate right through carry. Only CY changes. @return {@code (newF << 8) | result} */
    public static int rotateRightThroughCarry(int a, int f) {
        int bit0 = a & 1;
        return ((f & ~CY) | bit0) << 8 | (a >> 1) | (f & CY) << 7;
    }

    /**
     * DAD: 16-bit {@code hl + rp}. Only CY changes (set on a carry out of bit 15).
     *
     * @return {@code (newF << 16) | result}
     */
    public static int addWord(int hl, int rp, int f) {
        int sum = hl + rp;
        int flags = (f & ~CY) | (sum > 0xFFFF ? CY : 0);
        return flags << 16 | (sum & 0xFFFF);
    }
}
