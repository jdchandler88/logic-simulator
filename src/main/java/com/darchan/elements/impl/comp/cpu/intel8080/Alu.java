package com.darchan.elements.impl.comp.cpu.intel8080;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.IComponent;
import com.darchan.elements.iface.IRange;
import com.darchan.elements.iface.ISignal;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.ConstantSignal;
import com.darchan.elements.impl.SimpleRange;
import com.darchan.elements.impl.comp.arith.RippleCarryAdder;
import com.darchan.sim.Simulator;
import com.darchan.validation.Validation;

import static com.darchan.elements.impl.Gates.*;

/**
 * Gate-level 8-bit Intel 8080 ALU (purely combinational): the eight register/immediate ALU operations with the 8080
 * flag set, built from AND/OR/XOR/NOR/inverter gates around an 8-bit {@link RippleCarryAdder}.
 *
 * <p><b>Input bus (20 bits):</b>
 * <pre>
 *   [7:0]    A (accumulator operand)
 *   [15:8]   B (second operand: register, memory or immediate)
 *   [16]     carry in (the CY flag; used by ADC and SBB only)
 *   [19:17]  operation, {@link Op#code()}: the 8080 encoding of bits 5:3 of "10 ooo sss"
 * </pre>
 * <b>Output bus (16 bits):</b>
 * <pre>
 *   [7:0]    result (for CMP: A - B, which a CPU must not write back)
 *   [15:8]   flags in 8080 F layout: S Z 0 AC 0 P 1 CY (bit 15 = S ... bit 8 = CY)
 * </pre>
 *
 * <p>Flag rules:
 * <ul>
 *     <li>S = result bit 7, Z = result is zero, P = even parity of the result.</li>
 *     <li>ADD/ADC: CY = carry out of bit 7, AC = carry out of bit 3.</li>
 *     <li>SUB/SBB/CMP compute {@code A + ~B + !borrowIn}. CY = borrow (the inverted carry out of bit 7);
 *     AC = carry out of bit 3 of that addition, not inverted (8080 behaviour).</li>
 *     <li>AND: CY = 0, AC = A[3] OR B[3] (8080 behaviour; the 8085 sets AC = 1). XOR, OR: CY = 0, AC = 0.</li>
 * </ul>
 */
public class Alu implements IComponent {

    /**
     * ALU operations, in 8080 encoding order (ANA = AND, XRA = XOR, ORA = OR).
     */
    public enum Op {
        ADD, ADC, SUB, SBB, AND, XOR, OR, CMP;

        /**
         * @return 3-bit operation code for input bits [19:17]
         */
        public int code() {
            return ordinal();
        }
    }

    public static final IRange OPERAND_A = new SimpleRange(0, 7);
    public static final IRange OPERAND_B = new SimpleRange(8, 15);
    public static final IRange CARRY_IN = new SimpleRange(16, 16);
    public static final IRange OPERATION = new SimpleRange(17, 19);

    public static final IRange RESULT = new SimpleRange(0, 7);
    public static final IRange FLAGS = new SimpleRange(8, 15);

    /** flag bit positions within the flags byte (add 8 for the output bus) */
    public static final int FLAG_CY = 0;
    public static final int FLAG_P = 2;
    public static final int FLAG_AC = 4;
    public static final int FLAG_Z = 6;
    public static final int FLAG_S = 7;

    private static final IRange ALLOWED_INPUT_BUS_WIDTH = new SimpleRange(20, 20);

    private static final IRange ALLOWED_OUTPUT_BUS_WIDTH = new SimpleRange(16, 16);

    private final IBus inputBus;

    private final IBus outputBus;

    public Alu(Simulator sim, IBus inputBus) {
        Validation.validateInputWidth(ALLOWED_INPUT_BUS_WIDTH, inputBus);
        this.inputBus = inputBus;

        IBus a = inputBus.slice(OPERAND_A);
        IBus b = inputBus.slice(OPERAND_B);
        ISignal carryIn = inputBus.getSignal(CARRY_IN.getMin());
        ISignal o0 = inputBus.getSignal(OPERATION.getMin());
        ISignal o1 = inputBus.getSignal(OPERATION.getMin() + 1);
        ISignal o2 = inputBus.getSignal(OPERATION.getMin() + 2);

        //operation decode
        ISignal notO0 = not(sim, o0);
        ISignal notO1 = not(sim, o1);
        ISignal notO2 = not(sim, o2);
        ISignal arithmetic = or(sim, notO2, and(sim, o1, o0));      //ADD ADC SUB SBB CMP
        ISignal subtract = and(sim, o1, or(sim, notO2, o0));        //SUB SBB CMP
        ISignal useCarry = and(sim, notO2, o0);                     //ADC SBB
        ISignal selectAnd = and(sim, o2, notO1, notO0);
        ISignal selectXor = and(sim, o2, notO1, o0);
        ISignal selectOr = and(sim, o2, o1, notO0);

        //adder: A + (B XOR subtract) + (subtract XOR (useCarry AND CY))
        ISignal[] adderIn = new ISignal[17];
        for (int i = 0; i < 8; i++) {
            adderIn[i] = a.getSignal(i);
            adderIn[8 + i] = xor(sim, b.getSignal(i), subtract);
        }
        adderIn[16] = xor(sim, subtract, and(sim, useCarry, carryIn));
        RippleCarryAdder adder = new RippleCarryAdder(sim, 8, new Bus(adderIn));

        //result multiplexer
        ISignal[] result = new ISignal[8];
        for (int i = 0; i < 8; i++) {
            ISignal ai = a.getSignal(i);
            ISignal bi = b.getSignal(i);
            result[i] = or(sim,
                    and(sim, arithmetic, adder.getOutputBus().getSignal(i)),
                    and(sim, selectAnd, and(sim, ai, bi)),
                    and(sim, selectXor, xor(sim, ai, bi)),
                    and(sim, selectOr, or(sim, ai, bi)));
        }

        //flags
        ISignal sign = result[7];
        ISignal zero = nor(sim, result);
        ISignal parity = not(sim, xor(sim, result));
        ISignal carry = and(sim, arithmetic, xor(sim, adder.getCarries().getSignal(7), subtract));
        ISignal auxCarry = or(sim,
                and(sim, arithmetic, adder.getCarries().getSignal(3)),
                and(sim, selectAnd, or(sim, a.getSignal(3), b.getSignal(3))));

        ISignal[] out = new ISignal[16];
        System.arraycopy(result, 0, out, 0, 8);
        out[8 + FLAG_CY] = carry;
        out[8 + 1] = ConstantSignal.ON;
        out[8 + FLAG_P] = parity;
        out[8 + 3] = ConstantSignal.OFF;
        out[8 + FLAG_AC] = auxCarry;
        out[8 + 5] = ConstantSignal.OFF;
        out[8 + FLAG_Z] = zero;
        out[8 + FLAG_S] = sign;
        this.outputBus = new Bus(out);
    }

    @Override
    public IBus getInputBus() {
        return this.inputBus;
    }

    @Override
    public IRange getAllowedInputBusWidth() {
        return ALLOWED_INPUT_BUS_WIDTH;
    }

    @Override
    public IBus getOutputBus() {
        return this.outputBus;
    }

    @Override
    public IRange getAllowedOutputBusWidth() {
        return ALLOWED_OUTPUT_BUS_WIDTH;
    }
}
