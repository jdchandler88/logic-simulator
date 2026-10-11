package com.darchan.behavioral.cpm;

import com.darchan.diag.compare.GoldenTrace;
import com.darchan.diag.compare.GoldenTraceResult;
import com.darchan.diag.compare.TraceMismatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TST8080 against the committed golden trace {@code src/test/resources/golden/TST8080.trace} (650 instructions,
 * recorded from this core after it passed all four CP/M diagnostics with reference-exact cycle counts).
 */
class CpmGoldenTraceTest {

    private static GoldenTrace golden() {
        return GoldenTrace.of("TST8080.trace", new InputStreamReader(
                Objects.requireNonNull(CpmGoldenTraceTest.class.getResourceAsStream("/golden/TST8080.trace")),
                StandardCharsets.UTF_8));
    }

    @Test
    void theCoreReproducesTheGoldenTrace() throws IOException {
        CpmMachine m = new CpmMachine(CpmRomTest.rom("TST8080.COM"));
        GoldenTraceResult r = golden().compare(m.debugger(), 1_000_000);
        assertTrue(r.matched(), r::report);
        assertTrue(r.complete(), r::report);
        assertEquals(650, r.linesCompared());
        assertTrue(m.exited());
    }

    @Test
    void aCorruptedReadIsPinpointed() throws IOException {
        CpmMachine m = new CpmMachine(CpmRomTest.rom("TST8080.COM"));
        // instruction 437 is 0508: LDA 06BFH; flip bit 0 of the byte it reads
        FaultInjectingBus.install(m, FaultInjectingBus.Kind.READ, 437, 0x01);
        GoldenTraceResult r = golden().compare(m.debugger(), 1_000_000);
        System.out.println(r.report());
        assertFalse(r.matched());
        TraceMismatch mm = r.mismatch();
        assertEquals(440, mm.lineNumber(), "header + 438 matching lines, then the line of instruction 438");
        assertEquals(438, mm.instruction());
        assertEquals(List.of("A: expected FF, actual FE"), mm.differingColumns());
        int a = mm.actual().indexOf(" A=") + 3;
        assertEquals(" ".repeat(a + 1) + "^", mm.markers(), "marks the second digit of A");
        assertTrue(mm.context().get(mm.context().size() - 1).contains("0508: 3A BF 06  LDA 06BFH"), mm::report);
        assertEquals(439, m.cpu().instructionCount());
    }

    @Test
    void recordingReproducesTheCommittedFile(@TempDir Path dir) throws IOException {
        CpmMachine m = new CpmMachine(CpmRomTest.rom("TST8080.COM"));
        Path file = dir.resolve("tst.trace");
        GoldenTrace.record(m.debugger(), file, 1_000_000);
        try (var in = CpmGoldenTraceTest.class.getResourceAsStream("/golden/TST8080.trace")) {
            assertEquals(new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8),
                    Files.readString(file));
        }
    }
}
