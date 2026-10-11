package com.darchan.behavioral.cpm;

import com.darchan.diag.compare.Divergence;
import com.darchan.diag.compare.Granularity;
import com.darchan.diag.compare.Lockstep;
import com.darchan.diag.compare.LockstepResult;
import com.darchan.diag.compare.Narrowing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Supplier;
import java.util.stream.Stream;

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

    // ---- narrowing a coarse divergence -----------------------------------------------------------------------

    static Stream<Granularity> coarse() {
        return Stream.of(Granularity.everyCycles(1000), Granularity.everySteps(50));
    }

    private static CpmMachine tst8080() {
        try {
            return new CpmMachine(CpmRomTest.rom("TST8080.COM"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** fresh machines, the candidate with the same fault, for replay-based narrowing */
    private static Supplier<Lockstep.Builder> freshPair(FaultInjectingBus.Kind kind, long at, int mask,
                                                       boolean withMemory) {
        return () -> {
            CpmMachine r = tst8080();
            CpmMachine c = tst8080();
            FaultInjectingBus.install(c, kind, at, mask);
            Lockstep.Builder b = Lockstep.builder("reference", r.debugger(), "faulty", c.debugger());
            return withMemory ? b.compareMemory("ram", r.ram()::peek, c.ram()::peek, 0x0600, 0x200) : b;
        };
    }

    private static void assertNarrowedToTheLda(Divergence d, Narrowing.Method method) {
        Narrowing n = d.narrowing();
        assertEquals(Narrowing.Status.NARROWED, n.status(), d::report);
        assertEquals(method, n.method(), d::report);
        assertEquals(LDA_INSTRUCTION + 1, n.step(), d::report);
        assertEquals(LDA_INSTRUCTION + 1, d.firstStep().orElseThrow());
        assertTrue(n.windowStart() < LDA_INSTRUCTION && n.windowEnd() > LDA_INSTRUCTION, d::report);
        assertEquals(1, n.diff().fieldDifferences().size(), d::report);
        assertEquals("A", n.diff().fieldDifferences().get(0).name());
        assertTrue(n.candidate().lastInstruction().orElseThrow().contains("0508: 3A BF 06  LDA 06BFH"), d::report);
        String report = d.report();
        assertTrue(report.startsWith("LOCKSTEP DIVERGENCE after step 438 (narrowed by " + method.text()), report);
        assertTrue(report.contains("A           expected=FF  actual=FE"), report);
        assertTrue(report.contains("--- coarse comparison #"), report);
    }

    @ParameterizedTest
    @MethodSource("coarse")
    void aCoarseDivergenceIsNarrowedByRestore(Granularity granularity) {
        FaultInjectingBus.install(candidate, FaultInjectingBus.Kind.READ, LDA_INSTRUCTION, 0x01);
        LockstepResult r = lockstep().granularity(granularity)
                .restoreWith(reference.stateSaver(), candidate.stateSaver())
                .build().runUntil(reference::exited, 10_000);
        System.out.println(r.report());
        assertTrue(r.diverged());
        assertNarrowedToTheLda(r.divergence(), Narrowing.Method.RESTORE);
        assertTrue(r.divergence().step() > LDA_INSTRUCTION + 1, "the coarse comparison saw it later");
        // both machines are left at the narrowed point, with the console output of that point
        assertEquals(LDA_INSTRUCTION + 1, reference.cpu().instructionCount());
        assertEquals(LDA_INSTRUCTION + 1, candidate.cpu().instructionCount());
        assertEquals(reference.output(), candidate.output());
    }

    @ParameterizedTest
    @MethodSource("coarse")
    void aCoarseDivergenceIsNarrowedByReplay(Granularity granularity) {
        FaultInjectingBus.install(candidate, FaultInjectingBus.Kind.READ, LDA_INSTRUCTION, 0x01);
        LockstepResult r = lockstep().granularity(granularity)
                .replayWith(freshPair(FaultInjectingBus.Kind.READ, LDA_INSTRUCTION, 0x01, false))
                .build().runUntil(reference::exited, 10_000);
        assertTrue(r.diverged());
        assertNarrowedToTheLda(r.divergence(), Narrowing.Method.REPLAY);
    }

    @Test
    void aCorruptedWriteIsNarrowedWithMemoryRestored() {
        FaultInjectingBus.install(candidate, FaultInjectingBus.Kind.WRITE, MOV_M_A_INSTRUCTION, 0x80);
        Divergence d = lockstep().granularity(Granularity.everySteps(50))
                .compareMemory("ram", reference.ram()::peek, candidate.ram()::peek, 0x0600, 0x200)
                .restoreWith(reference.stateSaver(), candidate.stateSaver())
                .build().runUntil(reference::exited, 10_000).divergence();
        Narrowing n = d.narrowing();
        assertEquals(Narrowing.Status.NARROWED, n.status(), d::report);
        assertEquals(MOV_M_A_INSTRUCTION + 1, n.step(), d::report);
        assertTrue(n.diff().fieldDifferences().isEmpty(), d::report);
        assertEquals(0x06BF, n.diff().byteDifferences().get(0).address());
    }

    @Test
    void cpusAloneCannotBeRestoredBecauseMemoryIsOutsideTheirSnapshots() {
        FaultInjectingBus.install(candidate, FaultInjectingBus.Kind.READ, LDA_INSTRUCTION, 0x01);
        Divergence d = lockstep().granularity(Granularity.everySteps(50)).build()
                .runUntil(reference::exited, 10_000).divergence();
        assertEquals(Narrowing.Status.NOT_NARROWED, d.narrowing().status());
        assertTrue(d.report().contains("NOT NARROWED: the first difference is somewhere in steps 401..450"),
                d::report);
    }
}
