package com.darchan.diag.run;

import com.darchan.diag.MemoryRegion;
import com.darchan.diag.MemoryView;
import com.darchan.diag.Snapshot;
import com.darchan.diag.repl.DebuggerRepl;
import com.darchan.diag.repl.ReplTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Scripted debugger sessions on the toy accumulator machine: command semantics, argument parsing, messages. */
class ReplSessionTest {

    @TempDir
    Path dir;

    private ToyCpu cpu;
    private Debugger dbg;
    private ReplTarget machine;

    @BeforeEach
    void setUp() {
        cpu = new ToyCpu();
        dbg = new Debugger(cpu, a -> cpu.mem[a & 0xFF]);
        cpu.bus = dbg.busListener();
        // 00: NOP; 01: INC; 02: STA 80; 04: LDA 81; 06: OUT 07; 08: INC; 09: JMP 00
        cpu.load(0x00, 0x03, 0x02, 0x80, 0x01, 0x81, 0x05, 0x07, 0x03, 0x06, 0x00);
        machine = new ReplTarget() {
            @Override
            public String name() {
                return "toy";
            }

            @Override
            public Debugger debugger() {
                return dbg;
            }

            @Override
            public MemoryView memory() {
                return a -> cpu.mem[a & 0xFF];
            }

            @Override
            public int addressBits() {
                return 8;
            }

            @Override
            public int maxInstructionLength() {
                return 2;
            }

            @Override
            public int instructionLength(int address) {
                return ToyCpu.length(cpu.mem[address & 0xFF]);
            }

            @Override
            public String disassemble(int address) {
                int len = instructionLength(address);
                return ToyCpu.disassemble(address, new int[]{cpu.mem[address & 0xFF], cpu.mem[(address + 1) & 0xFF]}, len);
            }

            @Override
            public Snapshot fullSnapshot() {
                byte[] ram = new byte[256];
                for (int i = 0; i < 256; i++) {
                    ram[i] = (byte) cpu.mem[i];
                }
                return cpu.snapshot().withRegion(new MemoryRegion("ram", 0, ram));
            }

            @Override
            public void restoreFull(Snapshot s) {
                cpu.restore(s);
                byte[] ram = s.region("ram").orElseThrow().bytes();
                for (int i = 0; i < 256; i++) {
                    cpu.mem[i] = ram[i] & 0xFF;
                }
            }

            @Override
            public Map<Integer, String> addressLabels() {
                return Map.of(0x06, "the OUT");
            }
        };
    }

    private String session(String script) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        new DebuggerRepl(machine, new StringReader(script), out).run();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static final String START = "-> 00: 00     NOP\n   PC=00 A=00 F=00 HALT=0 CYCLES=0 INSTRUCTIONS=0\n";

    @Test
    void theBannerShowsTheNextInstructionAndTheRegisters() {
        String out = session("");
        assertEquals("toy debugger. Type 'help' for commands.\n" + START + DebuggerRepl.PROMPT + "\n", out);
        assertEquals("dbg> ", DebuggerRepl.PROMPT);
        assertTrue(DebuggerRepl.HELP.contains("step [n]"));
    }

    @Test
    void blankLinesCommentsAndUnknownCommandsAreHandled() {
        String out = session("\n   \n# a comment\n#another\nfrobnicate now\nQUIT\n");
        assertEquals("toy debugger. Type 'help' for commands.\n" + START
                + "dbg> dbg> dbg> dbg> dbg> unknown command 'frobnicate' (type 'help')\ndbg> ", out);
        for (String alias : new String[]{"help", "h", "?"}) {
            assertEquals("toy debugger. Type 'help' for commands.\n" + START + "dbg> " + DebuggerRepl.HELP + "\ndbg> ",
                    session(alias + "\nq\n"), alias);
        }
        // aliases of quit end the session; commands are case-insensitive
        for (String quit : new String[]{"quit", "q", "exit", "QUIT", "Exit"}) {
            assertFalse(new DebuggerRepl(machine, new StringReader(""), new PrintStream(new ByteArrayOutputStream()))
                    .execute(quit), quit);
        }
        assertTrue(new DebuggerRepl(machine, new StringReader(""), new PrintStream(new ByteArrayOutputStream()))
                .execute("regs"));
    }

    @Test
    void stepRunsTheRequestedCountAndRejectsNonsense() {
        String out = session("step\ns 3\nstep 0\nstep -2\nstep abc\nstep 0x2\nstep 1_0\nq\n");
        assertTrue(out.contains("[STEPPED] 1 step(s) at cycle 4, instruction 1\n-> 01: 03     INC\n"), out);
        assertTrue(out.contains("[STEPPED] 3 step(s) at cycle 22, instruction 4\n"), out);
        assertEquals(2, out.split("error: step count must be at least 1", -1).length - 1, out);
        assertTrue(out.contains("error: not a number: abc"), out);
        assertTrue(out.contains("[STEPPED] 2 step(s)"), "hex counts need 0x: " + out);
        assertTrue(out.contains("[STEPPED] 10 step(s)"), "underscores are ignored: " + out);
    }

    @Test
    void nextCyclesAndCont() {
        String out = session("nc 10\nnc\ncont 20\nq\n");
        assertTrue(out.contains("[STEPPED] 10 cycle(s) at cycle 15, instruction 3\n"), "finishes the instruction: " + out);
        assertTrue(out.contains("error: usage: next-cycles <n>"), out);
        assertTrue(out.contains("[CYCLE_LIMIT] cycle target 35 reached at cycle 40, instruction 7"), out);
    }

    @Test
    void untilCycleAndInstructionStopWhereAsked() {
        String out = session("until cycle 20\nuntil cycle 20\nuntil instr 6\nuntil instr 6\nuntil\nuntil cycle\nuntil bogus 3\n"
                + "until cycles 100\nuntil instrs 20\nq\n");
        assertTrue(out.contains("[reached] cycle 22, instruction 4\n"), out);
        assertTrue(out.contains("error: already at cycle 22"), out);
        assertTrue(out.contains("[reached] cycle 33, instruction 6\n"), out);
        assertTrue(out.contains("error: already at instruction 6"), out);
        assertTrue(out.contains("error: usage: until cycle <n> | until instr <n>"), out);
        assertTrue(out.contains("error: usage: until cycle <n>"), out);
        assertTrue(out.contains("error: until cycle <n> | until instr <n>"), out);
        assertTrue(cpu.cycles >= 100);
        assertEquals(20, cpu.instructions);
    }

    @Test
    void breakpointsGetIdsLabelsAndStopTheRun() {
        String out = session("break 6\nb 0x6\nbreak 06H\nbreak $04\nbreak\nbreak zz\nbreak 100\nbreaks\ncont\ndelete 1\ncont\nq\n");
        assertTrue(out.contains("#1 break 06 (the OUT)\n"), out);
        assertEquals(2, out.split("breakpoint already set: #1", -1).length - 1, out);
        assertTrue(out.contains("#2 break 04\n"), out);
        assertTrue(out.contains("error: usage: break <addr>"), out);
        assertTrue(out.contains("error: not a hex address: zz"), out);
        assertTrue(out.contains("error: address out of range: 100"), out);
        assertTrue(out.contains("#1 break 06 (the OUT)\n#2 break 04\n"), out);
        assertTrue(out.contains("[BREAKPOINT] breakpoint at 0004 at cycle 15, instruction 3\n-> 04: 01 81  LDA 81\n"), out);
        assertTrue(out.contains("[BREAKPOINT] breakpoint at 0004 at cycle 55, instruction 10"), "the second cont, after deleting #1: " + out);
        assertTrue(out.contains("deleted #1 break 06 (the OUT)\n"), out);
    }

    @Test
    void breakpointsSetBeforeTheSessionAreListedWithIds() {
        dbg.addBreakpoint(0x04);
        dbg.addWatchpoint(Watchpoint.memoryWrite(0x80));
        String out = session("breaks\nbreak 08\nwatches\nq\n");
        assertTrue(out.contains("#1 break 04\n#2 watch [WRITE] 0080\n"), out);
        assertTrue(out.contains("#3 break 08\n"), "new ids continue after the existing ones: " + out);
    }

    @Test
    void watchpointsCoverRangesAndKinds() {
        String out = session("watch w 80\nwatch r 81-82\nwatch rw 10-12\nwatch x 09\nwatch\nwatch q 10\nwatch w\nwatch w 9-1\n"
                + "io out 07\nio in 3\nio inout 4\nio\nio sideways 3\nio out\nbreaks\nq\n");
        assertTrue(out.contains("#1 watch [WRITE] 0080\n"), out);
        assertTrue(out.contains("#2 watch [READ] 0081..0082\n"), out);
        assertTrue(out.contains("#3 watch [READ, WRITE] 0010..0012\n"), out);
        assertTrue(out.contains("#4 watch [FETCH] 0009\n"), out);
        assertTrue(out.contains("error: usage: watch <r|w|rw|x> <addr[-addr]>"), out);
        assertTrue(out.contains("error: watch kind must be r, w, rw or x: q"), out);
        assertTrue(out.contains("error: usage: watch w <addr[-addr]>"), out);
        assertTrue(out.contains("error: bad range 9..1"), out);
        assertTrue(out.contains("#5 watch [IO_OUT] 0007\n"), out);
        assertTrue(out.contains("#6 watch [IO_IN] 0003\n"), out);
        assertTrue(out.contains("#7 watch [IO_IN, IO_OUT] 0004\n"), out);
        assertTrue(out.contains("error: usage: io <in|out|inout> <port>"), out);
        assertTrue(out.contains("error: io direction must be in, out or inout: sideways"), out);
        assertTrue(out.contains("error: usage: io out <port>"), out);
        assertEquals(7, dbg.watchpoints().size());
        String run = session("watch w 80\ncont\nq\n");
        assertTrue(run.contains("[WATCHPOINT] watch [WRITE] 0080: WRITE 0080 value 01 at cycle 15, instruction 3\n"), run);
    }

    @Test
    void deleteRemovesOneOrAllAndStopsTheirEffect() {
        String out = session("break 04\nwatch w 80\nbreaks\ndelete 2\nbreaks\ndelete all\nbreaks\ndelete\ndelete x\nd #7\nq\n");
        assertTrue(out.contains("deleted #2 watch [WRITE] 0080\n"), out);
        assertTrue(out.contains("deleted 1 breakpoint(s)/watchpoint(s)\n"), out);
        assertTrue(out.contains("no breakpoints or watchpoints\n"), out);
        assertTrue(out.contains("error: usage: delete <id|all>"), out);
        assertTrue(out.contains("error: not a number: x"), out);
        assertTrue(out.contains("error: no breakpoint or watchpoint #7"), out);
        assertTrue(dbg.breakpoints().isEmpty());
        assertTrue(dbg.watchpoints().isEmpty());
        // 'delete all' with several entries counts them all, and 'DELETE ALL' is case-insensitive
        String many = session("break 01\nbreak 02\nwatch r 03\nio in 4\ndelete ALL\nq\n");
        assertTrue(many.contains("deleted 4 breakpoint(s)/watchpoint(s)\n"), many);
        assertTrue(dbg.breakpoints().isEmpty());
        assertTrue(dbg.watchpoints().isEmpty());
        // 'delete all' on a set-up that has cycle breakpoints in the debugger leaves them alone (not the REPL's)
    }

    @Test
    void inspectingMemoryAndCode() {
        String out = session("regs\nr\nmem 0 16\nm 08 2\nmem\nmem 0xF0\nmem zz\ndis\ndis 2 3\nu 09 1\ndis 0 0\nstep\ndis 0 2\nq\n");
        assertTrue(out.contains("PC=00 A=00 F=00 HALT=0 CYCLES=0 INSTRUCTIONS=0\n"), out);
        assertTrue(out.contains("0000: 00 03 02 80 01 81 05 07  03 06 00 00 00 00 00 00  |................|\n"), out);
        assertTrue(out.contains("0008: 03 06"), out);
        assertTrue(out.contains("error: usage: mem <addr> [len]"), out);
        assertTrue(out.contains("00F0: 00 00"), "mem 0xF0 is clamped to the end of the 8-bit address space: " + out);
        assertTrue(out.contains("error: not a hex address: zz"), out);
        assertTrue(out.contains("-> 00: 00     NOP\n   01: 03     INC\n   02: 02 80  STA 80\n"), "dis from the PC, 10 lines: " + out);
        assertTrue(out.contains("   02: 02 80  STA 80\n   04: 01 81  LDA 81\n   06: 05 07  OUT 07\n"), out);
        assertTrue(out.contains("   09: 06 00  JMP 00\n"), out);
        assertTrue(out.contains("   00: 00     NOP\n-> 01: 03     INC\n"), "the arrow follows the PC: " + out);
    }

    @Test
    void historyShowsTheLastInstructions() {
        String out = session("history\nstep 5\nhistory 2\nhist\nq\n");
        assertTrue(out.contains("no history yet\n"), out);
        String afterStep = out.substring(out.indexOf("[STEPPED] 5 step(s)"));
        String[] sections = afterStep.split("dbg> ");
        String twoLines = sections[1];
        assertEquals("000000000015 000000000003 04: 01 81  LDA 81   A=01 F=00 Z=-\n"
                + "000000000022 000000000004 06: 05 07  OUT 07   A=00 F=00 Z=-\n", twoLines);
        assertEquals(5, sections[2].lines().count(), "the default shows up to 20, here all 5: " + sections[2]);
        dbg.setRingBufferCapacity(0);
        assertTrue(session("history\nq\n").contains("no history yet\n"));
    }

    @Test
    void traceGoesToTheConsoleOrAFile() throws IOException {
        String out = session("trace on\nstep 2\ntrace off\nstep\ntrace off\ntrace\ntrace maybe\nq\n");
        assertTrue(out.contains("tracing to the console\n"), out);
        assertTrue(out.contains("000000000000 000000000000 00: 00     NOP      A=00 F=00 Z=-\n"), out);
        assertTrue(out.contains("tracing stopped\n"), out);
        assertTrue(out.contains("tracing is off\n"), out);
        assertTrue(out.contains("error: usage: trace on [file] | trace off"), out);
        assertTrue(out.contains("error: trace on [file] | trace off"), out);
        assertFalse(out.contains("000000000008 000000000002 02: 02 80"), "tracing was off for the third step: " + out);

        Path file = dir.resolve("t.trace");
        String f = session("trace on " + file + "\nstep 3\ntrace off\nstep\nq\n");
        assertTrue(f.contains("tracing to " + file + "\n"), f);
        java.util.List<String> lines = Files.readAllLines(file);
        assertEquals(4, lines.size(), "header and three instructions: " + lines);
        assertTrue(lines.get(0).startsWith("# toy trace: cycle instruction pc: bytes  disassembly A F"), lines.get(0));
        // quitting with a trace open closes the file
        Path second = dir.resolve("u.trace");
        session("trace on " + second + "\nstep 2\nq\n");
        assertEquals(3, Files.readAllLines(second).size());
        // and so does the end of input, and replacing one trace by another
        Path third = dir.resolve("v.trace");
        String eof = session("trace on " + third + "\nstep 2\n");
        assertEquals(3, Files.readAllLines(third).size(), eof);
        // the machine and its debugger outlive the session: they must not keep writing to the closed file
        dbg.step(3);
        assertEquals(3, Files.readAllLines(third).size(), "nothing more is written after the session ended");
        assertEquals(3, Files.readAllLines(second).size());
        Path a = dir.resolve("a.trace");
        Path b = dir.resolve("b.trace");
        session("trace on " + a + "\nstep\ntrace on " + b + "\nstep\nq\n");
        assertEquals(2, Files.readAllLines(a).size());
        assertEquals(2, Files.readAllLines(b).size());
    }

    @Test
    void saveLoadAndDiffRoundTripTheWholeMachine() {
        Path file = dir.resolve("s.json");
        String out = session("step 3\nsave " + file + "\nstep 4\ndiff " + file + "\nload " + file + "\ndiff " + file
                + "\nsave\nload\ndiff\nload " + dir.resolve("missing.json") + "\nq\n");
        assertTrue(out.contains("saved 6 field(s) and 256 byte(s) of memory to " + file + "\n"), out);
        assertTrue(out.contains("3 field(s) and 0 memory byte(s) differ"), out);
        assertTrue(out.contains("restored " + file + "\n-> 04: 01 81  LDA 81\n"), out);
        assertTrue(out.contains("snapshots are identical (expected \"cycle 15\" vs actual \"now\")"), out);
        assertEquals(3, out.split("error: usage: (save|load|diff) <file.json>", -1).length - 1, out);
        assertTrue(out.contains("error: cannot read snapshot"), out);
        assertEquals(3, cpu.instructions, "load restored the machine");
    }

    @Test
    void aCrashingMachinePrintsTheReportAndTheSessionGoesOn() {
        cpu.load(0x03, 0xFF);
        String out = session("cont\nregs\nq\n");
        assertTrue(out.contains("the machine threw an exception: java.lang.IllegalStateException: crash opcode FF\n"), out);
        assertTrue(out.contains("--- last 2 of 2 traced instruction(s) (toy) ---"), out);
        assertTrue(out.contains("dbg> PC=02 A=01 F=00"), "the session continues: " + out);
    }

    @Test
    void anInterruptStopsARunningCommand() {
        cpu.load(0x06, 0x00); // JMP 0 forever
        DebuggerRepl repl = new DebuggerRepl(machine, new StringReader(""),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertFalse(repl.isRunning());
        Thread t = new Thread(() -> repl.execute("cont"));
        t.start();
        try {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!repl.isRunning() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            repl.interrupt();
            t.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertFalse(t.isAlive(), "the run ended after interrupt()");
        assertFalse(repl.isRunning());
    }

    @Test
    void longStepsRunInChunksAndCanBeInterrupted() {
        String out = session("step 250_000\nq\n");
        assertTrue(out.contains("[STEPPED] 250000 step(s)"), out);
        assertEquals(250_000, cpu.instructions);
        cpu.instructions = 0;
        DebuggerRepl repl = new DebuggerRepl(machine, new StringReader(""),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        repl.interrupt();
        // execute() clears a stale interrupt request before running
        repl.execute("step 3");
        assertEquals(3, cpu.instructions);
    }

    // ---- exact transcripts and edge arguments ----------------------------------------------------------------------

    private static final String BANNER = "toy debugger. Type 'help' for commands.\n" + START;

    @Test
    void breakAndWatchCommandsAnswerWithTheirIdAndDescription() {
        assertEquals(BANNER + "dbg> #1 break 06 (the OUT)\ndbg> #2 break 04\ndbg> #3 watch [WRITE] 0080\ndbg> ",
                session("break 06\nbreak 04\nwatch w 80\n").replace("\ndbg> \n", "\ndbg> "));
        dbg.clearBreakpointsAndWatchpoints();
        assertEquals(BANNER + "dbg> #1 break 04\ndbg> breakpoint already set: #1\ndbg> #2 break 05\ndbg> ",
                session("break 4\nbreak 04\nb 5\nquit\n"));
    }

    @Test
    void watchRangesNeedBothEndsAndCountsAcceptUpperCaseHexPrefixes() {
        String out = session("watch r -80\nwatch r 80-\nbreak 0X10\nstep 0X2\nmem 0X0 0X4\nq\n");
        assertTrue(out.contains("error: not a hex address: \n"), out);
        assertTrue(out.contains("#1 break 10\n"), out);
        assertTrue(out.contains("[STEPPED] 2 step(s)"), out);
        assertTrue(out.contains("0000: 00 03 02 80  "), out);
        assertEquals(0, dbg.watchpoints().size());
    }

    @Test
    void memAndDisAreExactAboutLengthsAndWrapAround() {
        // an explicit length, a default length clamped to the end of the address space, and zero
        String two = session("mem 0 2\nq\n");
        assertEquals(BANNER + "dbg> 0000: 00 03" + " ".repeat(3 * 14 + 1) + "  |..|\ndbg> ", two);
        String clamped = session("mem F0\nq\n");
        assertEquals(1, clamped.lines().filter(l -> l.contains("00F0:")).count(), clamped);
        assertEquals(0, clamped.lines().filter(l -> l.contains("0100:")).count(), clamped);
        long defaultLines = session("mem 0\nq\n").lines().filter(l -> l.matches("(dbg> )?[0-9A-F]{4}: .*")).count();
        assertEquals(4, defaultLines, "64 bytes by default: four lines");
        assertEquals(10, session("dis\nq\n").lines().filter(l -> l.matches("(dbg> )?(-> |   )[0-9A-F]{2}: .*")).count() - 1,
                "ten instructions by default (the banner shows the first one too)");
        assertEquals(1, session("dis 0 1\nq\n").lines().filter(l -> l.contains("-> 00:") || l.contains("   00:")).count()
                - 1);
        // the listing wraps from the last address back to 00
        String wrap = session("dis FF 3\nq\n");
        assertTrue(wrap.contains("   FF: 00     NOP\n-> 00: 00     NOP\n   01: 03     INC\n"), wrap);
        assertFalse(wrap.contains("100:"), wrap);
    }

    @Test
    void untilAtTheCurrentCycleIsRejected() {
        String out = session("step 2\nuntil cycle 8\nq\n");
        assertTrue(out.contains("error: already at cycle 8\n"), out);
        assertEquals(2, cpu.instructions);
    }

    @Test
    void deletingAWatchpointThatWasThereBeforeTheSessionRemovesItFromTheDebugger() {
        dbg.addWatchpoint(Watchpoint.memoryWrite(0x80));
        dbg.addBreakpoint(0x04);
        session("delete 1\nq\n");
        assertEquals(0, dbg.watchpoints().size() + dbg.breakpoints().size() - 1, "the first entry (the breakpoint) went");
        assertEquals(1, dbg.watchpoints().size());
        session("delete 1\nq\n");
        assertEquals(0, dbg.watchpoints().size());
    }

    @Test
    void thePromptIsFlushedBeforeWaitingForInput() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(new java.io.BufferedOutputStream(sink, 8192), false, StandardCharsets.UTF_8);
        boolean[] promptVisible = {false};
        java.io.Reader in = new java.io.Reader() {
            private boolean done;

            @Override
            public int read(char[] buf, int off, int len) {
                if (done) {
                    return -1;
                }
                promptVisible[0] = sink.toString(StandardCharsets.UTF_8).endsWith("dbg> ");
                done = true;
                return -1;
            }

            @Override
            public void close() {
            }
        };
        new DebuggerRepl(machine, in, out).run();
        assertTrue(promptVisible[0], "the prompt must have reached the stream before the REPL blocks on input");
    }

    @Test
    void anInterruptEndsAnyLongRunningCommand() throws InterruptedException {
        cpu.load(0x06, 0x00); // JMP 0 forever
        for (String command : new String[]{"cont", "nc 1000000000000", "until cycle 1000000000000",
                "until instr 1000000000000", "step 1000000000000"}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
            DebuggerRepl repl = new DebuggerRepl(machine, new StringReader(""), out);
            Thread t = new Thread(() -> repl.execute(command));
            t.start();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!repl.isRunning() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            Thread.sleep(20);
            repl.interrupt();
            t.join(10_000);
            assertFalse(t.isAlive(), command + " did not stop");
            assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("[interrupted] at cycle"), command + ": " + bytes);
        }
    }
}
