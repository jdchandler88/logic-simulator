package com.darchan.behavioral.cpm;

import com.darchan.diag.run.StopReason;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the classic CP/M 8080 CPU diagnostics (see {@code src/test/resources/cpm/README.md}). Expected cycle and
 * instruction counts are those published for the superzazu/8080 reference emulator, which uses the same BDOS stub
 * bytes, minus its final {@code OUT 0} at 0000 (10 states, 1 instruction) that this harness does not execute.
 *
 * <p>Runs first ({@code @Order(1)}, see {@code junit-platform.properties}) so its timing is not affected by tests
 * that add their own {@code MemoryBus} or {@code DebugTarget} implementations, which can make the CPU's call sites
 * megamorphic in a shared test JVM. Production runs only see the production types.
 */
@Order(1)
class CpmRomTest {

    private static final long BILLION = 1_000_000_000L;

    record Run(CpmMachine machine, StopReason stop, long nanos) {
        String report() {
            return "stop: " + stop + "\n" + machine.failureReport();
        }

        double mhz() {
            return machine.cpu().cycleCount() * 1000.0 / nanos;
        }
    }

    /**
     * The named diagnostic from {@code src/test/resources/cpm/}. A missing file skips the calling test locally but
     * fails it in CI (environment variable {@code CI=true}, which GitHub Actions sets), so a lost ROM cannot turn
     * the suites that use it into silent skips there.
     */
    static byte[] rom(String name) throws IOException {
        try (InputStream in = CpmRomTest.class.getResourceAsStream("/cpm/" + name)) {
            if (in == null) {
                String message = "CP/M test ROM " + name + " is not in src/test/resources/cpm/ (see the README there)";
                if (romsRequired()) {
                    fail(message + "; CI=true, so missing ROMs fail instead of skipping");
                }
                assumeTrue(false, message + "; skipping");
            }
            return in.readAllBytes();
        }
    }

    /** true in CI, where every ROM must be present */
    static boolean romsRequired() {
        return "true".equalsIgnoreCase(System.getenv("CI"));
    }

    static Run run(String name, long maxCycles) throws IOException {
        CpmMachine m = new CpmMachine(rom(name));
        long t0 = System.nanoTime();
        StopReason stop = m.run(maxCycles);
        long nanos = System.nanoTime() - t0;
        Run r = new Run(m, stop, nanos);
        System.out.printf("%s: %,d instructions, %,d cycles in %.2f s (%.0f MHz emulated)%n", name,
                m.cpu().instructionCount(), m.cpu().cycleCount(), nanos / 1e9, r.mhz());
        return r;
    }

    static void assertExited(Run r) {
        assertTrue(r.machine().exited(), () -> "program did not exit\n" + r.report());
    }

    @Test
    void preliminaryTests() throws IOException {
        Run r = run("8080PRE.COM", 1_000_000);
        assertExited(r);
        String out = r.machine().output();
        assertTrue(out.contains("8080 Preliminary tests complete"), r::report);
        assertFalse(out.contains("ERROR"), r::report);
        assertEquals(7807, r.machine().cpu().cycleCount(), r::report);
        assertEquals(1060, r.machine().cpu().instructionCount(), r::report);
    }

    @Test
    void microcosmDiagnostic() throws IOException {
        Run r = run("TST8080.COM", 1_000_000);
        assertExited(r);
        String out = r.machine().output();
        assertTrue(out.contains("CPU IS OPERATIONAL"), r::report);
        assertFalse(out.contains("FAILED"), r::report);
        assertEquals(4914, r.machine().cpu().cycleCount(), r::report);
        assertEquals(650, r.machine().cpu().instructionCount(), r::report);
    }

    @Test
    @Tag("mutation-skip") // 255 M cycles: too slow to run once per PIT mutant, see build.gradle
    void supersoftDiagnostic() throws IOException {
        Run r = run("CPUTEST.COM", 1 * BILLION);
        assertExited(r);
        String out = r.machine().output();
        assertTrue(out.contains("CPU TESTS OK"), r::report);
        assertFalse(out.contains("FAILED"), r::report);
        assertFalse(out.contains("ERROR"), r::report);
        assertEquals(255_653_373L, r.machine().cpu().cycleCount(), r::report);
    }

    @Test
    @Tag("slow")
    void instructionExerciser() throws IOException {
        Run r = run("8080EXM.COM", 30 * BILLION);
        assertExited(r);
        String out = r.machine().output();
        assertTrue(out.contains("Tests complete"), r::report);
        assertFalse(out.contains("ERROR"), r::report);
        assertEquals(25, out.split("PASS!", -1).length - 1, r::report);
        assertEquals(23_803_381_161L, r.machine().cpu().cycleCount(), r::report);
    }

    @Test
    void aMissingRomSkipsLocallyAndFailsInCi() {
        Throwable t = assertThrows(Throwable.class, () -> rom("NO-SUCH-ROM.COM"));
        if (romsRequired()) {
            assertInstanceOf(org.opentest4j.AssertionFailedError.class, t);
        } else {
            assertInstanceOf(org.opentest4j.TestAbortedException.class, t);
        }
        assertTrue(t.getMessage().contains("NO-SUCH-ROM.COM"), t.getMessage());
    }
}
