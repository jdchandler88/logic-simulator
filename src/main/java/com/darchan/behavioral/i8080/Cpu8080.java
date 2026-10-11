package com.darchan.behavioral.i8080;

import com.darchan.behavioral.bus.IoBus;
import com.darchan.behavioral.bus.MemoryBus;
import com.darchan.diag.Snapshot;
import com.darchan.diag.run.DebugTarget;
import com.darchan.diag.trace.RegisterSpec;
import com.darchan.diag.trace.TraceEvent;
import com.darchan.diag.trace.TraceLayout;
import com.darchan.diag.trace.TraceListener;

import java.util.List;
import java.util.Objects;

import static com.darchan.behavioral.i8080.Alu8080.ALWAYS_ONE;
import static com.darchan.behavioral.i8080.Alu8080.CY;
import static com.darchan.behavioral.i8080.Alu8080.P;
import static com.darchan.behavioral.i8080.Alu8080.S;
import static com.darchan.behavioral.i8080.Alu8080.Z;

/**
 * Instruction-level Intel 8080: all 256 opcodes (including the undocumented duplicates), cycle counts in clock
 * states, and interrupts.
 *
 * <p><b>Registers</b>: A, F, B, C, D, E, H, L (8-bit), SP and PC (16-bit). F is kept in the 8080 layout
 * {@code S Z 0 AC 0 P 1 CY}; every flag is computed by {@link Alu8080}, the same golden model the gate-level ALU is
 * tested against.
 *
 * <p><b>Timing</b>: {@link #step()} executes one instruction and returns its states (cycles), e.g. 4 for
 * {@code NOP}, 17/11 for a conditional call taken/not taken, 11/5 for a conditional return taken/not taken.
 * {@link #cycleCount()} and {@link #instructionCount()} accumulate.
 *
 * <p><b>Interrupts</b>: {@code EI} enables interrupts after the <em>following</em> instruction; {@code DI}
 * disables them. {@link #requestInterrupt(int)} models the INT line plus the opcode the interrupting device puts
 * on the data bus during the acknowledge (normally {@code RST n}). The request is held until the CPU accepts it,
 * which happens at the next instruction boundary with interrupts enabled: interrupts are disabled, the halt state
 * ends and the supplied instruction executes without the PC being advanced (so {@code RST} pushes the address of
 * the instruction that would have run next). Only single-byte instructions can be injected.
 *
 * <p><b>HLT</b> (7 states) stops the processor; each further {@link #step()} idles for
 * {@link #HALT_IDLE_CYCLES} cycles (not counted as an instruction) until an interrupt is accepted. With interrupts
 * disabled, only a {@link #reset()} leaves the halt state.
 *
 * <p><b>Debugging</b>: the CPU is a {@link DebugTarget}. With no trace listener the only debugging cost is one
 * null check per instruction; bus watchpoints are implemented by decorating the buses
 * ({@link Debug8080#attach}).
 */
public final class Cpu8080 implements DebugTarget {

    /** {@link Snapshot#machine()} of 8080 snapshots */
    public static final String MACHINE = "i8080";

    /** cycles consumed by each {@link #step()} while halted */
    public static final int HALT_IDLE_CYCLES = 4;

    /** Trace columns: A F B C D E H L SP (the PC has its own column). */
    public static final TraceLayout TRACE_LAYOUT = new TraceLayout(MACHINE, 16, 3, 14, List.of(
            RegisterSpec.of("A", 8), RegisterSpec.flags("F", "SZ.A.P.C"),
            RegisterSpec.of("B", 8), RegisterSpec.of("C", 8), RegisterSpec.of("D", 8), RegisterSpec.of("E", 8),
            RegisterSpec.of("H", 8), RegisterSpec.of("L", 8), RegisterSpec.of("SP", 16)));

    private static final Alu8080.Op[] ALU_OPS = Alu8080.Op.values();
    private static final int CMP = Alu8080.Op.CMP.ordinal();
    /** bits of F that POP PSW can change: S Z AC P CY; bit 1 always reads 1, bits 3 and 5 always 0 */
    private static final int F_WRITABLE = 0xD5;

    private MemoryBus memory;
    private IoBus io;

    private int a, f = ALWAYS_ONE, b, c, d, e, h, l, sp, pc;
    private long cycles;
    private long instructions;

    private boolean inte;
    private boolean eiPending;
    private boolean halted;
    private boolean interruptRequested;
    private int interruptOpcode;

    private TraceListener trace;
    private final TraceEvent traceEvent = new TraceEvent(TRACE_LAYOUT, Disassembler8080.INSTANCE);

    /** A CPU with no I/O devices ({@link IoBus#UNCONNECTED}). */
    public Cpu8080(MemoryBus memory) {
        this(memory, IoBus.UNCONNECTED);
    }

    public Cpu8080(MemoryBus memory, IoBus io) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.io = Objects.requireNonNull(io, "io");
    }

    // ---- execution -------------------------------------------------------------------------------------------

    /**
     * Executes one instruction (or accepts a pending interrupt and executes the injected instruction, or idles
     * while halted).
     *
     * @return the clock states it took
     */
    @Override
    public int step() {
        boolean inhibited = eiPending; // EI takes effect after the instruction that follows it
        eiPending = false;
        int n;
        if (interruptRequested && inte && !inhibited) {
            interruptRequested = false;
            inte = false;
            halted = false;
            if (trace != null) {
                traceInterrupt();
            }
            n = execute(interruptOpcode);
        } else if (halted) {
            cycles += HALT_IDLE_CYCLES;
            return HALT_IDLE_CYCLES;
        } else {
            if (trace != null) {
                traceFetch();
            }
            int opcode = memory.fetch(pc);
            pc = (pc + 1) & 0xFFFF;
            n = execute(opcode);
        }
        cycles += n;
        instructions++;
        return n;
    }

    /** Steps until at least {@code minCycles} more cycles have passed; returns the cycles actually executed. */
    public long run(long minCycles) {
        long target = cycles + minCycles;
        long start = cycles;
        while (cycles < target) {
            step();
        }
        return cycles - start;
    }

    /** Executes {@code opcode} (already fetched) and returns its states. */
    private int execute(int op) {
        if (op >= 0x40 && op < 0x80) { // MOV dst,src and HLT
            if (op == 0x76) {
                halted = true;
                return 7;
            }
            int dst = op >> 3 & 7;
            int src = op & 7;
            setReg(dst, reg(src));
            return dst == 6 || src == 6 ? 7 : 5;
        }
        if (op >= 0x80 && op < 0xC0) { // ADD ADC SUB SBB ANA XRA ORA CMP r
            alu(op >> 3 & 7, reg(op & 7));
            return (op & 7) == 6 ? 7 : 4;
        }
        switch (op) {
            // ---- 00..3F: data transfer, 16-bit arithmetic, INR/DCR/MVI, rotates and flag ops
            case 0x00, 0x08, 0x10, 0x18, 0x20, 0x28, 0x30, 0x38 -> { // NOP (+ undocumented NOPs)
                return 4;
            }
            case 0x01, 0x11, 0x21, 0x31 -> { // LXI rp,d16
                setPair(op >> 4, fetchWord());
                return 10;
            }
            case 0x09, 0x19, 0x29, 0x39 -> { // DAD rp
                int r = Alu8080.addWord(hl(), pair(op >> 4), f);
                setHL(r & 0xFFFF);
                f = r >>> 16;
                return 10;
            }
            case 0x02, 0x12 -> { // STAX B / STAX D
                memory.write(op == 0x02 ? bc() : de(), a);
                return 7;
            }
            case 0x0A, 0x1A -> { // LDAX B / LDAX D
                a = memory.read(op == 0x0A ? bc() : de());
                return 7;
            }
            case 0x22 -> { // SHLD a16
                int addr = fetchWord();
                memory.write(addr, l);
                memory.write((addr + 1) & 0xFFFF, h);
                return 16;
            }
            case 0x2A -> { // LHLD a16
                int addr = fetchWord();
                l = memory.read(addr);
                h = memory.read((addr + 1) & 0xFFFF);
                return 16;
            }
            case 0x32 -> { // STA a16
                memory.write(fetchWord(), a);
                return 13;
            }
            case 0x3A -> { // LDA a16
                a = memory.read(fetchWord());
                return 13;
            }
            case 0x03, 0x13, 0x23, 0x33 -> { // INX rp
                setPair(op >> 4, (pair(op >> 4) + 1) & 0xFFFF);
                return 5;
            }
            case 0x0B, 0x1B, 0x2B, 0x3B -> { // DCX rp
                setPair(op >> 4, (pair(op >> 4) - 1) & 0xFFFF);
                return 5;
            }
            case 0x04, 0x0C, 0x14, 0x1C, 0x24, 0x2C, 0x34, 0x3C -> { // INR r
                int r = op >> 3 & 7;
                int result = Alu8080.increment(reg(r), f);
                setReg(r, result & 0xFF);
                f = result >> 8;
                return r == 6 ? 10 : 5;
            }
            case 0x05, 0x0D, 0x15, 0x1D, 0x25, 0x2D, 0x35, 0x3D -> { // DCR r
                int r = op >> 3 & 7;
                int result = Alu8080.decrement(reg(r), f);
                setReg(r, result & 0xFF);
                f = result >> 8;
                return r == 6 ? 10 : 5;
            }
            case 0x06, 0x0E, 0x16, 0x1E, 0x26, 0x2E, 0x36, 0x3E -> { // MVI r,d8
                int r = op >> 3 & 7;
                setReg(r, fetchByte());
                return r == 6 ? 10 : 7;
            }
            case 0x07 -> { // RLC
                setAF(Alu8080.rotateLeft(a, f));
                return 4;
            }
            case 0x0F -> { // RRC
                setAF(Alu8080.rotateRight(a, f));
                return 4;
            }
            case 0x17 -> { // RAL
                setAF(Alu8080.rotateLeftThroughCarry(a, f));
                return 4;
            }
            case 0x1F -> { // RAR
                setAF(Alu8080.rotateRightThroughCarry(a, f));
                return 4;
            }
            case 0x27 -> { // DAA
                setAF(Alu8080.decimalAdjust(a, f));
                return 4;
            }
            case 0x2F -> { // CMA (no flags)
                a ^= 0xFF;
                return 4;
            }
            case 0x37 -> { // STC
                f |= CY;
                return 4;
            }
            case 0x3F -> { // CMC
                f ^= CY;
                return 4;
            }

            // ---- C0..FF: branches, stack, I/O, immediates, RST
            case 0xC0, 0xC8, 0xD0, 0xD8, 0xE0, 0xE8, 0xF0, 0xF8 -> { // Rcc
                if (condition(op >> 3 & 7)) {
                    pc = pop();
                    return 11;
                }
                return 5;
            }
            case 0xC1, 0xD1, 0xE1 -> { // POP B/D/H
                setPair(op >> 4 & 3, pop());
                return 10;
            }
            case 0xF1 -> { // POP PSW
                int v = pop();
                a = v >> 8;
                f = v & F_WRITABLE | ALWAYS_ONE;
                return 10;
            }
            case 0xC9, 0xD9 -> { // RET (+ undocumented D9)
                pc = pop();
                return 10;
            }
            case 0xE9 -> { // PCHL
                pc = hl();
                return 5;
            }
            case 0xF9 -> { // SPHL
                sp = hl();
                return 5;
            }
            case 0xC2, 0xCA, 0xD2, 0xDA, 0xE2, 0xEA, 0xF2, 0xFA -> { // Jcc a16 (10 states either way)
                int addr = fetchWord();
                if (condition(op >> 3 & 7)) {
                    pc = addr;
                }
                return 10;
            }
            case 0xC3, 0xCB -> { // JMP (+ undocumented CB)
                pc = fetchWord();
                return 10;
            }
            case 0xD3 -> { // OUT port
                io.out(fetchByte(), a);
                return 10;
            }
            case 0xDB -> { // IN port
                a = io.in(fetchByte()) & 0xFF;
                return 10;
            }
            case 0xE3 -> { // XTHL
                int lo = memory.read(sp);
                int hi = memory.read((sp + 1) & 0xFFFF);
                memory.write(sp, l);
                memory.write((sp + 1) & 0xFFFF, h);
                l = lo;
                h = hi;
                return 18;
            }
            case 0xEB -> { // XCHG
                int t = h;
                h = d;
                d = t;
                t = l;
                l = e;
                e = t;
                return 4;
            }
            case 0xF3 -> { // DI
                inte = false;
                return 4;
            }
            case 0xFB -> { // EI
                inte = true;
                eiPending = true;
                return 4;
            }
            case 0xC4, 0xCC, 0xD4, 0xDC, 0xE4, 0xEC, 0xF4, 0xFC -> { // Ccc a16
                int addr = fetchWord();
                if (condition(op >> 3 & 7)) {
                    push(pc);
                    pc = addr;
                    return 17;
                }
                return 11;
            }
            case 0xCD, 0xDD, 0xED, 0xFD -> { // CALL (+ undocumented DD ED FD)
                int addr = fetchWord();
                push(pc);
                pc = addr;
                return 17;
            }
            case 0xC5, 0xD5, 0xE5 -> { // PUSH B/D/H
                push(pair(op >> 4 & 3));
                return 11;
            }
            case 0xF5 -> { // PUSH PSW
                push(a << 8 | f);
                return 11;
            }
            case 0xC6, 0xCE, 0xD6, 0xDE, 0xE6, 0xEE, 0xF6, 0xFE -> { // ADI ACI SUI SBI ANI XRI ORI CPI
                alu(op >> 3 & 7, fetchByte());
                return 7;
            }
            case 0xC7, 0xCF, 0xD7, 0xDF, 0xE7, 0xEF, 0xF7, 0xFF -> { // RST n
                push(pc);
                pc = op & 0x38;
                return 11;
            }
            default -> throw new AssertionError("unhandled opcode " + Integer.toHexString(op));
        }
    }

    private void alu(int operation, int value) {
        int r = Alu8080.compute(ALU_OPS[operation], a, value, (f & CY) != 0);
        f = r >> 8;
        if (operation != CMP) {
            a = r & 0xFF;
        }
    }

    /** 8080 condition codes in encoding order: NZ Z NC C PO PE P M */
    private boolean condition(int cc) {
        return switch (cc) {
            case 0 -> (f & Z) == 0;
            case 1 -> (f & Z) != 0;
            case 2 -> (f & CY) == 0;
            case 3 -> (f & CY) != 0;
            case 4 -> (f & P) == 0;
            case 5 -> (f & P) != 0;
            case 6 -> (f & S) == 0;
            default -> (f & S) != 0;
        };
    }

    private void setAF(int resultAndFlags) {
        a = resultAndFlags & 0xFF;
        f = resultAndFlags >> 8;
    }

    private int fetchByte() {
        int v = memory.fetch(pc);
        pc = (pc + 1) & 0xFFFF;
        return v;
    }

    private int fetchWord() {
        int lo = fetchByte();
        return fetchByte() << 8 | lo;
    }

    /** high byte first, as the 8080 does */
    private void push(int value) {
        sp = (sp - 1) & 0xFFFF;
        memory.write(sp, value >> 8 & 0xFF);
        sp = (sp - 1) & 0xFFFF;
        memory.write(sp, value & 0xFF);
    }

    private int pop() {
        int lo = memory.read(sp);
        sp = (sp + 1) & 0xFFFF;
        int hi = memory.read(sp);
        sp = (sp + 1) & 0xFFFF;
        return hi << 8 | lo;
    }

    /** register by 3-bit code: B C D E H L M A (M = memory at HL) */
    private int reg(int code) {
        return switch (code) {
            case 0 -> b;
            case 1 -> c;
            case 2 -> d;
            case 3 -> e;
            case 4 -> h;
            case 5 -> l;
            case 6 -> memory.read(hl());
            default -> a;
        };
    }

    private void setReg(int code, int value) {
        switch (code) {
            case 0 -> b = value;
            case 1 -> c = value;
            case 2 -> d = value;
            case 3 -> e = value;
            case 4 -> h = value;
            case 5 -> l = value;
            case 6 -> memory.write(hl(), value);
            default -> a = value;
        }
    }

    /** register pair by 2-bit code: BC DE HL SP */
    private int pair(int code) {
        return switch (code & 3) {
            case 0 -> bc();
            case 1 -> de();
            case 2 -> hl();
            default -> sp;
        };
    }

    private void setPair(int code, int value) {
        switch (code & 3) {
            case 0 -> setBC(value);
            case 1 -> setDE(value);
            case 2 -> setHL(value);
            default -> sp = value;
        }
    }

    // ---- interrupts ------------------------------------------------------------------------------------------

    /**
     * Raises the interrupt line with {@code opcode} as the instruction to inject on acknowledge (normally
     * {@code RST n} = {@code 0xC7 | n << 3}). The request stays pending until accepted or cancelled; a newer request
     * replaces the opcode of a pending one.
     *
     * @throws IllegalArgumentException if {@code opcode} is not a single-byte instruction
     */
    public void requestInterrupt(int opcode) {
        if (opcode < 0 || opcode > 0xFF || Disassembler8080.length(opcode) != 1) {
            throw new IllegalArgumentException("only single-byte instructions can be injected: " + Integer.toHexString(opcode));
        }
        interruptRequested = true;
        interruptOpcode = opcode;
    }

    /** Convenience for {@code requestInterrupt(RST n)}. */
    public void requestRst(int n) {
        if (n < 0 || n > 7) {
            throw new IllegalArgumentException("RST 0..7: " + n);
        }
        requestInterrupt(0xC7 | n << 3);
    }

    /** Drops a pending request (the device released the INT line). */
    public void cancelInterrupt() {
        interruptRequested = false;
    }

    public boolean isInterruptPending() {
        return interruptRequested;
    }

    /** the INTE flip-flop */
    public boolean interruptsEnabled() {
        return inte;
    }

    /**
     * RESET: PC = 0, interrupts disabled, halt state, pending EI and interrupt request cleared, counters zeroed.
     * Other registers keep their values (they are undefined after a real reset).
     */
    public void reset() {
        pc = 0;
        inte = false;
        eiPending = false;
        halted = false;
        interruptRequested = false;
        cycles = 0;
        instructions = 0;
    }

    // ---- tracing ---------------------------------------------------------------------------------------------

    private void traceFetch() {
        TraceEvent ev = traceEvent;
        ev.begin(cycles, instructions, pc, false);
        int opcode = memory.peek(pc);
        // always peek three bytes (no side effects) rather than loop over the instruction length
        int packed = opcode | memory.peek((pc + 1) & 0xFFFF) << 8 | memory.peek((pc + 2) & 0xFFFF) << 16;
        ev.setBytes(packed, Disassembler8080.length(opcode));
        fillRegistersAndSend(ev);
    }

    private void traceInterrupt() {
        TraceEvent ev = traceEvent;
        ev.begin(cycles, instructions, pc, true);
        ev.setBytes(interruptOpcode, 1);
        fillRegistersAndSend(ev);
    }

    private void fillRegistersAndSend(TraceEvent ev) {
        ev.setRegister(0, a);
        ev.setRegister(1, f);
        ev.setRegister(2, b);
        ev.setRegister(3, c);
        ev.setRegister(4, d);
        ev.setRegister(5, e);
        ev.setRegister(6, h);
        ev.setRegister(7, l);
        ev.setRegister(8, sp);
        trace.onInstruction(ev);
    }

    @Override
    public void setTraceListener(TraceListener listener) {
        this.trace = listener;
    }

    // ---- DebugTarget / Snapshottable -------------------------------------------------------------------------

    @Override
    public long cycleCount() {
        return cycles;
    }

    @Override
    public long instructionCount() {
        return instructions;
    }

    @Override
    public int programCounter() {
        return pc;
    }

    @Override
    public boolean isHalted() {
        return halted;
    }

    /**
     * Fields, in order: PC SP A F B C D E H L (hex), INTE HALT EI_PENDING (bool), CYCLES INSTRUCTIONS (decimal).
     * A pending interrupt request is input-pin state and is not included.
     */
    @Override
    public Snapshot snapshot() {
        return Snapshot.builder(MACHINE)
                .hex("PC", 16, pc).hex("SP", 16, sp)
                .hex("A", 8, a).hex("F", 8, f)
                .hex("B", 8, b).hex("C", 8, c).hex("D", 8, d).hex("E", 8, e).hex("H", 8, h).hex("L", 8, l)
                .bool("INTE", inte).bool("HALT", halted).bool("EI_PENDING", eiPending)
                .dec("CYCLES", cycles).dec("INSTRUCTIONS", instructions)
                .build();
    }

    @Override
    public void restore(Snapshot s) {
        if (!MACHINE.equals(s.machine())) {
            throw new IllegalArgumentException("not an " + MACHINE + " snapshot: " + s.machine());
        }
        pc = (int) s.value("PC");
        sp = (int) s.value("SP");
        a = (int) s.value("A");
        setF((int) s.value("F"));
        b = (int) s.value("B");
        c = (int) s.value("C");
        d = (int) s.value("D");
        e = (int) s.value("E");
        h = (int) s.value("H");
        l = (int) s.value("L");
        inte = s.value("INTE") != 0;
        halted = s.value("HALT") != 0;
        eiPending = s.value("EI_PENDING") != 0;
        cycles = s.value("CYCLES");
        instructions = s.value("INSTRUCTIONS");
    }

    // ---- buses -----------------------------------------------------------------------------------------------

    public MemoryBus memory() {
        return memory;
    }

    /** Replaces the memory bus, e.g. with an observing decorator while debugging. */
    public void setMemory(MemoryBus memory) {
        this.memory = Objects.requireNonNull(memory);
    }

    public IoBus io() {
        return io;
    }

    public void setIo(IoBus io) {
        this.io = Objects.requireNonNull(io);
    }

    // ---- register access -------------------------------------------------------------------------------------

    public int a() { return a; }
    public int f() { return f; }
    public int b() { return b; }
    public int c() { return c; }
    public int d() { return d; }
    public int e() { return e; }
    public int h() { return h; }
    public int l() { return l; }
    public int sp() { return sp; }
    public int pc() { return pc; }
    public int bc() { return b << 8 | c; }
    public int de() { return d << 8 | e; }
    public int hl() { return h << 8 | l; }
    /** A and F as one word, as PUSH PSW stores it */
    public int psw() { return a << 8 | f; }

    /** @return whether all bits of {@code mask} (e.g. {@link Alu8080#Z}) are set in F */
    public boolean flag(int mask) { return (f & mask) == mask; }

    public void setA(int v) { a = v & 0xFF; }
    /** sets F, forcing bit 1 to 1 and bits 3 and 5 to 0 as on the real chip */
    public void setF(int v) { f = v & F_WRITABLE | ALWAYS_ONE; }
    public void setB(int v) { b = v & 0xFF; }
    public void setC(int v) { c = v & 0xFF; }
    public void setD(int v) { d = v & 0xFF; }
    public void setE(int v) { e = v & 0xFF; }
    public void setH(int v) { h = v & 0xFF; }
    public void setL(int v) { l = v & 0xFF; }
    public void setSP(int v) { sp = v & 0xFFFF; }
    public void setPC(int v) { pc = v & 0xFFFF; }
    public void setBC(int v) { b = v >> 8 & 0xFF; c = v & 0xFF; }
    public void setDE(int v) { d = v >> 8 & 0xFF; e = v & 0xFF; }
    public void setHL(int v) { h = v >> 8 & 0xFF; l = v & 0xFF; }

    @Override
    public String toString() {
        return snapshot().toString();
    }
}
