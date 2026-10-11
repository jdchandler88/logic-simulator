package com.darchan.behavioral.cpm;

import com.darchan.diag.compare.Divergence;
import com.darchan.diag.compare.Granularity;
import com.darchan.diag.compare.Lockstep;
import com.darchan.diag.compare.LockstepResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live lockstep of two 8080s running TST8080: a clean pair agrees for the whole program; a deliberately faulty
 * memory bus on the candidate is caught at the exact instruction.
 */
class CpmLockstepTest {

    /**
     * Instruction 437 (0-based) of TST8080 is {@code 0508: 3A BF 06  LDA 06BFH}; 358 is
     * {@code 0474: 77  MOV M,A} (HL = 06BF).
     */
    private static final long LDA_INSTRUCTION = 437;
    private static final long MOV_M_A_INSTRUCTION = 358;

    private final CpmMachine reference;
    private final CpmMachine candidate;

    CpmLockstepTest() throws IOException {
        reference = new CpmMachine(CpmRomTest.rom("TST8080.COM"));
        candidate = new CpmMachine(CpmRomTest.rom("TST8080.COM"));
    }

    private Lockstep.Builder lockstep() {
        return Lockstep.builder("reference", reference.debugger(), "faulty", candidate.debugger());
    }

    @Test
    void identicalCoresAgreeThroughTheWholeProgram() {
        LockstepResult r = lockstep().build().runUntil(reference::exited, 10_000);
        assertFalse(r.diverged(), r::report);
        assertEquals(650, r.steps());
        assertTrue(candidate.exited());
        assertTrue(candidate.output().contains("CPU IS OPERATIONAL"));
    }

    @Test
    void corruptedReadIsCaughtAtTheExactInstruction() {
        FaultInjectingBus.install(candidate, FaultInjectingBus.Kind.READ, LDA_INSTRUCTION, 0x01);
        LockstepResult r = lockstep().build().runUntil(reference::exited, 10_000);
        System.out.println(r.report());
        assertTrue(r.diverged());
        Divergence d = r.divergence();
        assertEquals(LDA_INSTRUCTION + 1, d.step());
        assertEquals(LDA_INSTRUCTION, d.lastMatchingStep());
        assertEquals(1, d.diff().fieldDifferences().size(), d::report);
        assertEquals("A", d.diff().fieldDifferences().get(0).name());
        assertEquals(LDA_INSTRUCTION + 1, candidate.cpu().instructionCount(), "stops right after the faulty instruction");
        assertTrue(d.candidate().lastInstruction().orElseThrow().contains("0508: 3A BF 06  LDA 06BFH"), d::report);
        assertTrue(d.report().contains("expected=FF  actual=FE"), d::report);
    }

    @Test
    void corruptedWriteIsCaughtThroughMemoryComparison() {
        FaultInjectingBus.install(candidate, FaultInjectingBus.Kind.WRITE, MOV_M_A_INSTRUCTION, 0x80);
        LockstepResult r = lockstep()
                .compareMemory("ram", reference.ram()::peek, candidate.ram()::peek, 0x0600, 0x200)
                .build().runUntil(reference::exited, 10_000);
        assertTrue(r.diverged());
        Divergence d = r.divergence();
        assertEquals(MOV_M_A_INSTRUCTION + 1, d.step(), d::report);
        assertTrue(d.diff().fieldDifferences().isEmpty(), d::report);
        assertEquals(1, d.diff().byteDifferenceCount());
        assertEquals(0x06BF, d.diff().byteDifferences().get(0).address());
        assertTrue(d.candidate().lastInstruction().orElseThrow().contains("0474: 77        MOV M,A"), d::report);
    }

    @Test
    void cycleGranularityFindsTheFaultyWindow() {
        FaultInjectingBus.install(candidate, FaultInjectingBus.Kind.READ, LDA_INSTRUCTION, 0x01);
        Divergence d = lockstep().granularity(Granularity.everyCycles(500)).build()
                .runUntil(reference::exited, 10_000).divergence();
        assertNotNull(d);
        // LDA 06BFH starts at cycle 3126: the first boundary after it is 3500
        assertTrue(d.reference().cycles() >= 3500 && d.reference().cycles() < 3520, d::report);
        assertTrue(d.lastMatchingStep() <= LDA_INSTRUCTION && d.step() > LDA_INSTRUCTION, d::report);
    }
}
