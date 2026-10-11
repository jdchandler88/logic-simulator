package com.darchan.cosim;

import com.darchan.diag.compare.Divergence;
import com.darchan.diag.compare.Granularity;
import com.darchan.diag.compare.LockstepResult;
import com.darchan.diag.compare.Narrowing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The gate-level ALU datapath against the behavioral 8080, one instruction per comparison. */
class AluDatapathCosimTest {

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            b[i] = (byte) values[i];
        }
        return b;
    }

    /** a random program of {@code n} supported instructions */
    static byte[] randomProgram(long seed, int n) {
        Random rnd = new Random(seed);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < n; i++) {
            int op;
            do {
                op = rnd.nextInt(256);
            } while (!AluDatapathCosim.supports(op));
            out.write(op);
            if ((op & 0xC7) == 0x06 || (op & 0xC7) == 0xC6) {
                out.write(rnd.nextInt(256));
            }
        }
        return out.toByteArray();
    }

    @Test
    void bothLayersAgreeOnARandomProgram() {
        AluDatapathCosim cosim = new AluDatapathCosim(randomProgram(8080, 1500));
        LockstepResult r = cosim.lockstep().run(cosim.instructionCount());
        assertFalse(r.diverged(), r::report);
        assertEquals(1500, r.steps());
        assertEquals(1501, r.comparisons());
        assertEquals(cosim.behavioral().a(), cosim.probes().value("A"));
        assertEquals(cosim.behavioral().f(), cosim.probes().value("F"));
    }

    @Test
    void aStuckAtFaultIsCaughtAtTheFirstInstructionThatExercisesIt() {
        byte[] program = bytes(
                0x06, 0x05,   // MVI B,05H
                0x3E, 0x03,   // MVI A,03H
                0x80,         // ADD B      operand bit 3 is 0: unaffected
                0xC6, 0x08,   // ADI 08H    operand bit 3 is 1: the stuck-at-0 fault shows here
                0x91);        // SUB C
        AluDatapathCosim cosim = new AluDatapathCosim(program, AluDatapathCosim.stuckAt(3, false));
        LockstepResult r = cosim.lockstep().run(cosim.instructionCount());
        System.out.println(r.report());
        assertTrue(r.diverged());
        Divergence d = r.divergence();
        assertEquals(4, d.step());
        assertEquals(3, d.lastMatchingStep());
        assertTrue(d.reference().lastInstruction().orElseThrow().contains("0005: C6 08     ADI 08H"), d::report);
        assertTrue(d.candidate().lastInstruction().orElseThrow().contains("0005: C6 08     ADI 08H"), d::report);
        assertEquals("A", d.diff().fieldDifferences().get(0).name());
        assertTrue(d.report().contains("A           expected=10  actual=08"), d::report);
    }

    @Test
    void gateLevelTracesReadLikeTheCpus() {
        AluDatapathCosim cosim = new AluDatapathCosim(bytes(0x3E, 0x41, 0x47, 0xA8));
        cosim.lockstep().run(3);
        var gate = cosim.gateDebugger().ringBuffer().lines();
        assertEquals(3, gate.size());
        assertEquals("000000000000 000000000000 0000: 3E 41     MVI A,41H      A=00 F=02 B=00 C=00 D=00 E=00 H=00 L=00"
                + " SZAPC=-----", gate.get(0));
        assertTrue(gate.get(2).contains("0003: A8        XRA B          A=41 F=02 B=41"), gate.get(2));
        assertEquals(0x00, cosim.probes().value("A"));
        assertEquals(0x46, cosim.probes().value("F"), "XRA A,B with A = B: Z and P set");
    }

    @Test
    void unsupportedInstructionsAreRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new AluDatapathCosim(bytes(0x3E, 0x01, 0x86))); // ADD M
        assertTrue(e.getMessage().contains("unsupported instruction 86 (ADD M) at 0002"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new AluDatapathCosim(bytes(0x3E))); // truncated
        assertFalse(AluDatapathCosim.supports(0x76)); // HLT
        assertTrue(AluDatapathCosim.supports(0xFE)); // CPI
        assertFalse(AluDatapathCosim.supports(0xC3)); // JMP
    }

    // ---- narrowing a coarse divergence -----------------------------------------------------------------------

    /** instruction index (0-based) of the only instruction whose operand B has bit 3 set */
    static final int FAULTY_INSTRUCTION = 437;

    /**
     * 600 random instructions whose operand B never has bit 3 set (immediates without bit 3, C only ever 05), except
     * for an {@code ADI 08H} at {@link #FAULTY_INSTRUCTION}: a stuck-at-0 fault on operand bit 3 shows exactly there.
     */
    static byte[] programWithOneBit3Operand() {
        int[][] fillers = {
                {0xC6, 0x01},   // ADI 01H
                {0xF6, 0x04},   // ORI 04H
                {0xE6, 0xF7},   // ANI F7H
                {0xEE, 0x02},   // XRI 02H
                {0x00},         // NOP
                {0x0E, 0x05},   // MVI C,05H
                {0x51},         // MOV D,C
                {0x81},         // ADD C
        };
        Random rnd = new Random(1979);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x0E);
        out.write(0x05);       // MVI C,05H first, so C is 05 before any use
        for (int i = 1; i < 600; i++) {
            int[] insn = i == FAULTY_INSTRUCTION ? new int[]{0xC6, 0x08} : fillers[rnd.nextInt(fillers.length)];
            for (int b : insn) {
                out.write(b);
            }
        }
        return out.toByteArray();
    }

    static Stream<Granularity> coarse() {
        return Stream.of(Granularity.everyCycles(1000), Granularity.everySteps(50));
    }

    @Test
    void theKnownStepProgramIsCleanWithoutTheFault() {
        AluDatapathCosim cosim = new AluDatapathCosim(programWithOneBit3Operand());
        LockstepResult r = cosim.lockstep().run(cosim.instructionCount());
        assertFalse(r.diverged(), r::report);
        assertEquals(cosim.behavioral().cycleCount(), cosim.gateLevel().cycleCount(),
                "the gate level charges each instruction the 8080's cycle count");
    }

    @ParameterizedTest
    @MethodSource("coarse")
    void aCoarseDivergenceIsNarrowedToTheFaultyInstruction(Granularity granularity) {
        byte[] program = programWithOneBit3Operand();
        AluDatapathCosim cosim = new AluDatapathCosim(program, AluDatapathCosim.stuckAt(3, false));
        LockstepResult r = cosim.lockstep(granularity).run(cosim.instructionCount());
        System.out.println(r.report());
        assertTrue(r.diverged());
        Divergence d = r.divergence();
        assertTrue(d.step() > FAULTY_INSTRUCTION + 1, "the coarse comparison sees it later: " + d.report());
        Narrowing n = d.narrowing();
        assertEquals(Narrowing.Status.NARROWED, n.status(), d::report);
        assertEquals(FAULTY_INSTRUCTION + 1, n.step(), d::report);
        assertEquals("A", n.diff().fieldDifferences().get(0).name(), d::report);
        assertTrue(n.candidate().lastInstruction().orElseThrow().contains("C6 08     ADI 08H"), d::report);
        assertTrue(n.reference().lastInstruction().orElseThrow().contains("C6 08     ADI 08H"), d::report);
        assertTrue(d.report().startsWith("LOCKSTEP DIVERGENCE after step " + (FAULTY_INSTRUCTION + 1) + " (narrowed by "),
                d::report);
    }
}
