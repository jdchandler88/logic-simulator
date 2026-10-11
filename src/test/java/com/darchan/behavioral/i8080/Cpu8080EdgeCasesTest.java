package com.darchan.behavioral.i8080;

import com.darchan.behavioral.bus.Ram;
import com.darchan.diag.Field;
import com.darchan.diag.Snapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Argument checks and accessors of the CPU that the instruction tests do not reach. */
class Cpu8080EdgeCasesTest {

    private final Ram ram = new Ram();
    private final Cpu8080 cpu = new Cpu8080(ram);

    @Test
    void runExecutesWholeInstructionsUntilTheBudgetIsReachedAndReturnsTheCyclesUsed() {
        assertEquals(0, cpu.run(0), "a zero budget executes nothing");
        assertEquals(0, cpu.cycleCount());
        assertEquals(8, cpu.run(8), "two NOPs of 4 states");
        assertEquals(12, cpu.run(9), "9 states needs three NOPs");
        assertEquals(20, cpu.cycleCount(), "run returns the cycles of this call, not the running total");
        assertEquals(5, cpu.instructionCount());
        assertEquals(5, cpu.pc());
    }

    @Test
    void anInterruptOpcodeMustBeASingleByteInstruction() {
        cpu.requestInterrupt(0xFF); // RST 7: the largest opcode
        assertTrue(cpu.isInterruptPending());
        cpu.cancelInterrupt();
        assertFalse(cpu.isInterruptPending());
        cpu.requestInterrupt(0x00); // NOP: the smallest
        assertTrue(cpu.isInterruptPending());
        assertThrows(IllegalArgumentException.class, () -> cpu.requestInterrupt(0x100));
        assertThrows(IllegalArgumentException.class, () -> cpu.requestInterrupt(-1));
        assertThrows(IllegalArgumentException.class, () -> cpu.requestInterrupt(0xCD), "CALL has operands");
        assertThrows(IllegalArgumentException.class, () -> cpu.requestInterrupt(0x3E), "MVI has an operand");
    }

    @Test
    void rstNumbersAreZeroToSevenAndRst7InjectsTheLastVector() {
        for (int n = 0; n <= 7; n++) {
            cpu.requestRst(n);
            assertTrue(cpu.isInterruptPending());
            cpu.cancelInterrupt();
        }
        assertThrows(IllegalArgumentException.class, () -> cpu.requestRst(8));
        assertThrows(IllegalArgumentException.class, () -> cpu.requestRst(-1));

        cpu.setPC(0x0200);
        cpu.setSP(0x1000);
        ram.write(0x0200, 0xFB); // EI
        cpu.step();
        cpu.requestRst(7);
        cpu.step(); // the instruction after EI still runs
        assertEquals(0x0202, cpu.pc());
        assertEquals(11, cpu.step(), "RST takes 11 states");
        assertEquals(0x38, cpu.pc());
        assertEquals(0x0FFE, cpu.sp());
        assertEquals(0x02, ram.peek(0x0FFE));
        assertEquals(0x02, ram.peek(0x0FFF));
        assertFalse(cpu.interruptsEnabled(), "acknowledging an interrupt disables further ones");
        assertFalse(cpu.isInterruptPending());
    }

    @Test
    void restoreChecksTheMachineName() {
        Snapshot own = cpu.snapshot();
        Snapshot.Builder foreign = Snapshot.builder("z80").fields(own.fields());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> cpu.restore(foreign.build()));
        assertTrue(e.getMessage().contains("z80"), e.getMessage());
    }

    @Test
    void restoreRejectsNegativeCountersEachOnTheirOwn() {
        for (String counter : new String[]{"CYCLES", "INSTRUCTIONS"}) {
            Snapshot own = cpu.snapshot();
            Snapshot.Builder b = Snapshot.builder(Cpu8080.MACHINE);
            for (Field f : own.fields()) {
                b.field(f.name().equals(counter) ? Field.dec(counter, -1) : f);
            }
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> cpu.restore(b.build()),
                    counter);
            assertTrue(e.getMessage().contains("negative"), e.getMessage());
        }
        // zero is fine
        cpu.restore(cpu.snapshot());
    }

    @Test
    void restoreRangeChecksAreInclusiveOfTheMaximum() {
        Snapshot full = Snapshot.builder(Cpu8080.MACHINE)
                .hex("PC", 16, 0xFFFF).hex("SP", 16, 0xFFFF).hex("A", 8, 0xFF).hex("F", 8, 0xFF)
                .hex("B", 8, 0xFF).hex("C", 8, 0xFF).hex("D", 8, 0xFF).hex("E", 8, 0xFF).hex("H", 8, 0xFF)
                .hex("L", 8, 0xFF).bool("INTE", true).bool("HALT", true).bool("EI_PENDING", true)
                .dec("CYCLES", Long.MAX_VALUE).dec("INSTRUCTIONS", Long.MAX_VALUE).build();
        cpu.restore(full);
        assertEquals(0xFFFF, cpu.pc());
        assertEquals(0xFFFF, cpu.sp());
        assertEquals(0xFF, cpu.l());
        assertEquals(0xD7, cpu.f(), "F keeps S Z AC P CY, bit 1 forced on, bits 3 and 5 forced off");
        assertTrue(cpu.interruptsEnabled());
        assertTrue(cpu.isHalted());
        assertEquals(Long.MAX_VALUE, cpu.cycleCount());
        assertEquals(Long.MAX_VALUE, cpu.instructionCount());
        // each register is limited on its own: one above the maximum in turn is rejected
        for (String name : new String[]{"PC", "SP", "A", "F", "B", "C", "D", "E", "H", "L"}) {
            Snapshot.Builder b = Snapshot.builder(Cpu8080.MACHINE);
            for (Field f : full.fields()) {
                if (f.name().equals(name)) {
                    long tooBig = f.width() == 16 ? 0x10000 : 0x100;
                    b.field(new Field(name, 32, tooBig, Field.Format.HEX));
                } else {
                    b.field(f);
                }
            }
            assertThrows(IllegalArgumentException.class, () -> cpu.restore(b.build()), name);
        }
    }

    @Test
    void registerAccessorsAndPairsAreConsistent() {
        cpu.setA(0x12);
        cpu.setB(0x34);
        cpu.setC(0x56);
        cpu.setD(0x78);
        cpu.setE(0x9A);
        cpu.setH(0xBC);
        cpu.setL(0xDE);
        cpu.setF(0xFF);
        assertEquals(0x12, cpu.a());
        assertEquals(0x34, cpu.b());
        assertEquals(0x56, cpu.c());
        assertEquals(0x78, cpu.d());
        assertEquals(0x9A, cpu.e());
        assertEquals(0xBC, cpu.h());
        assertEquals(0xDE, cpu.l());
        assertEquals(0x3456, cpu.bc());
        assertEquals(0x789A, cpu.de());
        assertEquals(0xBCDE, cpu.hl());
        assertEquals(0x12D7, cpu.psw());
        cpu.setBC(0xFEDC);
        cpu.setDE(0xBA98);
        cpu.setHL(0x7654);
        assertEquals(0xFE, cpu.b());
        assertEquals(0xDC, cpu.c());
        assertEquals(0xBA, cpu.d());
        assertEquals(0x98, cpu.e());
        assertEquals(0x76, cpu.h());
        assertEquals(0x54, cpu.l());
        // setters mask to the register width
        cpu.setA(0x1FF);
        cpu.setB(0x1FF);
        cpu.setC(0x1FF);
        cpu.setD(0x1FF);
        cpu.setE(0x1FF);
        cpu.setH(0x1FF);
        cpu.setL(0x1FF);
        cpu.setSP(0x1FFFF);
        cpu.setPC(0x1FFFF);
        assertEquals(0xFF, cpu.a());
        assertEquals(0xFF, cpu.b());
        assertEquals(0xFF, cpu.c());
        assertEquals(0xFF, cpu.d());
        assertEquals(0xFF, cpu.e());
        assertEquals(0xFF, cpu.h());
        assertEquals(0xFF, cpu.l());
        assertEquals(0xFFFF, cpu.sp());
        assertEquals(0xFFFF, cpu.pc());
        cpu.setBC(0x1FFFF);
        assertEquals(0xFFFF, cpu.bc());
    }

    @Test
    void flagTestsNeedEveryBitOfTheMask() {
        cpu.setF(Alu8080.Z);
        assertTrue(cpu.flag(Alu8080.Z));
        assertFalse(cpu.flag(Alu8080.Z | Alu8080.CY), "both bits are required");
        assertFalse(cpu.flag(Alu8080.S));
    }

    @Test
    void toStringIsTheSnapshotText() {
        cpu.setHL(0xABCD);
        assertEquals(cpu.snapshot().toString(), cpu.toString());
        assertTrue(cpu.toString().startsWith("i8080: PC=0000 SP=0000 A=00 F=02"), cpu.toString());
        assertTrue(cpu.toString().contains("H=AB L=CD"), cpu.toString());
    }

    @Test
    void theBusesCanBeReplacedButNotWithNull() {
        Ram other = new Ram();
        cpu.setMemory(other);
        assertSame(other, cpu.memory());
        assertThrows(NullPointerException.class, () -> cpu.setMemory(null));
        assertThrows(NullPointerException.class, () -> cpu.setIo(null));
        assertThrows(NullPointerException.class, () -> new Cpu8080(null));
        assertThrows(NullPointerException.class, () -> new Cpu8080(ram, null));
        assertSame(com.darchan.behavioral.bus.IoBus.UNCONNECTED, cpu.io());
    }

    @Test
    void resetClearsControlStateButKeepsRegisters() {
        cpu.setA(0x42);
        cpu.setPC(0x1234);
        ram.write(0x1234, 0xFB); // EI
        ram.write(0x1235, 0x76); // HLT
        cpu.step();
        cpu.step();
        assertTrue(cpu.isHalted());
        assertTrue(cpu.interruptsEnabled());
        cpu.requestRst(1);
        cpu.reset();
        assertEquals(0, cpu.pc());
        assertEquals(0, cpu.cycleCount());
        assertEquals(0, cpu.instructionCount());
        assertFalse(cpu.interruptsEnabled());
        assertFalse(cpu.isInterruptPending());
        assertFalse(cpu.isHalted());
        assertEquals(0x42, cpu.a(), "registers are undefined after RESET: they keep their values");
    }
}
