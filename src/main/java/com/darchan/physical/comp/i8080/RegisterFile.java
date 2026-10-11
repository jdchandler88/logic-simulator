package com.darchan.physical.comp.i8080;

import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.IComponent;
import com.darchan.physical.signal.IRange;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.SimpleRange;
import com.darchan.physical.comp.Register;
import com.darchan.physical.sim.Simulator;
import com.darchan.physical.validation.Validation;

/**
 * Intel 8080 register file: eight 8-bit registers sharing one clock and one data-in bus, each with its own write
 * enable. Registers are numbered by <em>slot</em> in the order {@code L, H, E, D, C, B, F, A} (slot 0 to 7, see
 * {@link Reg}).
 *
 * <p><b>Input bus (16 bits):</b>
 * <pre>
 *   [7:0]   data in, shared by all registers
 *   [15:8]  write enables: bit 8+i enables slot i
 *           (8 = L, 9 = H, 10 = E, 11 = D, 12 = C, 13 = B, 14 = F, 15 = A)
 * </pre>
 * The clock is a separate input. On a rising clock edge every register whose write enable is high loads data-in;
 * all other registers hold. Enables may change freely between edges (the clock is never gated).
 *
 * <p><b>Output bus (64 bits):</b> every register, slot i at {@code [8i+7:8i]}:
 * <pre>
 *   [7:0] L   [15:8] H   [23:16] E   [31:24] D   [39:32] C   [47:40] B   [55:48] F   [63:56] A
 * </pre>
 * so each 16-bit slice is a register pair with the high register in the high byte: {@code [15:0] = HL},
 * {@code [31:16] = DE}, {@code [47:32] = BC}, {@code [63:48] = PSW} (A high, flags low, as PUSH PSW stores them).
 * The output can feed {@link com.darchan.physical.comp.BusSelector8By8Bit}'s data input directly, so select
 * bit i reads slot i.
 *
 * <p>Each register is a storage element named {@code <name>.<register>} (e.g. {@code regfile0.A}), see
 * {@link com.darchan.physical.sim.StateElement}.
 */
public class RegisterFile implements IComponent {

    /**
     * register slots, in output-bus and write-enable order
     */
    public enum Reg {
        L, H, E, D, C, B, F, A;

        /**
         * @return slot index: output bits {@code [8*slot+7 : 8*slot]}, write enable on input bit {@code 8+slot}
         */
        public int slot() {
            return ordinal();
        }

        /**
         * @return this register's bits on the output bus
         */
        public IRange outputRange() {
            return new SimpleRange(8 * slot(), 8 * slot() + 7);
        }
    }

    /**
     * data-in bits on the input bus
     */
    public static final IRange DATA_IN = new SimpleRange(0, 7);

    /**
     * write-enable bits on the input bus
     */
    public static final IRange WRITE_ENABLES = new SimpleRange(8, 15);

    private static final IRange ALLOWED_INPUT_BUS_WIDTH = new SimpleRange(16, 16);

    private static final IRange ALLOWED_OUTPUT_BUS_WIDTH = new SimpleRange(64, 64);

    private final ISignal clk;

    private final IBus inputBus;

    private final IBus outputBus;

    private final Register[] registers = new Register[8];

    /**
     * @param sim simulator
     * @param clk clock shared by all registers
     * @param inputBus data in and write enables, see the class documentation
     */
    public RegisterFile(Simulator sim, ISignal clk, IBus inputBus) {
        this(sim, clk, inputBus, null);
    }

    /**
     * @param sim simulator
     * @param clk clock shared by all registers
     * @param inputBus data in and write enables, see the class documentation
     * @param name prefix of the registers' storage-element names ({@code <name>.A} ...); {@code null} for an
     *             automatic {@code regfile<n>}
     */
    public RegisterFile(Simulator sim, ISignal clk, IBus inputBus, String name) {
        Validation.validateInputWidth(ALLOWED_INPUT_BUS_WIDTH, inputBus);
        String prefix = name != null ? name : sim.uniqueName("regfile");
        this.clk = clk;
        this.inputBus = inputBus;

        IBus dataIn = inputBus.slice(DATA_IN);
        ISignal[] outputSignals = new ISignal[64];
        for (Reg reg : Reg.values()) {
            int slot = reg.slot();
            ISignal writeEnable = inputBus.getSignal(WRITE_ENABLES.getMin() + slot);
            this.registers[slot] = new Register(sim, clk, dataIn, writeEnable, false, prefix + "." + reg.name());
            for (int bit = 0; bit < 8; bit++) {
                outputSignals[8 * slot + bit] = this.registers[slot].getOutputBus().getSignal(bit);
            }
        }
        this.outputBus = new Bus(outputSignals);
    }

    /**
     * @return the 8-bit output of one register (same signals as its slice of {@link #getOutputBus()})
     */
    public IBus getRegisterOutput(Reg reg) {
        return this.registers[reg.slot()].getOutputBus();
    }

    public ISignal getClock() {
        return this.clk;
    }

    /**
     * @return data in [7:0] and write enables [15:8]
     */
    @Override
    public IBus getInputBus() {
        return this.inputBus;
    }

    @Override
    public IRange getAllowedInputBusWidth() {
        return ALLOWED_INPUT_BUS_WIDTH;
    }

    /**
     * @return all registers, slot i at [8i+7:8i]
     */
    @Override
    public IBus getOutputBus() {
        return this.outputBus;
    }

    @Override
    public IRange getAllowedOutputBusWidth() {
        return ALLOWED_OUTPUT_BUS_WIDTH;
    }
}
