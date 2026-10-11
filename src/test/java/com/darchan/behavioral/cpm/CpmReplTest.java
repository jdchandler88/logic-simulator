package com.darchan.behavioral.cpm;

import com.darchan.diag.repl.DebuggerRepl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Scripted sessions of the command-line debugger on TST8080. */
class CpmReplTest {

    @TempDir
    Path dir;

    private CpmMachine machine;

    private String session(String script) throws IOException {
        machine = new CpmMachine(CpmRomTest.rom("TST8080.COM"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        machine.echoTo(out);
        new DebuggerRepl(machine.replTarget(), new BufferedReader(new StringReader(script)), out).run();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void startsWithTheCurrentInstructionAndQuitsOnEof() throws IOException {
        String out = session("");
        assertTrue(out.startsWith("i8080 debugger. Type 'help' for commands.\n-> 0100: C3 B2 01  JMP 01B2H\n"
                + "   PC=0100 SP=0000 A=00 F=02"), out);
        assertTrue(out.endsWith(DebuggerRepl.PROMPT + "\n"), out);
    }

    @Test
    void stepShowsTheNextInstructionAndRegisters() throws IOException {
        String out = session("step\ns 3\nquit\n");
        assertTrue(out.contains("[STEPPED] 1 step(s) at cycle 10, instruction 1\n-> 01B2: 31 BD 07  LXI SP,07BDH\n"
                + "   PC=01B2 SP=0000"), out);
        assertTrue(out.contains("[STEPPED] 3 step(s) at cycle 47, instruction 4\n-> 014B: D5        PUSH D"), out);
        assertEquals(4, machine.cpu().instructionCount());
    }

    @Test
    void breakpointsInEveryNotationAndContinue() throws IOException {
        String out = session("""
                break 0x14B
                b 5H
                break 0005
                breaks
                cont
                cont
                delete 2
                delete #9
                breaks
                c
                quit
                """);
        assertTrue(out.contains("#2 break 014B\n"), out);
        assertTrue(out.contains("#3 break 0005 (BDOS call)\n"), out);
        assertTrue(out.contains("breakpoint already set: #3"), out);
        assertTrue(out.contains("#1 break 0000 (program exit (CP/M warm boot))\n#2 break 014B\n#3 break 0005"), out);
        assertTrue(out.contains("[BREAKPOINT] breakpoint at 014B at cycle 47, instruction 4\n-> 014B: D5        PUSH D"),
                out);
        assertTrue(out.contains("[BREAKPOINT] breakpoint at 0005 (BDOS call) at cycle 86, instruction 8\n"
                + "-> 0005: D3 01     OUT 01H"), out);
        assertTrue(out.contains("deleted #2 break 014B"), out);
        assertTrue(out.contains("error: no breakpoint or watchpoint #9"), out);
        // continuing runs the first BDOS call (printing the banner) and stops at the last one
        assertTrue(out.contains("MICROCOSM ASSOCIATES"), out);
        assertTrue(out.contains("[BREAKPOINT] breakpoint at 0005 (BDOS call) at cycle 4864, instruction 645"), out);
    }

    @Test
    void untilRunsToACycleOrInstructionCount() throws IOException {
        String out = session("until instr 100\nuntil cycle 1000\nuntil cycle 10\nuntil bogus 1\nquit\n");
        assertTrue(out.contains("[reached] cycle 933, instruction 100\n-> 02FD: D8        RC"), out);
        assertTrue(out.contains("[reached] cycle 1003, instruction 108\n-> 0318: CC 21 03  CZ 0321H"), out);
        assertTrue(out.contains("error: already at cycle"), out);
        assertTrue(out.contains("error: until cycle <n> | until instr <n>"), out);
    }

    @Test
    void nextCyclesFinishesTheInstructionThatCrosses() throws IOException {
        String out = session("next-cycles 12\nquit\n");
        assertTrue(out.contains("[STEPPED] 12 cycle(s) at cycle 20, instruction 2"), out); // JMP 10 + LXI 10
    }

    @Test
    void watchpointsOnMemoryAndIo() throws IOException {
        String out = session("""
                watch w 07B0-07BF
                cont
                delete all
                io out 1
                cont
                watch x 0x0153
                watch q 1
                cont
                quit
                """);
        assertTrue(out.contains("#2 watch [WRITE] 07B0..07BF"), out);
        assertTrue(out.contains("[WATCHPOINT] watch [WRITE] 07B0..07BF: WRITE 07BC value 01 at cycle 47, instruction 4"),
                out);
        assertTrue(out.contains("deleted 2 breakpoint(s)/watchpoint(s)"), out);
        assertTrue(out.contains("[WATCHPOINT] watch [IO_OUT] 0001: IO_OUT 0001 value"), out);
        assertTrue(out.contains("-> 0007: C9        RET"), out);
        assertTrue(out.contains("error: watch kind must be r, w, rw or x: q"), out);
        assertTrue(out.contains("[WATCHPOINT] watch [FETCH] 0153: FETCH 0153"), out);
    }

    @Test
    void memoryDisassemblyRegistersHistoryAndDump() throws IOException {
        String out = session("step 5\nregs\nmem 100 32\ndis 100 3\ndis\nhistory 2\ndump\nquit\n");
        assertTrue(out.contains("0100: C3 B2 01 4D 49 43 52 4F  43 4F 53 4D 20 41 53 53  |...MICROCOSM ASS|"), out);
        assertTrue(out.contains("   0100: C3 B2 01  JMP 01B2H\n   0103: 4D        MOV C,L\n   0104: 49        MOV C,C\n"),
                out);
        assertTrue(out.contains("-> 014C: EB        XCHG\n   014D: 0E 09     MVI C,09H"), out);
        assertTrue(out.contains("000000000030 000000000003 01B8: CD 4B 01  CALL 014BH"), out);
        assertTrue(out.contains("000000000047 000000000004 014B: D5        PUSH D"), out);
        assertTrue(out.contains("--- dump at cycle 58 ---\nregisters: PC=014C"), out);
    }

    @Test
    void traceToTheConsoleAndToAFile() throws IOException {
        Path file = dir.resolve("t.trace");
        String out = session("trace on\nstep 2\ntrace off\ntrace on " + file + "\nstep 3\ntrace off\nstep\nquit\n");
        assertTrue(out.contains("tracing to the console\n"), out);
        assertTrue(out.contains("000000000000 000000000000 0100: C3 B2 01  JMP 01B2H"), out);
        assertTrue(out.contains("000000000010 000000000001 01B2: 31 BD 07  LXI SP,07BDH"), out);
        var lines = Files.readAllLines(file);
        assertEquals(4, lines.size(), "header + 3 instructions");
        assertTrue(lines.get(0).startsWith("# i8080 trace"));
        assertTrue(lines.get(1).startsWith("000000000020 000000000002 01B5: 21 03 01"), lines.get(1));
    }

    @Test
    void saveLoadAndDiffSnapshots() throws IOException {
        Path snap = dir.resolve("s.json");
        String out = session("step 4\nsave " + snap + "\nstep 20\ndiff " + snap + "\nload " + snap + "\ndiff " + snap
                + "\nload nonexistent.json\nquit\n");
        assertTrue(out.contains("saved 15 field(s) and 65536 byte(s) of memory to " + snap), out);
        assertTrue(out.contains("PC          expected=014B"), out);
        assertTrue(out.contains("restored " + snap + "\n-> 014B: D5        PUSH D"), out);
        assertTrue(out.contains("snapshots are identical"), out);
        assertTrue(out.contains("error: cannot read snapshot nonexistent.json"), out);
        assertEquals(4, machine.cpu().instructionCount());
    }

    @Test
    void runningToTheEndAndBadInput() throws IOException {
        String out = session("cont\nfrobnicate\nmem zz\nstep -1\nhelp\nquit\nstep\n");
        assertTrue(out.contains("CPU IS OPERATIONAL"), out);
        assertTrue(out.contains("[BREAKPOINT] breakpoint at 0000 (program exit (CP/M warm boot)) at cycle 4914, instruction 650"),
                out);
        assertTrue(out.contains("unknown command 'frobnicate' (type 'help')"), out);
        assertTrue(out.contains("error: not a hex address: zz"), out);
        assertTrue(out.contains("error: step count must be at least 1"), out);
        assertTrue(out.contains("watch <r|w|rw|x> <addr[-addr]>"), out);
        assertEquals(650, machine.cpu().instructionCount(), "nothing runs after quit");
    }

    @Test
    void interruptStopsALongRun() throws Exception {
        machine = new CpmMachine(CpmRomTest.rom("CPUTEST.COM"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        DebuggerRepl repl = new DebuggerRepl(machine.replTarget(), new StringReader(""), out);
        // deterministic: the run pauses inside instruction 10,000 until another thread has called interrupt()
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        machine.debugger().addTraceListener(event -> {
            if (event.instruction() == 9_999) {
                reached.countDown();
                try {
                    assertTrue(interrupted.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        Thread t = new Thread(() -> repl.execute("cont"));
        t.start();
        assertTrue(reached.await(10, TimeUnit.SECONDS));
        assertTrue(repl.isRunning());
        repl.interrupt();
        interrupted.countDown();
        t.join(10_000);
        assertFalse(t.isAlive());
        String text = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(text.startsWith("[interrupted] at cycle "), text);
        assertEquals(10_000, machine.cpu().instructionCount(), "stops right after the interrupted instruction");
        assertFalse(machine.exited());
        assertFalse(repl.isRunning());
    }

    /** a session on a tiny program (no ROM needed): 0100: MVI A,41; NOP; ...; JMP 0000 */
    private String tinySession(java.io.Reader script) {
        machine = new CpmMachine(new byte[]{0x3E, 0x41, 0x00, 0x00, (byte) 0xC3, 0x00, 0x00});
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        try {
            new DebuggerRepl(machine.replTarget(), script, out).run();
        } catch (RuntimeException e) {
            out.println("SESSION ENDED BY " + e);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aMalformedSnapshotFileDoesNotEndTheSession() throws IOException {
        Path bad = dir.resolve("bad.json");
        Files.writeString(bad, "{\"format\":\"darchan-snapshot\",\"version\":1,\"machine\":\"i8080\",\"label\":\"\","
                + "\"fields\":[],\"memory\":[{\"name\":\"ram\",\"start\":0,\"length\":-1,\"hex\":[]}]}");
        Path escape = dir.resolve("escape.json");
        Files.writeString(escape, "{\"format\":\"darchan-snapshot\",\"version\":1,\"machine\":\"i8080\","
                + "\"label\":\"\\u-041\",\"fields\":[]}");
        String out = tinySession(new StringReader("load " + bad + "\ndiff " + escape + "\nstep\nquit\n"));
        assertFalse(out.contains("SESSION ENDED"), out);
        assertTrue(out.contains("error: ram.length must be in 0.."), out);
        assertTrue(out.contains("error: JSON error"), out);
        assertEquals(1, machine.cpu().instructionCount(), "the step after the errors ran");
    }

    @Test
    void anyReaderWorksAndTheTraceFileIsClosedWhenInputFails() throws IOException {
        Path file = dir.resolve("t.trace");
        java.io.Reader failing = new java.io.Reader() {
            private final StringReader script = new StringReader("trace on " + file + "\nstep 2\n");

            @Override
            public int read(char[] buf, int off, int len) throws IOException {
                int n = script.read(buf, off, len);
                if (n < 0) {
                    throw new IOException("terminal went away");
                }
                return n;
            }

            @Override
            public void close() {
            }
        };
        String out = tinySession(failing);
        assertTrue(out.contains("SESSION ENDED BY java.io.UncheckedIOException"), out);
        assertEquals(3, Files.readAllLines(file).size(), "header + 2 instructions, flushed and closed: " + out);
    }
}
