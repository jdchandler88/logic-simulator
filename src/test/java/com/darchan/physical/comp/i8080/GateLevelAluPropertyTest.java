package com.darchan.physical.comp.i8080;

import com.darchan.behavioral.i8080.Alu8080;
import com.darchan.physical.comp.arith.RippleCarryAdder;
import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.LogicValue;
import com.darchan.physical.signal.SimpleSignal;
import com.darchan.physical.sim.Simulator;
import com.darchan.testutil.Buses;
import com.darchan.testutil.FourValued;
import com.darchan.testutil.FourValued.Stimulus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate-built ALU and adder against the behavioral golden model for random inputs, including inputs that are
 * unknown (X) or floating (Z): an output bit that depends on an unknown input must be X, and a known output bit
 * must be right for every way the unknown inputs could turn out ({@link FourValued#assertSound}).
 */
class GateLevelAluPropertyTest {

    // one circuit for all tries: the ALU and the adder are purely combinational, so every try overwrites all inputs
    private static final Simulator SIM = new Simulator();
    private static final IBus ALU_IN = Buses.simpleBus(SIM, 20, 0);
    private static final Alu ALU = new Alu(SIM, ALU_IN);
    private static final IBus ADDER_IN = Buses.simpleBus(SIM, 17, 0);
    private static final RippleCarryAdder ADDER = new RippleCarryAdder(SIM, 8, ADDER_IN);

    static {
        SIM.settle();
    }

    /** ALU stimuli: input bits 0..19 (A, B, carry in, operation); half the time the operation stays known */
    @Provide
    Arbitrary<Stimulus> aluStimuli() {
        return Arbitraries.oneOf(FourValued.stimuli(20, 20), FourValued.stimuli(20, 17));
    }

    @Provide
    Arbitrary<Stimulus> adderStimuli() {
        return FourValued.stimuli(17, 17);
    }

    private static int aluReference(long in) {
        int a = (int) (in & 0xFF);
        int b = (int) (in >> 8 & 0xFF);
        boolean cin = (in >> 16 & 1) != 0;
        Alu8080.Op op = Alu8080.Op.values()[(int) (in >> 17 & 7)];
        return Alu8080.compute(op, a, b, cin);
    }

    @Property
    void theGateAluNeverReadsAWrongKnownBitForUnknownInputs(@ForAll("aluStimuli") Stimulus s) {
        FourValued.drive(ALU_IN, 0, s, 0, 20);
        SIM.settle();
        FourValued.assertSound(ALU.getOutputBus(), 16, s, FourValued.ofInt(in -> aluReference(in)));
    }

    @Property
    void theGateAluAgreesWithAlu8080OnKnownInputs(@ForAll("bytes") int a, @ForAll("bytes") int b,
                                                  @ForAll boolean cin,
                                                  @ForAll @IntRange(min = 0, max = 7) int op) {
        Buses.drive(ALU_IN, a | b << 8 | (cin ? 1 : 0) << 16 | op << 17);
        SIM.settle();
        int expected = Alu8080.compute(Alu8080.Op.values()[op], a, b, cin);
        assertEquals(expected, Buses.toInt(ALU.getOutputBus()));
        for (int i = 0; i < 16; i++) {
            assertEquals(expected >> i & 1, ALU.getOutputBus().getSignal(i).getValue() == LogicValue.ONE ? 1 : 0);
            assertTrue(ALU.getOutputBus().getSignal(i).getValue().isKnown());
        }
    }

    @Provide
    Arbitrary<Integer> bytes() {
        return Arbitraries.integers().between(0, 255).edgeCases(c -> c.add(0x0F).add(0x10).add(0x7F).add(0x80));
    }

    /** an unknown bit of one operand makes the flags it feeds unknown, never a confident wrong 0/1 */
    @Property
    void anUnknownOperandBitMakesDependentOutputsUnknown(@ForAll("bytes") int a, @ForAll("bytes") int b,
                                                         @ForAll @IntRange(min = 0, max = 7) int bit,
                                                         @ForAll boolean inA) {
        // ADD with no carry in: the unknown operand bit changes the sum bit at the same position, always
        Buses.drive(ALU_IN, a | b << 8 | Alu.Op.ADD.code() << 17);
        ((SimpleSignal) ALU_IN.getSignal(inA ? bit : 8 + bit)).setValue(LogicValue.X);
        SIM.settle();
        assertEquals(LogicValue.X, ALU.getOutputBus().getSignal(bit).getValue(),
                "sum bit " + bit + " depends on the unknown operand bit");
        for (int i = 0; i < bit; i++) {
            assertTrue(ALU.getOutputBus().getSignal(i).getValue().isKnown(),
                    "lower sum bits do not depend on bit " + bit);
        }
    }

    /** the sound rule is not satisfied by "everything is X": a controlling value still masks an unknown input */
    @Property
    void aControllingOperandBitMasksAnUnknownOneInAndAndOr(@ForAll("bytes") int a, @ForAll("bytes") int b,
                                                           @ForAll @IntRange(min = 0, max = 7) int bit,
                                                           @ForAll boolean and) {
        // AND: A bit 0 forces the result bit to 0; OR: A bit 1 forces it to 1 -- whatever B's bit is
        int aValue = and ? a & ~(1 << bit) : a | 1 << bit;
        Buses.drive(ALU_IN, aValue | b << 8 | (and ? Alu.Op.AND : Alu.Op.OR).code() << 17);
        ((SimpleSignal) ALU_IN.getSignal(8 + bit)).setValue(LogicValue.X);
        SIM.settle();
        assertEquals(and ? LogicValue.ZERO : LogicValue.ONE, ALU.getOutputBus().getSignal(bit).getValue());
    }

    @Property
    void theRippleCarryAdderNeverReadsAWrongKnownBitForUnknownInputs(@ForAll("adderStimuli") Stimulus s) {
        FourValued.drive(ADDER_IN, 0, s, 0, 17);
        SIM.settle();
        // output: sum[7:0] and carry out at bit 8
        FourValued.assertSound(ADDER.getOutputBus(), 9, s, in -> {
            int a = (int) (in & 0xFF);
            int b = (int) (in >> 8 & 0xFF);
            int c = (int) (in >> 16 & 1);
            return a + b + c;
        });
        // and the per-bit carries
        FourValued.assertSound(ADDER.getCarries(), 8, s, in -> {
            int a = (int) (in & 0xFF);
            int b = (int) (in >> 8 & 0xFF);
            int carry = (int) (in >> 16 & 1);
            int carries = 0;
            for (int i = 0; i < 8; i++) {
                int x = a >> i & 1;
                int y = b >> i & 1;
                carry = x & y | carry & (x ^ y);
                carries |= carry << i;
            }
            return carries;
        });
    }
}
