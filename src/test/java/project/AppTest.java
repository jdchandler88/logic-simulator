package project;

import com.darchan.behavioral.cpm.CpmMachine;
import com.darchan.diag.compare.GoldenTrace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The command line: exit codes and messages of {@link App#run}. */
class AppTest {

    @TempDir
    Path dir;

    private String out;
    private String err;

    private int run(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = App.run(args, new PrintStream(o, true, StandardCharsets.UTF_8),
                new PrintStream(e, true, StandardCharsets.UTF_8));
        out = o.toString(StandardCharsets.UTF_8);
        err = e.toString(StandardCharsets.UTF_8);
        return code;
    }

    private Path com(String name, int... bytes) throws IOException {
        byte[] b = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            b[i] = (byte) bytes[i];
        }
        Path p = dir.resolve(name);
        Files.write(p, b);
        return p;
    }

    /** 0100: MVI C,02; MVI E,'!'; CALL 0005; MVI A,value; JMP 0000 -- prints "!" and exits */
    private Path exiting(String name, int value) throws IOException {
        return com(name, 0x0E, 0x02, 0x1E, '!', 0xCD, 0x05, 0x00, 0x3E, value, 0xC3, 0x00, 0x00);
    }

    /** 0100: INR A; JMP 0100 -- never exits */
    private Path looping() throws IOException {
        return com("loop.com", 0x3C, 0xC3, 0x00, 0x01);
    }

    private Path golden(Path program, String name, long maxCycles, boolean pastExit) {
        CpmMachine m = CpmMachine.load(program);
        if (pastExit) {
            m.debugger().removeBreakpoint(0x0000); // keep recording past the exit (it halts at 0000)
        }
        Path file = dir.resolve(name);
        GoldenTrace.record(m.debugger(), file, maxCycles);
        return file;
    }

    @Test
    void runComSucceedsWhenTheProgramExits() throws IOException {
        assertEquals(App.OK, run("run-com", exiting("ok.com", 1).toString()));
        assertTrue(out.startsWith("!"), out);
        assertTrue(out.contains("BREAKPOINT"), out);
        assertEquals("", err);
    }

    @Test
    void runComFailsWhenTheProgramDoesNotExit() throws IOException {
        assertEquals(App.FAILED, run("run-com", looping().toString(), "--max-cycles", "1_000"));
        assertTrue(out.contains("CYCLE_LIMIT"), out);
        assertTrue(err.contains("registers"), err);
    }

    @Test
    void runComWritesATrace() throws IOException {
        Path trace = dir.resolve("t.trace");
        assertEquals(App.OK, run("run-com", exiting("ok.com", 1).toString(), "--trace", trace.toString()));
        assertEquals(1 + 7, Files.readAllLines(trace).size(), "header + 5 program + 2 BDOS stub instructions");
    }

    @Test
    void goldenMatchExitsZero() throws IOException {
        Path program = exiting("ok.com", 1);
        Path g = golden(program, "ok.trace", 1000, false);
        assertEquals(App.OK, run("run-com", program.toString(), "--golden", g.toString()), out + err);
        assertTrue(out.contains("all 7 line(s) matched"), out);
    }

    @Test
    void goldenMismatchExitsOne() throws IOException {
        Path g = golden(exiting("a.com", 1), "a.trace", 1000, false);
        assertEquals(App.FAILED, run("run-com", exiting("b.com", 2).toString(), "--golden", g.toString()));
        assertTrue(out.contains("GOLDEN TRACE MISMATCH"), out);
        assertTrue(out.contains("bytes: expected 3E 01, actual 3E 02"), out);
        assertEquals(App.OK, run("run-com", exiting("b2.com", 2).toString(), "--golden", g.toString(),
                "--ignore-columns", "bytes,disassembly,A"), out);
    }

    @Test
    void goldenIncompleteExitsOneUnlessTheCycleLimitEndedTheRun() throws IOException {
        Path program = exiting("ok.com", 1);
        // the golden run went on past the exit; the live run stops there in the matching state
        Path g = golden(program, "long.trace", 1000, true);
        assertEquals(App.FAILED, run("run-com", program.toString(), "--golden", g.toString()));
        assertTrue(out.contains("before the golden trace ended"), out);
        assertTrue(err.contains("golden trace not completed"), err);

        // a deliberately shortened check: --max-cycles ends the run, every compared line matched
        Path loop = looping();
        Path lg = golden(loop, "loop.trace", 10_000, false);
        assertEquals(App.OK, run("run-com", loop.toString(), "--golden", lg.toString(), "--max-cycles", "100"));
        assertTrue(out.contains("--max-cycles 100 ended the run"), out);
    }

    @Test
    void goldenStopInTheWrongStateExitsOne() throws IOException {
        // the golden program runs one more instruction before exiting; the live one exits at once
        Path longer = com("longer.com", 0x3E, 0x01, 0x3C, 0xC3, 0x00, 0x00);  // MVI A,1; INR A; JMP 0
        Path shorter = com("shorter.com", 0x3E, 0x01, 0xC3, 0x00, 0x00, 0x00); // MVI A,1; JMP 0
        Path g = golden(longer, "longer.trace", 1000, false);
        assertEquals(App.FAILED, run("run-com", shorter.toString(), "--golden", g.toString()));
        assertTrue(out.contains("GOLDEN TRACE MISMATCH"), out);
    }

    @Test
    void badArgumentsExitTwo() throws IOException {
        Path ok = exiting("ok.com", 1);
        assertEquals(App.USAGE, run("run-com"));
        assertTrue(err.startsWith("usage: run-com"), err);
        assertEquals(App.USAGE, run("run-com", ok.toString(), "--bogus"));
        assertTrue(err.contains("unknown option --bogus"), err);
        assertEquals(App.USAGE, run("run-com", ok.toString(), "--trace"));
        assertTrue(err.contains("option --trace needs a value"), err);
        assertEquals(App.USAGE, run("run-com", ok.toString(), "--max-cycles", "lots"));
        assertTrue(err.contains("--max-cycles needs a non-negative number: lots"), err);
        assertEquals(App.USAGE, run("run-com", ok.toString(), "--max-cycles", "-5"));
        assertEquals(App.USAGE, run("run-com", ok.toString(), "--ignore-columns", "cycle"));
        assertEquals(App.USAGE, run("frobnicate"));
        assertTrue(err.contains("unknown command frobnicate"), err);
        assertEquals(App.USAGE, run("debug"));
        assertEquals(App.USAGE, run("debug", "--raw", ok.toString(), "--org", "xyz"));
        assertEquals(App.USAGE, run("vcd-demo", dir.resolve("w.vcd").toString(), "--timing", "sometimes"));
        assertEquals(App.USAGE, run("vcd-demo", dir.resolve("w.vcd").toString(), "--cycles"));
    }

    @Test
    void aMissingProgramExitsOne() {
        assertEquals(App.FAILED, run("run-com", dir.resolve("missing.com").toString()));
        assertTrue(err.contains("cannot read"), err);
    }

    @Test
    void vcdDemoWritesAFile() {
        Path vcd = dir.resolve("c.vcd");
        assertEquals(App.OK, run("vcd-demo", vcd.toString(), "--cycles", "4"));
        assertTrue(Files.exists(vcd));
        assertTrue(out.contains("wrote " + vcd), out);
    }

    @Test
    void noArgumentsRunsTheGateDemo() {
        assertEquals(App.OK, run());
        assertEquals(10, out.lines().count());
    }
}
