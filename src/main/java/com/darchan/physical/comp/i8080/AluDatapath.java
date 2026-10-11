package com.darchan.physical.comp.i8080;

import com.darchan.physical.comp.BusSelector8By8Bit;
import com.darchan.physical.comp.FlipFlop;
import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.ConstantSignal;
import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.IComponent;
import com.darchan.physical.signal.IRange;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.SimpleRange;
import com.darchan.physical.signal.Wire;
import com.darchan.physical.sim.Simulator;
import com.darchan.physical.validation.Validation;

import java.util.Objects;
import java.util.function.UnaryOperator;

import static com.darchan.physical.logic.Gates.and;
import static com.darchan.physical.logic.Gates.not;
import static com.darchan.physical.logic.Gates.or;

/**
 * A small gate-level 8080 register/ALU datapath: the {@link RegisterFile}, the {@link Alu}, a source-register
 * selector, operand and data-in multiplexers and a flags register. With a control unit setting its control inputs
 * once per clock it executes the 8080's register-to-register instructions: {@code MOV r,r}, {@code MVI r,n}, the
 * eight ALU operations on a register ({@code ADD r} ... {@code CMP r}) and on an immediate ({@code ADI n} ...
 * {@code CPI n}), one instruction per clock.
 *
 * <p><b>Data flow:</b> ALU operand A is register A; operand B is the selected source register or the immediate;
 * the register file's data-in is either operand B (for MOV/MVI) or the ALU result; the ALU's carry-in is the CY
 * flag. The flags register has a flip-flop with a write enable for each of S, Z, AC, P and CY, loaded from the
 * ALU flags; bits 1 (always 1), 3 and 5 (always 0) are constants, as on the 8080. The register file's own F slot is
 * not used (its write enable is tied low); the output bus shows the flags register in its place.
 *
 * <p><b>Input bus (30 bits), driven by a control unit:</b>
 * <pre>
 *   [7:0]    IMM       immediate byte
 *   [15:8]   SRC       one-hot source register slot (bit 8+i = slot i in {@link RegisterFile.Reg} order L H E D C B F A)
 *   [16]     USE_IMM   operand B is IMM instead of the source register
 *   [19:17]  OP        ALU operation ({@link Alu.Op#code()})
 *   [20]     PASS_B    register data-in is operand B instead of the ALU result
 *   [28:21]  WE        register write enables, slot order (bit 21+6, the F slot, is ignored)
 *   [29]     FLAGS_WE  load the flags register from the ALU
 * </pre>
 * <b>Output bus (64 bits):</b> registers in {@link RegisterFile} slot order (slot i at {@code [8i+7:8i]}), with the
 * flags register in the F slot {@code [55:48]}.
 *
 * <p>For fault-injection experiments a tap can replace the operand-B bus before it reaches the ALU and the
 * data-in multiplexer (e.g. to model a stuck-at fault).
 */
public class AluDatapath implements IComponent {

    public static final IRange IMM = new SimpleRange(0, 7);
    public static final IRange SRC = new SimpleRange(8, 15);
    public static final IRange USE_IMM = new SimpleRange(16, 16);
    public static final IRange OP = new SimpleRange(17, 19);
    public static final IRange PASS_B = new SimpleRange(20, 20);
    public static final IRange WE = new SimpleRange(21, 28);
    public static final IRange FLAGS_WE = new SimpleRange(29, 29);

    private static final IRange ALLOWED_INPUT_BUS_WIDTH = new SimpleRange(30, 30);

    /** storage-element names of the flag flip-flops, by bit (null for the constant bits) */
    private static final String[] FLAG_NAMES = {"CY", null, "P", null, "AC", null, "Z", "S"};
    private static final IRange ALLOWED_OUTPUT_BUS_WIDTH = new SimpleRange(64, 64);

    private final IBus inputBus;
    private final IBus outputBus;
    private final RegisterFile registerFile;
    private final Alu alu;
    private final IBus operandB;
    private final IBus dataIn;
    private final IBus flags;

    public AluDatapath(Simulator sim, ISignal clk, IBus inputBus) {
        this(sim, clk, inputBus, UnaryOperator.identity(), null);
    }

    /**
     * @param name prefix of the storage-element names; {@code null} for an automatic {@code datapath<n>}
     */
    public AluDatapath(Simulator sim, ISignal clk, IBus inputBus, String name) {
        this(sim, clk, inputBus, UnaryOperator.identity(), name);
    }

    public AluDatapath(Simulator sim, ISignal clk, IBus inputBus, UnaryOperator<IBus> operandBTap) {
        this(sim, clk, inputBus, operandBTap, null);
    }

    /**
     * @param operandBTap replaces the 8-bit operand-B bus (e.g. with one bit tied to a constant); identity for a
     *                    fault-free datapath
     * @param name        prefix of the storage-element names: the register file is {@code <name>.regs}
     *                    ({@code <name>.regs.A} ...), the flag flip-flops {@code <name>.flags.CY}, {@code .P},
     *                    {@code .AC}, {@code .Z}, {@code .S}; {@code null} for an automatic {@code datapath<n>}
     */
    public AluDatapath(Simulator sim, ISignal clk, IBus inputBus, UnaryOperator<IBus> operandBTap, String name) {
        Validation.validateInputWidth(ALLOWED_INPUT_BUS_WIDTH, inputBus);
        Objects.requireNonNull(operandBTap);
        String prefix = name != null ? name : sim.uniqueName("datapath");
        this.inputBus = inputBus;
        IBus imm = inputBus.slice(IMM);
        ISignal useImm = inputBus.getSignal(USE_IMM.getMin());
        ISignal passB = inputBus.getSignal(PASS_B.getMin());
        ISignal flagsWe = inputBus.getSignal(FLAGS_WE.getMin());

        // data-in depends on the register outputs (through operand B and the ALU), so it is a Wire connected at the
        // end; the loop is broken by the register file's flip-flops
        ISignal[] dataInWires = new ISignal[8];
        for (int i = 0; i < 8; i++) {
            dataInWires[i] = new Wire(sim);
        }
        ISignal[] rfIn = new ISignal[16];
        for (int i = 0; i < 8; i++) {
            rfIn[i] = dataInWires[i];
            rfIn[8 + i] = i == RegisterFile.Reg.F.slot() ? ConstantSignal.OFF : inputBus.getSignal(WE.getMin() + i);
        }
        this.registerFile = new RegisterFile(sim, clk, new Bus(rfIn), prefix + ".regs");

        // source selector: 64 data bits + 8 one-hot select bits
        ISignal[] selIn = new ISignal[72];
        for (int i = 0; i < 64; i++) {
            selIn[i] = registerFile.getOutputBus().getSignal(i);
        }
        for (int i = 0; i < 8; i++) {
            selIn[64 + i] = inputBus.getSignal(SRC.getMin() + i);
        }
        IBus selected = new BusSelector8By8Bit(sim, new Bus(selIn)).getOutputBus();

        // operand B = USE_IMM ? IMM : selected register
        ISignal notUseImm = not(sim, useImm);
        ISignal[] b = new ISignal[8];
        for (int i = 0; i < 8; i++) {
            b[i] = or(sim, and(sim, useImm, imm.getSignal(i)), and(sim, notUseImm, selected.getSignal(i)));
        }
        this.operandB = operandBTap.apply(new Bus(b));
        Validation.validateInputWidth(new SimpleRange(8, 8), operandB);

        // flags register: S Z AC P CY flip-flops with a shared write enable, constant bits 1, 3, 5
        ISignal[] flagIn = new ISignal[8];
        for (int i = 0; i < 8; i++) {
            flagIn[i] = new Wire(sim);
        }
        ISignal[] flagOut = new ISignal[8];
        for (int bit = 0; bit < 8; bit++) {
            flagOut[bit] = switch (bit) {
                case 1 -> ConstantSignal.ON;
                case 3, 5 -> ConstantSignal.OFF;
                default -> new FlipFlop(sim, clk, new Bus(flagIn[bit]), flagsWe, false,
                        prefix + ".flags." + FLAG_NAMES[bit]).getOutputBus().getSignal(0);
            };
        }
        this.flags = new Bus(flagOut);

        // ALU: A, operand B, carry in = CY, operation
        IBus a = registerFile.getRegisterOutput(RegisterFile.Reg.A);
        ISignal[] aluIn = new ISignal[20];
        for (int i = 0; i < 8; i++) {
            aluIn[i] = a.getSignal(i);
            aluIn[8 + i] = operandB.getSignal(i);
        }
        aluIn[16] = flagOut[Alu.FLAG_CY];
        for (int i = 0; i < 3; i++) {
            aluIn[17 + i] = inputBus.getSignal(OP.getMin() + i);
        }
        this.alu = new Alu(sim, new Bus(aluIn));
        for (int i = 0; i < 8; i++) {
            ((Wire) flagIn[i]).connect(alu.getOutputBus().getSignal(Alu.FLAGS.getMin() + i));
        }

        // data-in = PASS_B ? operand B : ALU result
        ISignal notPassB = not(sim, passB);
        for (int i = 0; i < 8; i++) {
            ((Wire) dataInWires[i]).connect(or(sim, and(sim, passB, operandB.getSignal(i)),
                    and(sim, notPassB, alu.getOutputBus().getSignal(Alu.RESULT.getMin() + i))));
        }
        this.dataIn = new Bus(dataInWires);

        ISignal[] out = new ISignal[64];
        for (int i = 0; i < 64; i++) {
            int slot = i / 8;
            out[i] = slot == RegisterFile.Reg.F.slot() ? flagOut[i % 8] : registerFile.getOutputBus().getSignal(i);
        }
        this.outputBus = new Bus(out);
    }

    /** one register's 8 bits; {@code F} is the flags register */
    public IBus getRegister(RegisterFile.Reg reg) {
        return reg == RegisterFile.Reg.F ? flags : registerFile.getRegisterOutput(reg);
    }

    /** the ALU output: result [7:0], flags [15:8] */
    public IBus getAluOutput() {
        return alu.getOutputBus();
    }

    /** operand B as the ALU sees it (after the tap) */
    public IBus getOperandB() {
        return operandB;
    }

    /** the register file's data-in */
    public IBus getDataIn() {
        return dataIn;
    }

    @Override
    public IBus getInputBus() {
        return inputBus;
    }

    @Override
    public IRange getAllowedInputBusWidth() {
        return ALLOWED_INPUT_BUS_WIDTH;
    }

    @Override
    public IBus getOutputBus() {
        return outputBus;
    }

    @Override
    public IRange getAllowedOutputBusWidth() {
        return ALLOWED_OUTPUT_BUS_WIDTH;
    }
}
