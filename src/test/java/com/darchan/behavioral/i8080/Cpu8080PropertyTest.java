package com.darchan.behavioral.i8080;

import com.darchan.behavioral.bus.MemoryBus;
import com.darchan.behavioral.bus.Ram;
import com.darchan.diag.Snapshot;
import com.darchan.diag.SnapshotJson;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

import java.util.List;

import static com.darchan.behavioral.i8080.Alu8080.AC;
import static com.darchan.behavioral.i8080.Alu8080.CY;
import static com.darchan.behavioral.i8080.Alu8080.P;
import static com.darchan.behavioral.i8080.Alu8080.S;
import static com.darchan.behavioral.i8080.Alu8080.Z;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Properties of the instruction-level 8080 over random machine states: stack operations for every register pair,
 * exchanges, 16-bit arithmetic and wraparound at the ends of the address space, and snapshot/JSON/restore.
 *
 * <p>The code the CPU executes comes from a separate overlay ({@link Machine#fetch}), not from {@link Ram}, so a
 * random stack pointer can never overwrite the instruction being tested.
 */
class Cpu8080PropertyTest {

    /** RAM for data plus a code overlay that only {@code fetch} sees (everything else fetches as NOP). */
    private static final class Machine implements MemoryBus {
        final Ram data = new Ram();
        final byte[] code = new byte[Ram.SIZE];
        final Cpu8080 cpu = new Cpu8080(this);

        @Override
        public int read(int address) {
            return data.read(address);
        }

        @Override
        public void write(int address, int value) {
            data.write(address, value);
        }

        @Override
        public int fetch(int address) {
            return code[address & 0xFFFF] & 0xFF;
        }

        @Override
        public int peek(int address) {
            return data.peek(address);
        }

        /** places {@code bytes} at {@code pc} (wrapping at 64 KiB), points the PC there and executes one step */
        int run(int pc, int... bytes) {
            for (int i = 0; i < bytes.length; i++) {
                code[pc + i & 0xFFFF] = (byte) bytes[i];
            }
            cpu.setPC(pc);
            return cpu.step();
        }
    }

    @Provide
    Arbitrary<Integer> words() {
        return Arbitraries.integers().between(0, 0xFFFF)
                .edgeCases(c -> c.add(1).add(2).add(0xFF).add(0x100).add(0x7FFF).add(0x8000).add(0xFFFE));
    }

    @Provide
    Arbitrary<Integer> bytes() {
        return Arbitraries.integers().between(0, 255);
    }

    /** a stack pointer, favouring the ends of the address space where the stack wraps */
    @Provide
    Arbitrary<Integer> stackPointers() {
        return Arbitraries.integers().between(0, 0xFFFF)
                .edgeCases(c -> c.add(1).add(2).add(3).add(0xFFFD).add(0xFFFE).add(0xFFFF));
    }

    // ---- registers by pair --------------------------------------------------------------------------------------

    /** pair 0..3 = BC DE HL PSW (A high, F low) */
    private static int getPair(Cpu8080 cpu, int pair) {
        return switch (pair) {
            case 0 -> cpu.bc();
            case 1 -> cpu.de();
            case 2 -> cpu.hl();
            default -> cpu.psw();
        };
    }

    private static void setPair(Cpu8080 cpu, int pair, int value) {
        switch (pair) {
            case 0 -> cpu.setBC(value);
            case 1 -> cpu.setDE(value);
            case 2 -> cpu.setHL(value);
            default -> {
                cpu.setA(value >> 8);
                cpu.setF(value);
            }
        }
    }

    // ---- stack ----------------------------------------------------------------------------------------------------

    @Property
    void pushThenPopRestoresEveryPairAndTheStackPointer(@ForAll @IntRange(min = 0, max = 3) int pair,
                                                        @ForAll("words") int value, @ForAll("words") int clobber,
                                                        @ForAll("stackPointers") int sp,
                                                        @ForAll("words") int pc) {
        Machine m = new Machine();
        Cpu8080 cpu = m.cpu;
        setPair(cpu, pair, value);
        int stored = getPair(cpu, pair); // the PSW's F is forced to the legal layout
        cpu.setSP(sp);
        assertEquals(11, m.run(pc, 0xC5 | pair << 4), "PUSH takes 11 states");
        assertEquals(sp - 2 & 0xFFFF, cpu.sp());
        assertEquals(stored >> 8, m.data.peek(sp - 1), "high byte is pushed first, at the higher address");
        assertEquals(stored & 0xFF, m.data.peek(sp - 2), "low byte at the lower address");
        assertEquals(pc + 1 & 0xFFFF, cpu.pc());

        setPair(cpu, pair, clobber);
        assertEquals(10, m.run(pc, 0xC1 | pair << 4), "POP takes 10 states");
        assertEquals(stored, getPair(cpu, pair), "register pair is back");
        assertEquals(sp, cpu.sp(), "stack pointer is back");
    }

    @Property
    void popTakesWhateverPushStoredIntoAnyPair(@ForAll @IntRange(min = 0, max = 3) int from,
                                               @ForAll @IntRange(min = 0, max = 3) int to,
                                               @ForAll("words") int value, @ForAll("stackPointers") int sp) {
        Machine m = new Machine();
        Cpu8080 cpu = m.cpu;
        setPair(cpu, from, value);
        int stored = getPair(cpu, from);
        cpu.setSP(sp);
        m.run(0x1000, 0xC5 | from << 4);
        m.run(0x1000, 0xC1 | to << 4);
        // a PSW destination forces the F layout bits (bit 1 = 1, bits 3 and 5 = 0) whatever was pushed
        int expected = to == 3 ? stored & 0xFFD5 | 0x0002 : stored;
        assertEquals(expected, getPair(cpu, to));
        assertEquals(sp, cpu.sp());
    }

    @Property
    void callThenReturnComesBackToTheNextInstruction(@ForAll("words") int pc, @ForAll("words") int target,
                                                     @ForAll("stackPointers") int sp) {
        Machine m = new Machine();
        m.cpu.setSP(sp);
        assertEquals(17, m.run(pc, 0xCD, target & 0xFF, target >> 8));
        assertEquals(target, m.cpu.pc());
        assertEquals(sp - 2 & 0xFFFF, m.cpu.sp());
        int ret = pc + 3 & 0xFFFF;
        assertEquals(ret, m.data.peek(sp - 2) | m.data.peek(sp - 1) << 8, "return address on the stack");
        assertEquals(10, m.run(target, 0xC9));
        assertEquals(ret, m.cpu.pc());
        assertEquals(sp, m.cpu.sp());
    }

    @Property
    void rstPushesTheReturnAddressAndJumpsToTheVector(@ForAll @IntRange(min = 0, max = 7) int n,
                                                      @ForAll("words") int pc, @ForAll("stackPointers") int sp) {
        Machine m = new Machine();
        m.cpu.setSP(sp);
        assertEquals(11, m.run(pc, 0xC7 | n << 3));
        assertEquals(n * 8, m.cpu.pc());
        assertEquals(sp - 2 & 0xFFFF, m.cpu.sp());
        assertEquals(pc + 1 & 0xFFFF, m.data.peek(sp - 2) | m.data.peek(sp - 1) << 8);
    }

    // ---- exchanges ------------------------------------------------------------------------------------------------

    @Property
    void xchgSwapsDeAndHlAndTwiceIsTheIdentity(@ForAll("words") int de, @ForAll("words") int hl,
                                               @ForAll("words") int pc) {
        Machine m = new Machine();
        m.cpu.setDE(de);
        m.cpu.setHL(hl);
        int flags = m.cpu.f();
        assertEquals(4, m.run(pc, 0xEB));
        assertEquals(hl, m.cpu.de());
        assertEquals(de, m.cpu.hl());
        m.run(pc, 0xEB);
        assertEquals(de, m.cpu.de());
        assertEquals(hl, m.cpu.hl());
        assertEquals(flags, m.cpu.f(), "XCHG does not touch the flags");
    }

    @Property
    void xthlSwapsHlWithTheTopOfTheStackAndTwiceIsTheIdentity(@ForAll("words") int hl, @ForAll("words") int top,
                                                              @ForAll("stackPointers") int sp,
                                                              @ForAll("words") int pc) {
        Machine m = new Machine();
        Cpu8080 cpu = m.cpu;
        cpu.setHL(hl);
        cpu.setSP(sp);
        m.data.write(sp, top & 0xFF);
        m.data.write(sp + 1 & 0xFFFF, top >> 8);
        assertEquals(18, m.run(pc, 0xE3));
        assertEquals(top, cpu.hl(), "HL gets the old top of stack");
        assertEquals(hl & 0xFF, m.data.peek(sp), "L goes to [SP]");
        assertEquals(hl >> 8, m.data.peek(sp + 1 & 0xFFFF), "H goes to [SP+1]");
        assertEquals(sp, cpu.sp());
        m.run(pc, 0xE3);
        assertEquals(hl, cpu.hl());
        assertEquals(top & 0xFF, m.data.peek(sp));
        assertEquals(top >> 8, m.data.peek(sp + 1 & 0xFFFF));
    }

    // ---- DAD and 16-bit register arithmetic ---------------------------------------------------------------------

    @Property
    void dadAddsAPairToHlSetsOnlyCarryAndHandlesDadH(@ForAll @IntRange(min = 0, max = 3) int rp,
                                                     @ForAll("words") int hl, @ForAll("words") int other,
                                                     @ForAll("bytes") int f, @ForAll("words") int pc) {
        Machine m = new Machine();
        Cpu8080 cpu = m.cpu;
        cpu.setHL(hl);
        cpu.setF(f);
        int flagsBefore = cpu.f();
        switch (rp) {
            case 0 -> cpu.setBC(other);
            case 1 -> cpu.setDE(other);
            case 2 -> { } // DAD H adds HL to itself
            default -> cpu.setSP(other);
        }
        int addend = rp == 2 ? hl : other;
        assertEquals(10, m.run(pc, 0x09 | rp << 4));
        int sum = hl + addend;
        assertEquals(sum & 0xFFFF, cpu.hl());
        assertEquals(sum > 0xFFFF, cpu.flag(CY), "CY is the carry out of bit 15");
        assertEquals(flagsBefore & ~CY, cpu.f() & ~CY, "S Z AC P are untouched");
    }

    @Property
    void inxAndDcxWrapAt16BitsAndLeaveFlagsAlone(@ForAll @IntRange(min = 0, max = 3) int rp,
                                                 @ForAll("words") int value, @ForAll("bytes") int f,
                                                 @ForAll("words") int pc) {
        Machine m = new Machine();
        Cpu8080 cpu = m.cpu;
        cpu.setF(f);
        int flagsBefore = cpu.f();
        loadPair(cpu, rp, value);
        assertEquals(5, m.run(pc, 0x03 | rp << 4), "INX");
        assertEquals(value + 1 & 0xFFFF, readPair(cpu, rp));
        assertEquals(5, m.run(pc, 0x0B | rp << 4), "DCX");
        assertEquals(value, readPair(cpu, rp), "DCX undoes INX");
        m.run(pc, 0x0B | rp << 4);
        assertEquals(value - 1 & 0xFFFF, readPair(cpu, rp));
        assertEquals(flagsBefore, cpu.f(), "INX and DCX do not touch the flags");
    }

    /** register pair by LXI/INX/DCX code: BC DE HL SP */
    private static void loadPair(Cpu8080 cpu, int rp, int value) {
        switch (rp) {
            case 0 -> cpu.setBC(value);
            case 1 -> cpu.setDE(value);
            case 2 -> cpu.setHL(value);
            default -> cpu.setSP(value);
        }
    }

    private static int readPair(Cpu8080 cpu, int rp) {
        return switch (rp) {
            case 0 -> cpu.bc();
            case 1 -> cpu.de();
            case 2 -> cpu.hl();
            default -> cpu.sp();
        };
    }

    @Property
    void lxiLoadsTheImmediateWordIntoThePairEvenAcrossTheTopOfMemory(@ForAll @IntRange(min = 0, max = 3) int rp,
                                                                      @ForAll("words") int value,
                                                                      @ForAll @IntRange(min = 0xFFF0, max = 0xFFFF) int pc) {
        Machine m = new Machine();
        assertEquals(10, m.run(pc, 0x01 | rp << 4, value & 0xFF, value >> 8));
        assertEquals(value, readPair(m.cpu, rp));
        assertEquals(pc + 3 & 0xFFFF, m.cpu.pc(), "PC wraps from FFFF to 0000");
    }

    @Property
    void theProgramCounterWrapsAtTheTopOfMemory(@ForAll @IntRange(min = 0xFFFC, max = 0xFFFF) int pc,
                                                @ForAll @IntRange(min = 0, max = 2) int kind,
                                                @ForAll("bytes") int imm) {
        Machine m = new Machine();
        int length;
        switch (kind) {
            case 0 -> { // NOP
                length = 1;
                m.run(pc, 0x00);
            }
            case 1 -> { // MVI B,n
                length = 2;
                m.run(pc, 0x06, imm);
                assertEquals(imm, m.cpu.b());
            }
            default -> { // JNZ taken is not wrapped, JZ not taken falls through three bytes
                length = 3;
                m.cpu.setF(0); // Z clear
                m.run(pc, 0xCA, 0x34, 0x12); // JZ 1234: not taken
            }
        }
        assertEquals(pc + length & 0xFFFF, m.cpu.pc());
    }

    // ---- ALU through the CPU --------------------------------------------------------------------------------------

    /** every ALU instruction does what Alu8080 says; CMP writes only the flags */
    @Property
    void aluInstructionsAgreeWithTheGoldenModelAndCmpKeepsA(@ForAll @IntRange(min = 0, max = 7) int op,
                                                            @ForAll("bytes") int a, @ForAll("bytes") int operand,
                                                            @ForAll("bytes") int f, @ForAll boolean immediate,
                                                            @ForAll @IntRange(min = 0, max = 7) int source) {
        Machine m = new Machine();
        Cpu8080 cpu = m.cpu;
        cpu.setA(a);
        cpu.setF(f);
        boolean carry = cpu.flag(CY);
        int cycles;
        if (immediate) {
            cycles = m.run(0x2000, 0xC6 | op << 3, operand);
        } else {
            // load the operand into the chosen source (A itself, B..L, or memory at HL), then ADD r ... CMP r
            cpu.setHL(0x4000);
            switch (source) {
                case 0 -> cpu.setB(operand);
                case 1 -> cpu.setC(operand);
                case 2 -> cpu.setD(operand);
                case 3 -> cpu.setE(operand);
                case 4 -> {
                    cpu.setH(operand);
                    cpu.setL(operand);
                }
                case 5 -> {
                    cpu.setH(0x40);
                    cpu.setL(operand);
                }
                case 6 -> m.data.write(0x4000, operand);
                default -> operand = a; // ADD A, ... CMP A: the operand is A itself
            }
            cycles = m.run(0x2000, 0x80 | op << 3 | source);
        }
        int expected = Alu8080.compute(Alu8080.Op.values()[op], a, operand, carry);
        assertEquals(expected >> 8, cpu.f(), "flags");
        assertEquals(op == Alu8080.Op.CMP.ordinal() ? a : expected & 0xFF, cpu.a(),
                op == 7 ? "CMP leaves A alone" : "result in A");
        assertEquals(immediate || source == 6 ? 7 : 4, cycles);
    }

    @Property
    void inrAndDcrChangeTheRegisterByOneAndKeepCarry(@ForAll @IntRange(min = 0, max = 7) int reg,
                                                      @ForAll("bytes") int value, @ForAll("bytes") int f) {
        Machine m = new Machine();
        Cpu8080 cpu = m.cpu;
        cpu.setHL(0x5000);
        cpu.setF(f);
        boolean carry = cpu.flag(CY);
        write(m, reg, value);
        m.run(0x100, 0x04 | reg << 3);
        assertEquals(value + 1 & 0xFF, read(m, reg), "INR");
        assertEquals(carry, cpu.flag(CY));
        m.run(0x100, 0x05 | reg << 3);
        assertEquals(value, read(m, reg), "DCR undoes INR");
        assertEquals(carry, cpu.flag(CY));
        // flags are those of the DCR result, which is the original value again
        assertEquals(0, cpu.f() & 0x28, "bits 3 and 5 stay clear");
        assertEquals(Integer.bitCount(value) % 2 == 0, cpu.flag(P));
        assertEquals(value == 0, cpu.flag(Z));
        assertEquals(value >= 0x80, cpu.flag(S));
        assertEquals((value & 0xF) != 0xF, cpu.flag(AC), "DCR: AC unless the low nibble borrowed");
    }

    private static void write(Machine m, int reg, int v) {
        Cpu8080 c = m.cpu;
        switch (reg) {
            case 0 -> c.setB(v);
            case 1 -> c.setC(v);
            case 2 -> c.setD(v);
            case 3 -> c.setE(v);
            case 4 -> c.setH(v);
            case 5 -> c.setL(v);
            case 6 -> m.data.write(c.hl(), v);
            default -> c.setA(v);
        }
    }

    private static int read(Machine m, int reg) {
        Cpu8080 c = m.cpu;
        return switch (reg) {
            case 0 -> c.b();
            case 1 -> c.c();
            case 2 -> c.d();
            case 3 -> c.e();
            case 4 -> c.h();
            case 5 -> c.l();
            case 6 -> m.data.peek(c.hl());
            default -> c.a();
        };
    }

    // ---- snapshot / JSON / restore ------------------------------------------------------------------------------

    private static Snapshot randomState(int pc, int sp, List<Integer> regs, int flags, boolean inte, boolean halted,
                                        boolean eiPending, long cycles, long instructions) {
        return Snapshot.builder(Cpu8080.MACHINE)
                .hex("PC", 16, pc).hex("SP", 16, sp)
                .hex("A", 8, regs.get(0)).hex("F", 8, flags & 0xD5 | 0x02)
                .hex("B", 8, regs.get(1)).hex("C", 8, regs.get(2)).hex("D", 8, regs.get(3)).hex("E", 8, regs.get(4))
                .hex("H", 8, regs.get(5)).hex("L", 8, regs.get(6))
                .bool("INTE", inte).bool("HALT", halted).bool("EI_PENDING", eiPending)
                .dec("CYCLES", cycles).dec("INSTRUCTIONS", instructions)
                .build();
    }

    @Property(tries = 300)
    void aRandomCpuStateSurvivesSnapshotJsonRestore(@ForAll @IntRange(min = 0, max = 0xFFFF) int pc,
                                                    @ForAll @IntRange(min = 0, max = 0xFFFF) int sp,
                                                    @ForAll @Size(7) List<@IntRange(min = 0, max = 255) Integer> regs,
                                                    @ForAll @IntRange(min = 0, max = 255) int flags,
                                                    @ForAll boolean inte, @ForAll boolean halted,
                                                    @ForAll boolean eiPending,
                                                    @ForAll @LongRange(min = 0, max = Long.MAX_VALUE - 100) long cycles,
                                                    @ForAll @LongRange(min = 0, max = Long.MAX_VALUE - 100) long instructions,
                                                    @ForAll("bytes") int b0, @ForAll("bytes") int b1,
                                                    @ForAll("bytes") int b2) {
        Snapshot state = randomState(pc, sp, regs, flags, inte, halted, eiPending, cycles, instructions);

        Machine original = new Machine();
        original.cpu.restore(state);
        assertEquals(state, original.cpu.snapshot(), "restore then snapshot gives the state back");

        Snapshot reloaded = SnapshotJson.fromJson(SnapshotJson.toJson(original.cpu.snapshot()));
        assertEquals(state, reloaded, "JSON round trip");

        Machine copy = new Machine();
        copy.cpu.setA(0x55);
        copy.cpu.setHL(0x1234);
        copy.cpu.setPC(0xBEEF);
        copy.cpu.restore(reloaded);
        assertEquals(state, copy.cpu.snapshot(), "restore from the loaded file");

        // and the two CPUs behave the same from here on, with the same bytes under the PC
        for (Machine machine : new Machine[]{original, copy}) {
            machine.code[pc] = (byte) b0;
            machine.code[pc + 1 & 0xFFFF] = (byte) b1;
            machine.code[pc + 2 & 0xFFFF] = (byte) b2;
        }
        int n1 = original.cpu.step();
        int n2 = copy.cpu.step();
        assertEquals(n1, n2);
        assertEquals(original.cpu.snapshot(), copy.cpu.snapshot());
        assertArrayEquals(original.data.copy(0, Ram.SIZE), copy.data.copy(0, Ram.SIZE), "same memory writes");
    }

    @Property
    void restoreRejectsOutOfRangeValuesAndLeavesTheCpuUntouched(@ForAll @IntRange(min = 0x100, max = 0xFFFF) int tooBig) {
        Cpu8080 cpu = new Cpu8080(new Ram());
        cpu.setBC(0x1234);
        Snapshot before = cpu.snapshot();
        Snapshot bad = Snapshot.builder(Cpu8080.MACHINE)
                .hex("PC", 16, 0).hex("SP", 16, 0).hex("A", 16, tooBig).hex("F", 8, 2)
                .hex("B", 8, 0).hex("C", 8, 0).hex("D", 8, 0).hex("E", 8, 0).hex("H", 8, 0).hex("L", 8, 0)
                .bool("INTE", false).bool("HALT", false).bool("EI_PENDING", false)
                .dec("CYCLES", 0).dec("INSTRUCTIONS", 0).build();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> cpu.restore(bad));
        assertEquals(before, cpu.snapshot());
    }
}
