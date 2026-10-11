package com.darchan.behavioral.i8080;

import com.darchan.behavioral.i8080.Alu8080.Op;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import static com.darchan.behavioral.i8080.Alu8080.AC;
import static com.darchan.behavioral.i8080.Alu8080.CY;
import static com.darchan.behavioral.i8080.Alu8080.P;
import static com.darchan.behavioral.i8080.Alu8080.S;
import static com.darchan.behavioral.i8080.Alu8080.Z;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Algebraic properties of the golden ALU model. Each property states a relation that must hold for every input and
 * is written independently of how {@link Alu8080} computes things (a bit-serial adder, identities between
 * operations, decimal arithmetic for DAA), so a wrong flag rule cannot hide behind a copied implementation.
 */
class Alu8080PropertyTest {

    @Provide
    Arbitrary<Integer> bytes() {
        return Arbitraries.integers().between(0, 255).edgeCases(c -> c.add(0x0F).add(0x10).add(0x7F).add(0x80));
    }

    @Provide
    Arbitrary<Op> ops() {
        return Arbitraries.of(Op.class);
    }

    @Provide
    Arbitrary<Integer> words() {
        return Arbitraries.integers().between(0, 0xFFFF).edgeCases(c -> c.add(0x00FF).add(0x0100).add(0x8000));
    }

    private static int result(int r) {
        return r & 0xFF;
    }

    private static int flags(int r) {
        return r >> 8 & 0xFF;
    }

    private static int bit(int v, int i) {
        return v >> i & 1;
    }

    // ---- flags common to every operation -----------------------------------------------------------------------

    @Property
    void sIsBit7ZIsZeroPIsEvenParityForEveryOperation(@ForAll("ops") Op op, @ForAll("bytes") int a,
                                                      @ForAll("bytes") int b, @ForAll boolean cin) {
        int r = Alu8080.compute(op, a, b, cin);
        int res = result(r);
        int f = flags(r);
        assertEquals(bit(res, 7) == 1, (f & S) != 0, "S is bit 7 of the result");
        assertEquals(res == 0, (f & Z) != 0, "Z iff the result is zero");
        assertEquals(Integer.bitCount(res) % 2 == 0, (f & P) != 0, "P iff the result has an even number of ones");
        assertEquals(0x02, f & 0x2A, "bit 1 reads 1, bits 3 and 5 read 0");
        assertEquals(0, r >>> 16, "nothing above the flags byte");
    }

    // ---- ADD / ADC against a bit-serial adder ------------------------------------------------------------------

    @Property
    void addAndAdcMatchABitSerialAdder(@ForAll("bytes") int a, @ForAll("bytes") int b, @ForAll boolean cin) {
        int carry = cin ? 1 : 0;
        int sum = 0;
        int carryOutOfBit3 = 0;
        int c = carry;
        for (int i = 0; i < 8; i++) {
            int x = bit(a, i);
            int y = bit(b, i);
            sum |= (x ^ y ^ c) << i;
            c = x & y | c & (x ^ y);
            if (i == 3) {
                carryOutOfBit3 = c;
            }
        }
        int r = Alu8080.compute(Op.ADC, a, b, cin);
        assertEquals(sum, result(r), "sum");
        assertEquals(c == 1, (flags(r) & CY) != 0, "CY is the carry out of bit 7");
        assertEquals(carryOutOfBit3 == 1, (flags(r) & AC) != 0, "AC is the carry out of bit 3");
        // ADD is ADC with no carry in, whatever the carry flag was
        assertEquals(Alu8080.compute(Op.ADC, a, b, false), Alu8080.compute(Op.ADD, a, b, cin));
    }

    // ---- SUB / SBB / CMP ----------------------------------------------------------------------------------------

    /** subtraction is A + ~B + !borrow: same result, CY inverted, AC (not inverted) taken from the addition */
    @Property
    void subtractIsAddOfTheComplement(@ForAll("bytes") int a, @ForAll("bytes") int b, @ForAll boolean borrow) {
        int sbb = Alu8080.compute(Op.SBB, a, b, borrow);
        int adc = Alu8080.compute(Op.ADC, a, ~b & 0xFF, !borrow);
        assertEquals(result(adc), result(sbb), "result");
        assertEquals((flags(adc) & CY) == 0, (flags(sbb) & CY) != 0, "CY is the inverted carry out");
        assertEquals(flags(adc) & AC, flags(sbb) & AC, "AC is the carry out of bit 3, not inverted");
        assertEquals(flags(adc) & (S | Z | P), flags(sbb) & (S | Z | P));
        // SUB ignores the carry flag
        assertEquals(Alu8080.compute(Op.SBB, a, b, false), Alu8080.compute(Op.SUB, a, b, borrow));
    }

    @Property
    void borrowIsUnsignedLessThan(@ForAll("bytes") int a, @ForAll("bytes") int b, @ForAll boolean borrow) {
        int r = Alu8080.compute(Op.SBB, a, b, borrow);
        assertEquals(a < b + (borrow ? 1 : 0), (flags(r) & CY) != 0);
        assertEquals((a - b - (borrow ? 1 : 0)) & 0xFF, result(r));
    }

    @Property
    void addAndSubtractUndoEachOtherIncludingTheCarry(@ForAll("bytes") int a, @ForAll("bytes") int b,
                                                      @ForAll boolean c) {
        // (a - b - c) + b + c == a
        int d = result(Alu8080.compute(Op.SBB, a, b, c));
        assertEquals(a, result(Alu8080.compute(Op.ADC, d, b, c)));
        // (a + b + c) - b - c == a
        int s = result(Alu8080.compute(Op.ADC, a, b, c));
        assertEquals(a, result(Alu8080.compute(Op.SBB, s, b, c)));
        // d + b + c = a + 256 * borrow, so the borrow out of the subtraction is exactly the carry out of the addition
        int sub = Alu8080.compute(Op.SBB, a, b, c);
        int add = Alu8080.compute(Op.ADC, result(sub), b, c);
        assertEquals((flags(sub) & CY) != 0, (flags(add) & CY) != 0, "borrow out equals carry out of the undo");
    }

    @Property
    void cmpHasTheFlagsAndResultOfSubButNeverChangesTheOperands(@ForAll("bytes") int a, @ForAll("bytes") int b,
                                                                 @ForAll boolean cin) {
        int cmp = Alu8080.compute(Op.CMP, a, b, cin);
        assertEquals(Alu8080.compute(Op.SUB, a, b, false), cmp, "CMP flags are SUB flags, whatever CY was");
        assertEquals(a == b, (flags(cmp) & Z) != 0, "equal operands");
        assertEquals(a < b, (flags(cmp) & CY) != 0, "A < B as unsigned numbers");
    }

    @Property
    void subtractingAFromItselfGivesZeroWithAuxCarry(@ForAll("bytes") int a) {
        int r = Alu8080.compute(Op.SUB, a, a, false);
        assertEquals(0, result(r));
        assertEquals(Z | P | AC | 0x02, flags(r), "SUB A: Z P and (8080 rule) AC set, CY clear");
    }

    // ---- AND / OR / XOR -----------------------------------------------------------------------------------------

    @Property
    void logicalOperationsMatchTheBitwiseOperatorsAndClearCarry(@ForAll("bytes") int a, @ForAll("bytes") int b,
                                                                  @ForAll boolean cin) {
        int and = Alu8080.compute(Op.AND, a, b, cin);
        int or = Alu8080.compute(Op.OR, a, b, cin);
        int xor = Alu8080.compute(Op.XOR, a, b, cin);
        assertEquals(a & b, result(and));
        assertEquals(a | b, result(or));
        assertEquals(a ^ b, result(xor));
        for (int r : new int[]{and, or, xor}) {
            assertEquals(0, flags(r) & CY, "logical operations clear CY");
        }
        assertEquals(((a | b) & 8) != 0, (flags(and) & AC) != 0, "8080 ANA: AC = bit 3 of A OR B");
        assertEquals(0, flags(or) & AC);
        assertEquals(0, flags(xor) & AC);
    }

    @Property
    void logicalIdentities(@ForAll("bytes") int a, @ForAll("bytes") int b) {
        assertEquals(a, result(Alu8080.compute(Op.AND, a, a, false)), "a AND a");
        assertEquals(a, result(Alu8080.compute(Op.OR, a, a, false)), "a OR a");
        assertEquals(0, result(Alu8080.compute(Op.XOR, a, a, false)), "a XOR a");
        assertEquals(a, result(Alu8080.compute(Op.AND, a, 0xFF, false)), "a AND FF");
        assertEquals(0, result(Alu8080.compute(Op.AND, a, 0, false)), "a AND 0");
        assertEquals(a, result(Alu8080.compute(Op.OR, a, 0, false)), "a OR 0");
        assertEquals(0xFF, result(Alu8080.compute(Op.OR, a, 0xFF, false)), "a OR FF");
        assertEquals(a, result(Alu8080.compute(Op.XOR, a, 0, false)), "a XOR 0");
        assertEquals(~a & 0xFF, result(Alu8080.compute(Op.XOR, a, 0xFF, false)), "a XOR FF is the complement");
        // commutative
        for (Op op : new Op[]{Op.AND, Op.OR, Op.XOR, Op.ADD}) {
            assertEquals(result(Alu8080.compute(op, a, b, false)), result(Alu8080.compute(op, b, a, false)), op + "");
        }
        // De Morgan, and XOR = (a OR b) AND NOT (a AND b)
        int nand = ~result(Alu8080.compute(Op.AND, a, b, false)) & 0xFF;
        int orOfComplements = result(Alu8080.compute(Op.OR, ~a & 0xFF, ~b & 0xFF, false));
        assertEquals(nand, orOfComplements);
        int xorViaAndOr = result(Alu8080.compute(Op.AND, result(Alu8080.compute(Op.OR, a, b, false)), nand, false));
        assertEquals(a ^ b, xorViaAndOr);
        // a + b == (a XOR b) + 2 (a AND b)
        assertEquals(result(Alu8080.compute(Op.ADD, a, b, false)),
                (a ^ b) + 2 * (a & b) & 0xFF);
    }

    // ---- INR / DCR ------------------------------------------------------------------------------------------------

    @Property
    void incrementWrapsPreservesCarryAndSetsAuxOnLowNibbleOverflow(@ForAll("bytes") int v, @ForAll("bytes") int f) {
        int r = Alu8080.increment(v, f);
        assertEquals((v + 1) & 0xFF, result(r));
        assertEquals(f & CY, flags(r) & CY, "CY unchanged");
        assertEquals((v & 0xF) == 0xF, (flags(r) & AC) != 0, "AC iff the low nibble carried");
        assertEquals(result(r) == 0, (flags(r) & Z) != 0);
        assertEquals(result(r) >= 0x80, (flags(r) & S) != 0);
        assertEquals(Integer.bitCount(result(r)) % 2 == 0, (flags(r) & P) != 0);
        assertEquals(0x02, flags(r) & 0x2A);
    }

    @Property
    void decrementWrapsPreservesCarryAndMatchesSubtractOne(@ForAll("bytes") int v, @ForAll("bytes") int f) {
        int r = Alu8080.decrement(v, f);
        assertEquals((v - 1) & 0xFF, result(r));
        assertEquals(f & CY, flags(r) & CY, "CY unchanged");
        // DCR is SUB 1 as far as S Z AC P go
        int sub = Alu8080.compute(Op.SUB, v, 1, false);
        assertEquals(result(sub), result(r));
        assertEquals(flags(sub) & (S | Z | AC | P), flags(r) & (S | Z | AC | P));
        assertEquals(result(r) == 0, (flags(r) & Z) != 0);
    }

    @Property
    void incrementAndDecrementUndoEachOther(@ForAll("bytes") int v, @ForAll("bytes") int f) {
        assertEquals(v, result(Alu8080.decrement(result(Alu8080.increment(v, f)), flags(Alu8080.increment(v, f)))));
        assertEquals(v, result(Alu8080.increment(result(Alu8080.decrement(v, f)), flags(Alu8080.decrement(v, f)))));
        assertEquals(0, result(Alu8080.increment(0xFF, f)), "FF + 1 wraps to 0");
        assertEquals(0xFF, result(Alu8080.decrement(0, f)), "0 - 1 wraps to FF");
    }

    // ---- DAA ----------------------------------------------------------------------------------------------------

    private static int toBcd(int n) {
        return n / 10 << 4 | n % 10;
    }

    private static int fromBcd(int bcd) {
        return (bcd >> 4) * 10 + (bcd & 0xF);
    }

    /** ADD/ADC of two packed-BCD bytes followed by DAA is decimal addition */
    @Property
    void daaAfterAddingTwoBcdBytesIsDecimalAddition(@ForAll int x, @ForAll int y, @ForAll boolean carryIn) {
        int a = toBcd(Math.floorMod(x, 100));
        int b = toBcd(Math.floorMod(y, 100));
        int cin = carryIn ? 1 : 0;
        int sum = Alu8080.compute(Op.ADC, a, b, carryIn);
        int daa = Alu8080.decimalAdjust(result(sum), flags(sum));
        int decimal = fromBcd(a) + fromBcd(b) + cin;
        assertEquals(toBcd(decimal % 100), result(daa), "BCD digits");
        assertEquals(decimal >= 100, (flags(daa) & CY) != 0, "decimal carry");
        assertEquals(result(daa) == 0, (flags(daa) & Z) != 0);
    }

    @Property
    void daaNeverClearsCarryAndLeavesValidBcdAlone(@ForAll("bytes") int a, @ForAll("bytes") int f) {
        int daa = Alu8080.decimalAdjust(a, f);
        if ((f & CY) != 0) {
            assertTrue((flags(daa) & CY) != 0, "DAA never clears CY");
        }
        if ((f & (CY | AC)) == 0 && (a & 0xF) <= 9 && (a >> 4) <= 9) {
            assertEquals(a, result(daa), "a valid BCD byte with no carries is unchanged");
            assertFalse((flags(daa) & CY) != 0);
        }
    }

    // ---- rotates ------------------------------------------------------------------------------------------------

    @Property
    void rotatesTouchOnlyCarryAndAreCyclic(@ForAll("bytes") int a, @ForAll("bytes") int f) {
        int rlc = Alu8080.rotateLeft(a, f);
        int rrc = Alu8080.rotateRight(a, f);
        int ral = Alu8080.rotateLeftThroughCarry(a, f);
        int rar = Alu8080.rotateRightThroughCarry(a, f);
        for (int r : new int[]{rlc, rrc, ral, rar}) {
            assertEquals(f & ~CY, flags(r) & ~CY, "only CY may change");
        }
        assertEquals(a >> 7, flags(rlc) & CY, "RLC: CY = old bit 7");
        assertEquals(a & 1, flags(rrc) & CY, "RRC: CY = old bit 0");
        assertEquals(a >> 7, flags(ral) & CY, "RAL: CY = old bit 7");
        assertEquals(a & 1, flags(rar) & CY, "RAR: CY = old bit 0");
        assertEquals(a, result(Alu8080.rotateRight(result(rlc), flags(rlc))), "RRC undoes RLC");
        assertEquals(a, result(Alu8080.rotateLeft(result(rrc), flags(rrc))), "RLC undoes RRC");
        assertEquals(a, result(Alu8080.rotateRightThroughCarry(result(ral), flags(ral))), "RAR undoes RAL");
        assertEquals(a, result(Alu8080.rotateLeftThroughCarry(result(rar), flags(rar))), "RAL undoes RAR");
        // eight RLCs are the identity; nine RALs bring back both A and CY
        int v = a;
        int fl = f;
        for (int i = 0; i < 8; i++) {
            int r = Alu8080.rotateLeft(v, fl);
            v = result(r);
            fl = flags(r);
        }
        assertEquals(a, v);
        v = a;
        fl = f;
        for (int i = 0; i < 9; i++) {
            int r = Alu8080.rotateLeftThroughCarry(v, fl);
            v = result(r);
            fl = flags(r);
        }
        assertEquals(a, v);
        assertEquals(f & CY, fl & CY);
    }

    // ---- DAD ----------------------------------------------------------------------------------------------------

    @Property
    void addWordIsSixteenBitAdditionWithCarryOutOfBit15(@ForAll("words") int hl, @ForAll("words") int rp,
                                                         @ForAll("bytes") int f) {
        int r = Alu8080.addWord(hl, rp, f);
        assertEquals((hl + rp) & 0xFFFF, r & 0xFFFF);
        assertEquals(hl + rp > 0xFFFF, (r >>> 16 & CY) != 0);
        assertEquals(f & ~CY, r >>> 16 & ~CY, "only CY may change");
        assertEquals((hl + rp) & 0xFFFF, (rp + hl) & 0xFFFF, "commutative");
        assertEquals(hl, Alu8080.addWord(hl, 0, f) & 0xFFFF);
        assertEquals(0, Alu8080.addWord(hl, 0, f) >>> 16 & CY, "adding 0 never carries");
    }
}
