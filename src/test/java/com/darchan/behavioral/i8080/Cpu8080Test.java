package com.darchan.behavioral.i8080;

import com.darchan.behavioral.bus.IoBus;
import com.darchan.behavioral.bus.Ram;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static com.darchan.behavioral.i8080.Alu8080.*;
import static org.junit.jupiter.api.Assertions.*;

class Cpu8080Test {

    private final Ram ram = new Ram();
    private final List<String> ioLog = new ArrayList<>();
    private final Cpu8080 cpu = new Cpu8080(ram, new IoBus() {
        @Override
        public int in(int port) {
            ioLog.add("IN " + port);
            return port ^ 0x5A;
        }

        @Override
        public void out(int port, int value) {
            ioLog.add("OUT " + port + "=" + value);
        }
    });

    /** loads {@code bytes} at 0000 */
    private void program(int... bytes) {
        load(0, bytes);
    }

    private void load(int address, int... bytes) {
        for (int i = 0; i < bytes.length; i++) {
            ram.write(address + i, bytes[i]);
        }
    }

    private int run(int instructions) {
        int cycles = 0;
        for (int i = 0; i < instructions; i++) {
            cycles += cpu.step();
        }
        return cycles;
    }

    @Test
    void initialState() {
        assertEquals(0, cpu.pc());
        assertEquals(0x02, cpu.f(), "bit 1 of F always reads 1");
        assertFalse(cpu.interruptsEnabled());
        assertFalse(cpu.isHalted());
        assertEquals(0, cpu.cycleCount());
    }

    /** Clock states per opcode from the Intel 8080 data sheet; conditional CALL/RET are "not taken/taken". */
    private static final String[] STATES = {
            //0    1    2    3    4      5    6    7    8    9    A    B    C      D    E    F
            "4    10   7    5    5      5    7    4    4    10   7    5    5      5    7    4",  // 0
            "4    10   7    5    5      5    7    4    4    10   7    5    5      5    7    4",  // 1
            "4    10   16   5    5      5    7    4    4    10   16   5    5      5    7    4",  // 2
            "4    10   13   5    10     10   10   4    4    10   13   5    5      5    7    4",  // 3
            "5    5    5    5    5      5    7    5    5    5    5    5    5      5    7    5",  // 4
            "5    5    5    5    5      5    7    5    5    5    5    5    5      5    7    5",  // 5
            "5    5    5    5    5      5    7    5    5    5    5    5    5      5    7    5",  // 6
            "7    7    7    7    7      7    7    7    5    5    5    5    5      5    7    5",  // 7
            "4    4    4    4    4      4    7    4    4    4    4    4    4      4    7    4",  // 8
            "4    4    4    4    4      4    7    4    4    4    4    4    4      4    7    4",  // 9
            "4    4    4    4    4      4    7    4    4    4    4    4    4      4    7    4",  // A
            "4    4    4    4    4      4    7    4    4    4    4    4    4      4    7    4",  // B
            "5/11 10   10   10   11/17  11   7    11   5/11 10   10   10   11/17  17   7    11", // C
            "5/11 10   10   10   11/17  11   7    11   5/11 10   10   10   11/17  17   7    11", // D
            "5/11 10   10   18   11/17  11   7    11   5/11 5    10   4    11/17  17   7    11", // E
            "5/11 10   10   4    11/17  11   7    11   5/11 5    10   4    11/17  17   7    11", // F
    };

    @Test
    void cycleCountsForAllOpcodesTakenAndNotTaken() {
        for (int op = 0; op < 256; op++) {
            String cell = STATES[op >> 4].trim().split("\\s+")[op & 0xF];
            int notTaken = Integer.parseInt(cell.split("/")[0]);
            int taken = Integer.parseInt(cell.contains("/") ? cell.split("/")[1] : cell);
            boolean conditional = (op & 0xC7) == 0xC0 || (op & 0xC7) == 0xC4;
            // F = 02: all flags clear, so NZ NC PO P are true; F = D7: all set, so Z C PE M are true
            for (int f : new int[]{0x02, 0xD7}) {
                Ram mem = new Ram();
                mem.write(0x100, op);
                mem.write(0x101, 0x34);
                mem.write(0x102, 0x12);
                Cpu8080 c = new Cpu8080(mem);
                c.setPC(0x100);
                c.setSP(0x8000);
                c.setF(f);
                int states = c.step();
                int expected = taken;
                if (conditional) {
                    boolean flagsClear = f == 0x02;
                    boolean conditionWantsSet = ((op >> 3) & 1) == 1; // Z, C, PE, M
                    expected = conditionWantsSet != flagsClear ? taken : notTaken;
                }
                assertEquals(expected, states, String.format("opcode %02X (%s) with F=%02X", op, Disassembler8080.template(op), f));
                assertEquals(states, c.cycleCount());
                assertEquals(1, c.instructionCount());
            }
        }
    }

    @Nested
    class Arithmetic {

        /** every ALU register instruction and every operand pair agrees with the golden model */
        @Test
        void aluRegisterOpsMatchTheGoldenModelExhaustively() {
            for (int op = 0; op < 8; op++) {
                program(0x80 | op << 3); // op B
                for (int a = 0; a < 256; a++) {
                    for (int b = 0; b < 256; b++) {
                        for (int cy = 0; cy < 2; cy++) {
                            cpu.setPC(0);
                            cpu.setA(a);
                            cpu.setB(b);
                            cpu.setF(cy);
                            cpu.step();
                            int expected = Alu8080.compute(Alu8080.Op.values()[op], a, b, cy == 1);
                            int expectedA = op == 7 ? a : expected & 0xFF;
                            if (cpu.a() != expectedA || cpu.f() != expected >> 8) {
                                fail(String.format("%s A=%02X B=%02X CY=%d: got A=%02X F=%02X, expected A=%02X F=%02X",
                                        Alu8080.Op.values()[op], a, b, cy, cpu.a(), cpu.f(), expectedA, expected >> 8));
                            }
                        }
                    }
                }
            }
        }

        @Test
        void immediateAndMemoryOperands() {
            program(0x21, 0x00, 0x20, 0x86, 0xC6, 0x01, 0xD6, 0x80, 0xFE, 0x84); // LXI H,2000; ADD M; ADI 1; SUI 80; CPI 84
            ram.write(0x2000, 0x7F);
            cpu.setA(0x04);
            run(2);
            assertEquals(0x83, cpu.a());
            assertTrue(cpu.flag(S));
            run(1);
            assertEquals(0x84, cpu.a());
            run(1);
            assertEquals(0x04, cpu.a());
            assertFalse(cpu.flag(CY));
            run(1); // CPI 84: 04 - 84 borrows, A unchanged
            assertEquals(0x04, cpu.a());
            assertTrue(cpu.flag(CY));
        }

        @Test
        void anaUsesTheBit3AuxCarryRuleAndXraOraClearIt() {
            program(0xA0, 0xA8, 0xE6, 0x08);
            cpu.setA(0xF0);
            cpu.setB(0x08);
            cpu.setF(CY | AC);
            run(1);
            assertEquals(0x00, cpu.a());
            assertEquals(Z | P | AC | ALWAYS_ONE, cpu.f(), "ANA: CY cleared, AC = (A|B) bit 3");
            run(1);
            assertEquals(0x08, cpu.a());
            assertFalse(cpu.flag(AC));
            run(1); // ANI 08
            assertTrue(cpu.flag(AC));
        }

        @ParameterizedTest(name = "INR {0} -> {1} F={2}")
        @CsvSource({
                "FF, 00, 57", // Z AC P (CY kept: was 1) -> 0x40|0x10|0x04|0x02|0x01
                "0F, 10, 13", // AC (and CY kept)
                "7F, 80, 93", // S AC
                "00, 01, 03",
        })
        void inr(String in, String out, String flags) {
            program(0x3C);
            cpu.setA(Integer.parseInt(in, 16));
            cpu.setF(CY);
            assertEquals(5, cpu.step());
            assertEquals(Integer.parseInt(out, 16), cpu.a());
            assertEquals(Integer.parseInt(flags, 16), cpu.f());
        }

        @ParameterizedTest(name = "DCR {0} -> {1} F={2}")
        @CsvSource({
                "01, 00, 56", // Z AC P, CY kept clear
                "00, FF, 86", // S P; low nibble borrows -> AC clear
                "10, 0F, 06", // P; borrow from bit 4 -> AC clear
                "81, 80, 92", // S AC
        })
        void dcr(String in, String out, String flags) {
            program(0x05);
            cpu.setB(Integer.parseInt(in, 16));
            cpu.setF(0);
            cpu.step();
            assertEquals(Integer.parseInt(out, 16), cpu.b());
            assertEquals(Integer.parseInt(flags, 16), cpu.f());
        }

        @Test
        void inrAndDcrOnMemory() {
            program(0x34, 0x35, 0x35);
            cpu.setHL(0x3000);
            ram.write(0x3000, 0x41);
            assertEquals(10, cpu.step());
            assertEquals(0x42, ram.read(0x3000));
            assertEquals(20, run(2));
            assertEquals(0x40, ram.read(0x3000));
        }

        @ParameterizedTest(name = "DAA A={0} F={1} -> {2} F={3}")
        @CsvSource({
                "9B, 02, 01, 13", // Intel manual example: A=9B, carries clear -> 01, AC=1, CY=1
                "15, 02, 15, 02",
                "0A, 02, 10, 12", // low nibble > 9 -> +06, AC
                "A0, 02, 00, 47", // high nibble > 9 -> +60, CY, Z, P
                "00, 13, 66, 07", // AC and CY set -> +66; CY stays set, AC from the addition (0)
                "99, 03, F9, 87", // CY set -> +60 (S, P, CY)
        })
        void daa(String a, String f, String result, String flags) {
            program(0x27);
            cpu.setA(Integer.parseInt(a, 16));
            cpu.setF(Integer.parseInt(f, 16));
            assertEquals(4, cpu.step());
            assertEquals(Integer.parseInt(result, 16), cpu.a(), "A");
            assertEquals(Integer.parseInt(flags, 16), cpu.f(), "F");
        }

        @Test
        void bcdAdditionWithDaa() {
            // 38 + 45 = 83 in BCD
            program(0x3E, 0x38, 0xC6, 0x45, 0x27);
            run(3);
            assertEquals(0x83, cpu.a());
            assertFalse(cpu.flag(CY));
        }

        @Test
        void rotatesOnlyTouchCarry() {
            // Intel manual examples
            program(0x07, 0x0F, 0x17, 0x1F);
            cpu.setA(0xF2);
            cpu.setF(Z | S | P | AC);
            cpu.step(); // RLC
            assertEquals(0xE5, cpu.a());
            assertEquals(Z | S | P | AC | ALWAYS_ONE | CY, cpu.f());
            cpu.setA(0xF2);
            cpu.step(); // RRC
            assertEquals(0x79, cpu.a());
            assertFalse(cpu.flag(CY));
            cpu.setA(0xB5);
            cpu.step(); // RAL with CY=0
            assertEquals(0x6A, cpu.a());
            assertTrue(cpu.flag(CY));
            cpu.step(); // RAR with CY=1
            assertEquals(0xB5, cpu.a());
            assertFalse(cpu.flag(CY));
            assertEquals(Z | S | P | AC | ALWAYS_ONE, cpu.f());
        }

        @Test
        void cmaStcCmc() {
            program(0x2F, 0x37, 0x3F, 0x3F);
            cpu.setA(0x51);
            cpu.step();
            assertEquals(0xAE, cpu.a());
            assertEquals(ALWAYS_ONE, cpu.f(), "CMA affects no flags");
            cpu.step();
            assertTrue(cpu.flag(CY));
            cpu.step();
            assertFalse(cpu.flag(CY));
            cpu.step();
            assertTrue(cpu.flag(CY));
        }

        @Test
        void dadSetsOnlyCarry() {
            program(0x09, 0x29, 0x39);
            cpu.setHL(0xFFFF);
            cpu.setBC(0x0001);
            cpu.setF(Z | S);
            assertEquals(10, cpu.step());
            assertEquals(0x0000, cpu.hl());
            assertEquals(Z | S | CY | ALWAYS_ONE, cpu.f());
            cpu.setHL(0x1234);
            cpu.step(); // DAD H
            assertEquals(0x2468, cpu.hl());
            assertFalse(cpu.flag(CY));
            cpu.setSP(0x0100);
            cpu.step(); // DAD SP
            assertEquals(0x2568, cpu.hl());
        }

        @Test
        void inxAndDcxWrapWithoutFlags() {
            program(0x03, 0x1B, 0x23, 0x3B);
            cpu.setBC(0xFFFF);
            cpu.setDE(0x0000);
            cpu.setHL(0x00FF);
            cpu.setSP(0x0000);
            run(4);
            assertEquals(0x0000, cpu.bc());
            assertEquals(0xFFFF, cpu.de());
            assertEquals(0x0100, cpu.hl());
            assertEquals(0xFFFF, cpu.sp());
            assertEquals(ALWAYS_ONE, cpu.f());
        }
    }

    @Nested
    class DataTransfer {

        @Test
        void movAndMvi() {
            program(0x06, 0x12, 0x48, 0x26, 0x40, 0x2E, 0x00, 0x71, 0x36, 0x99, 0x7E);
            run(5); // MVI B,12; MOV C,B; MVI H,40; MVI L,00; MOV M,C
            assertEquals(0x12, cpu.c());
            assertEquals(0x12, ram.read(0x4000));
            assertEquals(10, cpu.step()); // MVI M,99
            assertEquals(0x99, ram.read(0x4000));
            assertEquals(7, cpu.step()); // MOV A,M
            assertEquals(0x99, cpu.a());
        }

        @Test
        void lxiLdaStaLhldShld() {
            program(0x31, 0xCD, 0xAB, 0x3A, 0x00, 0x50, 0x32, 0x01, 0x50, 0x2A, 0x10, 0x50, 0x22, 0x20, 0x50);
            ram.write(0x5000, 0x77);
            ram.write(0x5010, 0x34);
            ram.write(0x5011, 0x12);
            run(5);
            assertEquals(0xABCD, cpu.sp());
            assertEquals(0x77, cpu.a());
            assertEquals(0x77, ram.read(0x5001));
            assertEquals(0x1234, cpu.hl());
            assertEquals(0x34, ram.read(0x5020));
            assertEquals(0x12, ram.read(0x5021));
        }

        @Test
        void ldaxStaxXchgXthlSphlPchl() {
            program(0x0A, 0x12, 0xEB, 0xE3, 0xF9, 0xE9);
            cpu.setBC(0x6000);
            cpu.setDE(0x6001);
            cpu.setHL(0x1111);
            cpu.setSP(0x7000);
            ram.write(0x6000, 0x5A);
            ram.write(0x7000, 0x22);
            ram.write(0x7001, 0x33);
            run(2);
            assertEquals(0x5A, ram.read(0x6001));
            cpu.step(); // XCHG
            assertEquals(0x6001, cpu.hl());
            assertEquals(0x1111, cpu.de());
            assertEquals(18, cpu.step()); // XTHL
            assertEquals(0x3322, cpu.hl());
            assertEquals(0x01, ram.read(0x7000));
            assertEquals(0x60, ram.read(0x7001));
            cpu.step(); // SPHL
            assertEquals(0x3322, cpu.sp());
            cpu.step(); // PCHL
            assertEquals(0x3322, cpu.pc());
        }

        @Test
        void inAndOut() {
            program(0x3E, 0x42, 0xD3, 0x10, 0xDB, 0x20);
            run(3);
            assertEquals(List.of("OUT 16=66", "IN 32"), ioLog);
            assertEquals(0x20 ^ 0x5A, cpu.a());
        }

        @Test
        void unconnectedIoReadsFF() {
            Ram mem = new Ram();
            mem.write(0, 0xDB);
            Cpu8080 c = new Cpu8080(mem);
            c.step();
            assertEquals(0xFF, c.a());
        }

        @Test
        void addressesWrapAt64K() {
            load(0xFFFF, 0x3E); // MVI A at FFFF, operand at 0000
            ram.write(0x0000, 0x99);
            cpu.setPC(0xFFFF);
            cpu.step();
            assertEquals(0x99, cpu.a());
            assertEquals(0x0001, cpu.pc());
        }
    }

    @Nested
    class Stack {

        @Test
        void pushAndPopPairs() {
            program(0xC5, 0xD5, 0xE5, 0xF5, 0xC1, 0xD1, 0xE1, 0xF1);
            cpu.setSP(0x2000);
            cpu.setBC(0x0102);
            cpu.setDE(0x0304);
            cpu.setHL(0x0506);
            cpu.setA(0x07);
            cpu.setF(S | CY);
            run(4);
            assertEquals(0x1FF8, cpu.sp());
            assertEquals(0x01, ram.read(0x1FFF), "high byte at SP-1");
            assertEquals(0x02, ram.read(0x1FFE));
            assertEquals(0x07, ram.read(0x1FF9));
            assertEquals(0x83, ram.read(0x1FF8), "F as pushed: S, bit 1, CY");
            run(4); // POP B (gets PSW), POP D (H,L), POP H (D,E), POP PSW (B,C)
            assertEquals(0x0783, cpu.bc());
            assertEquals(0x0506, cpu.de());
            assertEquals(0x0304, cpu.hl());
            assertEquals(0x01, cpu.a());
            assertEquals(0x02, cpu.f());
            assertEquals(0x2000, cpu.sp());
        }

        @Test
        void popPswForcesTheFixedFlagBits() {
            program(0xF1);
            cpu.setSP(0x3000);
            ram.write(0x3000, 0xFF);
            ram.write(0x3001, 0xAA);
            cpu.step();
            assertEquals(0xAA, cpu.a());
            assertEquals(0xD7, cpu.f(), "bits 3 and 5 read 0, bit 1 reads 1");
            ram.write(0x3000, 0x00);
            cpu.setSP(0x3000);
            cpu.setPC(0);
            cpu.step();
            assertEquals(0x02, cpu.f());
        }

        @Test
        void callAndReturn() {
            program(0xCD, 0x00, 0x10);
            load(0x1000, 0xC9);
            cpu.setSP(0x2000);
            assertEquals(17, cpu.step());
            assertEquals(0x1000, cpu.pc());
            assertEquals(0x1FFE, cpu.sp());
            assertEquals(0x0003, ram.read(0x1FFE) | ram.read(0x1FFF) << 8);
            assertEquals(10, cpu.step());
            assertEquals(0x0003, cpu.pc());
            assertEquals(0x2000, cpu.sp());
        }

        @Test
        void rstPushesTheReturnAddress() {
            load(0x0123, 0xEF); // RST 5
            cpu.setPC(0x0123);
            cpu.setSP(0x2000);
            assertEquals(11, cpu.step());
            assertEquals(0x0028, cpu.pc());
            assertEquals(0x24, ram.read(0x1FFE));
            assertEquals(0x01, ram.read(0x1FFF));
        }
    }

    @Nested
    class Branches {

        /** opcode base for condition cc: Jcc = C2, Ccc = C4, Rcc = C0, + cc << 3 */
        private void checkCondition(int cc, int flagsTrue, int flagsFalse) {
            for (boolean taken : new boolean[]{true, false}) {
                // Jcc
                Ram mem = new Ram();
                mem.write(0x100, 0xC2 | cc << 3);
                mem.write(0x101, 0x00);
                mem.write(0x102, 0x30);
                Cpu8080 c = new Cpu8080(mem);
                c.setPC(0x100);
                c.setSP(0x2000);
                c.setF(taken ? flagsTrue : flagsFalse);
                assertEquals(10, c.step());
                assertEquals(taken ? 0x3000 : 0x103, c.pc(), "J cc=" + cc + " taken=" + taken);
                // Ccc
                mem.write(0x100, 0xC4 | cc << 3);
                c.setPC(0x100);
                assertEquals(taken ? 17 : 11, c.step());
                assertEquals(taken ? 0x3000 : 0x103, c.pc(), "C cc=" + cc + " taken=" + taken);
                assertEquals(taken ? 0x1FFE : 0x2000, c.sp());
                // Rcc
                c.setSP(0x1FFE);
                mem.write(0x1FFE, 0x56);
                mem.write(0x1FFF, 0x04);
                mem.write(0x100, 0xC0 | cc << 3);
                c.setPC(0x100);
                assertEquals(taken ? 11 : 5, c.step());
                assertEquals(taken ? 0x0456 : 0x101, c.pc(), "R cc=" + cc + " taken=" + taken);
                assertEquals(taken ? 0x2000 : 0x1FFE, c.sp());
            }
        }

        @Test
        void nzAndZ() {
            checkCondition(0, 0, Z);
            checkCondition(1, Z, 0);
        }

        @Test
        void ncAndC() {
            checkCondition(2, 0, CY);
            checkCondition(3, CY, 0);
        }

        @Test
        void poAndPe() {
            checkCondition(4, 0, P);
            checkCondition(5, P, 0);
        }

        @Test
        void pAndM() {
            checkCondition(6, 0, S);
            checkCondition(7, S, 0);
        }

        @Test
        void loopWithDcrJnz() {
            program(0x06, 0x05, 0x3C, 0x05, 0xC2, 0x02, 0x00, 0x76); // MVI B,5; loop: INR A; DCR B; JNZ loop; HLT
            while (!cpu.isHalted()) {
                cpu.step();
            }
            assertEquals(5, cpu.a());
            assertEquals(7 + 5 * (5 + 5 + 10) + 7, cpu.cycleCount());
            assertEquals(1 + 5 * 3 + 1, cpu.instructionCount());
        }
    }

    @Test
    void undocumentedOpcodesBehaveLikeTheirTwins() {
        // *NOP
        for (int op : new int[]{0x08, 0x10, 0x18, 0x20, 0x28, 0x30, 0x38}) {
            program(op);
            cpu.setPC(0);
            cpu.setA(0x12);
            cpu.setF(0x02);
            assertEquals(4, cpu.step());
            assertEquals(1, cpu.pc());
            assertEquals(0x12, cpu.a());
            assertEquals(0x02, cpu.f());
        }
        // *JMP
        program(0xCB, 0x00, 0x40);
        cpu.setPC(0);
        cpu.step();
        assertEquals(0x4000, cpu.pc());
        // *CALL and *RET
        for (int op : new int[]{0xDD, 0xED, 0xFD}) {
            program(op, 0x00, 0x40);
            load(0x4000, 0xD9);
            cpu.setPC(0);
            cpu.setSP(0x2000);
            assertEquals(17, cpu.step());
            assertEquals(0x4000, cpu.pc());
            assertEquals(10, cpu.step());
            assertEquals(0x0003, cpu.pc());
            assertEquals(0x2000, cpu.sp());
        }
    }

    @Nested
    class Interrupts {

        @Test
        void ignoredWhileDisabledButHeldPending() {
            program(0x00, 0x00, 0xFB, 0x00, 0x00);
            cpu.setSP(0x2000);
            cpu.requestRst(1);
            run(2);
            assertEquals(2, cpu.pc(), "interrupts disabled after reset");
            assertTrue(cpu.isInterruptPending());
            cpu.step(); // EI
            assertTrue(cpu.interruptsEnabled());
            cpu.step(); // the instruction after EI still runs
            assertEquals(4, cpu.pc());
            assertEquals(11, cpu.step()); // now the RST 1 is accepted
            assertEquals(0x0008, cpu.pc());
            assertEquals(0x0004, ram.read(0x1FFE) | ram.read(0x1FFF) << 8, "return address = next instruction");
            assertFalse(cpu.interruptsEnabled(), "acknowledge disables interrupts");
            assertFalse(cpu.isInterruptPending());
            assertEquals(5, cpu.instructionCount());
        }

        @Test
        void eiRetIdiom() {
            // handler ends with EI; RET: the interrupt must not nest before the RET has executed
            program(0xFB, 0xC9);
            cpu.setSP(0x1FFE);
            load(0x1FFE, 0x00, 0x30);
            cpu.step(); // EI
            cpu.requestRst(7);
            cpu.step(); // RET (not interrupted)
            assertEquals(0x3000, cpu.pc());
            cpu.step(); // accepted here
            assertEquals(0x0038, cpu.pc());
            assertEquals(0x3000, ram.read(0x1FFE) | ram.read(0x1FFF) << 8);
        }

        @Test
        void diDisables() {
            program(0xFB, 0x00, 0xF3, 0x00, 0x00);
            run(3);
            assertFalse(cpu.interruptsEnabled());
            cpu.requestRst(2);
            run(2);
            assertEquals(5, cpu.pc());
        }

        @Test
        void haltIdlesUntilAnInterrupt() {
            program(0xFB, 0x76, 0x00);
            cpu.setSP(0x2000);
            cpu.step();
            assertEquals(7, cpu.step()); // HLT
            assertTrue(cpu.isHalted());
            assertEquals(2, cpu.pc());
            long instructions = cpu.instructionCount();
            assertEquals(Cpu8080.HALT_IDLE_CYCLES, cpu.step());
            assertEquals(Cpu8080.HALT_IDLE_CYCLES, cpu.step());
            assertEquals(instructions, cpu.instructionCount(), "idling is not an instruction");
            assertEquals(4 + 7 + 8, cpu.cycleCount());
            cpu.requestRst(3);
            assertEquals(11, cpu.step());
            assertFalse(cpu.isHalted());
            assertEquals(0x18, cpu.pc());
            assertEquals(0x0002, ram.read(0x1FFE) | ram.read(0x1FFF) << 8, "returns after the HLT");
        }

        @Test
        void haltWithInterruptsDisabledNeedsReset() {
            program(0x76);
            cpu.step();
            cpu.requestRst(1);
            run(10);
            assertTrue(cpu.isHalted());
            cpu.reset();
            assertFalse(cpu.isHalted());
            assertEquals(0, cpu.pc());
            assertEquals(0, cpu.cycleCount());
            assertFalse(cpu.isInterruptPending());
        }

        @Test
        void onlySingleByteInstructionsCanBeInjected() {
            assertThrows(IllegalArgumentException.class, () -> cpu.requestInterrupt(0xCD));
            assertThrows(IllegalArgumentException.class, () -> cpu.requestInterrupt(0x3E));
            assertThrows(IllegalArgumentException.class, () -> cpu.requestRst(8));
            cpu.requestInterrupt(0xFF);
            cpu.cancelInterrupt();
            assertFalse(cpu.isInterruptPending());
        }
    }
}
