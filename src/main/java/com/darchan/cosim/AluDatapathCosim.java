package com.darchan.cosim;

import com.darchan.behavioral.bus.IoBus;
import com.darchan.behavioral.bus.Ram;
import com.darchan.behavioral.i8080.Cpu8080;
import com.darchan.behavioral.i8080.Debug8080;
import com.darchan.behavioral.i8080.Disassembler8080;
import com.darchan.diag.compare.Granularity;
import com.darchan.diag.compare.Lockstep;
import com.darchan.diag.compare.StateSaver;
import com.darchan.diag.run.Debugger;
import com.darchan.diag.trace.RegisterSpec;
import com.darchan.physical.comp.i8080.AluDatapath;
import com.darchan.physical.comp.i8080.RegisterFile.Reg;
import com.darchan.physical.probe.ClockedTarget;
import com.darchan.physical.probe.ProbeSet;
import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.Clock;
import com.darchan.physical.signal.ConstantSignal;
import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.IRange;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.SimpleRange;
import com.darchan.physical.signal.SimpleSignal;
import com.darchan.physical.sim.Simulator;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Co-simulates one 8080 program on two layers and compares them in lockstep:
 * <ul>
 *   <li><b>gate level</b>: an {@link AluDatapath} (register file, ALU, multiplexers, flags register built from
 *       gates and flip-flops) clocked by a small control unit written here, one instruction per clock, exposed as a
 *       {@link ClockedTarget} with probes {@code A F B C D E H L} plus internal nets ({@code alu.result},
 *       {@code alu.flags}, {@code alu.operandB}, {@code regs.dataIn});</li>
 *   <li><b>behavioral</b>: the instruction-level {@link Cpu8080} (whose flags come from {@code Alu8080}) running the
 *       same bytes from RAM at 0000.</li>
 * </ul>
 * {@link #lockstep()} compares registers A F B C D E H L after every instruction (PC, SP and cycle counts are
 * left out: the datapath has no PC or SP and takes one clock per instruction). Both start with all registers 0 and
 * F = 02.
 *
 * <p>Supported instructions (anything else is rejected up front): {@code NOP}, {@code MOV r,r}, {@code MVI r,n},
 * {@code ADD/ADC/SUB/SBB/ANA/XRA/ORA/CMP r} and {@code ADI/ACI/SUI/SBI/ANI/XRI/ORI/CPI n}, where r is not M.
 */
public final class AluDatapathCosim {

    /** {@code Snapshot.machine()} of the gate-level side */
    public static final String MACHINE = "gate-alu-datapath";

    /** the fields compared in lockstep */
    public static final List<String> COMPARED = List.of("A", "F", "B", "C", "D", "E", "H", "L");

    /** 8080 register code (B C D E H L M A = 0..7) to register-file slot; M (6) is unsupported */
    private static final Reg[] REG_CODE = {Reg.B, Reg.C, Reg.D, Reg.E, Reg.H, Reg.L, null, Reg.A};

    private final byte[] program;
    private final UnaryOperator<IBus> operandBTap;
    private final Simulator sim = new Simulator();
    private final Clock clock = new Clock(sim, false);
    private final IBus controls;
    private final AluDatapath datapath;
    private final ProbeSet probes = new ProbeSet(sim);
    private final ClockedTarget gate;
    private final Debugger gateDebugger;
    private final Ram ram = new Ram();
    private final Cpu8080 cpu = new Cpu8080(ram, IoBus.UNCONNECTED);
    private final Debugger cpuDebugger;
    private final int instructionCount;
    private int pc;

    /** A fault-free co-simulation of {@code program}. */
    public AluDatapathCosim(byte[] program) {
        this(program, UnaryOperator.identity());
    }

    /**
     * @param operandBTap applied to the gate-level datapath's operand-B bus, e.g. to inject a stuck-at fault
     *                    (see {@link #stuckAt(int, boolean)})
     */
    public AluDatapathCosim(byte[] program, UnaryOperator<IBus> operandBTap) {
        this.program = program.clone();
        this.operandBTap = operandBTap;
        this.instructionCount = validate(this.program);

        ISignal[] in = new ISignal[30];
        for (int i = 0; i < in.length; i++) {
            // until the first instruction is decoded, operand B comes from the immediate (0) so no net is unknown
            in[i] = new SimpleSignal(sim, i == AluDatapath.USE_IMM.getMin());
        }
        controls = new Bus(in);
        datapath = new AluDatapath(sim, clock, controls, operandBTap, "dp");
        sim.settle();

        for (String r : COMPARED) {
            probes.add(r, datapath.getRegister(Reg.valueOf(r)));
        }
        probes.add("alu.result", datapath.getAluOutput().slice(new SimpleRange(0, 7)));
        probes.add("alu.flags", datapath.getAluOutput().slice(new SimpleRange(8, 15)));
        probes.add("alu.operandB", datapath.getOperandB());
        probes.add("regs.dataIn", datapath.getDataIn());

        gate = new ClockedTarget(MACHINE, clock, probes);
        // the control unit's state: its PC (Java) and the control lines it drives, so a restore puts them back
        gate.addState("ctl.pc", 16, () -> pc, v -> pc = (int) v);
        gate.addInputs("ctl", controls);
        gate.setStimulus(cycle -> decodeAndDrive());
        gate.setProgramCounter(() -> pc);
        gate.setCyclesPerStep(() -> states(byteAt(pc)));
        gate.setInstruction(() -> packedBytes(pc), () -> Disassembler8080.length(byteAt(pc)), 3,
                Disassembler8080.INSTANCE);
        gate.setTraceRegisters(List.of(RegisterSpec.of("A", 8), RegisterSpec.flags("F", "SZ.A.P.C"),
                RegisterSpec.of("B", 8), RegisterSpec.of("C", 8), RegisterSpec.of("D", 8), RegisterSpec.of("E", 8),
                RegisterSpec.of("H", 8), RegisterSpec.of("L", 8)));
        gateDebugger = new Debugger(gate, null);

        ram.load(0, this.program);
        cpuDebugger = Debug8080.attach(cpu);
    }

    /** An operand-B tap that ties bit {@code bit} to a constant: a stuck-at-0 or stuck-at-1 fault. */
    public static UnaryOperator<IBus> stuckAt(int bit, boolean value) {
        return bus -> {
            ISignal[] s = bus.getSignals().toArray(new ISignal[0]);
            s[bit] = value ? ConstantSignal.ON : ConstantSignal.OFF;
            return new Bus(s);
        };
    }

    /** @return true if the gate-level datapath can execute {@code opcode} */
    public static boolean supports(int opcode) {
        int op = opcode & 0xFF;
        int hi = op >> 6;
        int mid = op >> 3 & 7;
        int lo = op & 7;
        return switch (hi) {
            case 0 -> op == 0x00 || (lo == 6 && mid != 6);
            case 1 -> mid != 6 && lo != 6;
            case 2 -> lo != 6;
            default -> lo == 6;
        };
    }

    private static int validate(byte[] program) {
        int n = 0;
        for (int at = 0; at < program.length; n++) {
            int op = program[at] & 0xFF;
            if (!supports(op)) {
                throw new IllegalArgumentException(String.format("unsupported instruction %02X (%s) at %04X", op,
                        Disassembler8080.template(op), at));
            }
            at += Disassembler8080.length(op);
            if (at > program.length) {
                throw new IllegalArgumentException("program ends inside an instruction");
            }
        }
        return n;
    }

    private int byteAt(int address) {
        return address < program.length ? program[address] & 0xFF : 0x00; // NOP past the end, like zeroed RAM
    }

    private int packedBytes(int address) {
        return byteAt(address) | byteAt(address + 1) << 8 | byteAt(address + 2) << 16;
    }

    /** the control unit: decodes the instruction at pc into control signals and advances pc */
    private void decodeAndDrive() {
        int op = byteAt(pc);
        int imm = byteAt(pc + 1);
        int hi = op >> 6;
        int mid = op >> 3 & 7;
        int lo = op & 7;
        int src = 0;
        boolean useImm = false;
        int aluOp = 0;
        boolean passB = false;
        int we = 0;
        boolean flagsWe = false;
        switch (hi) {
            case 0 -> {
                // NOP selects no source register, so operand B comes from the (unused) immediate rather than
                // from the source selector, whose outputs float when nothing is selected (Z, which gates see as X)
                useImm = true;
                if (op != 0x00) { // MVI r,n
                    passB = true;
                    we = slotBit(mid);
                }
            }
            case 1 -> { // MOV d,s
                src = slotBit(lo);
                passB = true;
                we = slotBit(mid);
            }
            case 2 -> { // ALU r
                src = slotBit(lo);
                aluOp = mid;
                we = mid == 7 ? 0 : slotBit(7);
                flagsWe = true;
            }
            default -> { // ALU immediate
                useImm = true;
                aluOp = mid;
                we = mid == 7 ? 0 : slotBit(7);
                flagsWe = true;
            }
        }
        drive(AluDatapath.IMM, imm);
        drive(AluDatapath.SRC, src);
        drive(AluDatapath.USE_IMM, useImm ? 1 : 0);
        drive(AluDatapath.OP, aluOp);
        drive(AluDatapath.PASS_B, passB ? 1 : 0);
        drive(AluDatapath.WE, we);
        drive(AluDatapath.FLAGS_WE, flagsWe ? 1 : 0);
        pc = (pc + Disassembler8080.length(op)) & 0xFFFF;
    }

    /**
     * 8080 clock states of a supported instruction (data sheet): the gate level executes it in one clock but is
     * charged this many cycles, so cycle-granularity lockstep compares at the same instructions on both sides.
     */
    static int states(int opcode) {
        int hi = opcode >> 6;
        return switch (hi) {
            case 0 -> opcode == 0x00 ? 4 : 7; // NOP, MVI r,n
            case 1 -> 5;                      // MOV r,r
            case 2 -> 4;                      // ALU r
            default -> 7;                     // ALU immediate
        };
    }

    private static int slotBit(int regCode) {
        return 1 << REG_CODE[regCode].slot();
    }

    private void drive(IRange range, int value) {
        for (int i = range.getMin(); i <= range.getMax(); i++) {
            ((SimpleSignal) controls.getSignal(i)).setOn((value >> (i - range.getMin()) & 1) != 0);
        }
    }

    /** A lockstep of the behavioral CPU (reference) against the gate-level datapath (candidate), every instruction. */
    public Lockstep lockstep() {
        return lockstep(Granularity.everyStep());
    }

    /**
     * A lockstep of the behavioral CPU (reference) against the gate-level datapath (candidate) comparing at
     * {@code granularity}. A coarse divergence is narrowed to the first diverging instruction by restoring both
     * sides (the CPU with all of RAM, the gate level with its storage elements and control unit) and re-running the
     * window; if that fails, by replaying a fresh co-simulation of the same program and fault. Both are
     * deterministic: the control unit's stimulus depends only on the program and its PC.
     */
    public Lockstep lockstep(Granularity granularity) {
        return lockstepBuilder(granularity)
                .restoreWith(StateSaver.of(cpu).withMemory("ram", ram::peek, ram::restore, 0, Ram.SIZE),
                        StateSaver.of(gate))
                .replayWith(() -> new AluDatapathCosim(program, operandBTap).lockstepBuilder(granularity))
                .build();
    }

    /**
     * The lockstep configuration without a state saver for the CPU or a replay factory (so a coarse divergence is
     * not narrowed unless they are added), to customise.
     */
    public Lockstep.Builder lockstepBuilder(Granularity granularity) {
        return Lockstep.builder("behavioral i8080", cpuDebugger, "gate-level datapath", gateDebugger)
                .granularity(granularity)
                .compareOnly(COMPARED.toArray(new String[0]));
    }

    /** the control unit's PC: the address of the next instruction the gate level executes */
    public int programCounter() {
        return pc;
    }

    /** number of instructions in the program */
    public int instructionCount() {
        return instructionCount;
    }

    public ClockedTarget gateLevel() {
        return gate;
    }

    public Debugger gateDebugger() {
        return gateDebugger;
    }

    public Cpu8080 behavioral() {
        return cpu;
    }

    public Debugger behavioralDebugger() {
        return cpuDebugger;
    }

    public ProbeSet probes() {
        return probes;
    }

    public Simulator simulator() {
        return sim;
    }

    public AluDatapath datapath() {
        return datapath;
    }
}
