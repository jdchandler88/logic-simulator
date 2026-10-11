package com.darchan.diag.repl;

import com.darchan.diag.MemoryView;
import com.darchan.diag.RegisterDump;
import com.darchan.diag.Snapshot;
import com.darchan.diag.SnapshotJson;
import com.darchan.diag.run.AccessType;
import com.darchan.diag.run.DebugTarget;
import com.darchan.diag.run.Debugger;
import com.darchan.diag.run.ExecutionFailure;
import com.darchan.diag.run.StopReason;
import com.darchan.diag.run.Watchpoint;
import com.darchan.diag.trace.TextTraceWriter;
import com.darchan.diag.trace.TraceEvent;
import com.darchan.diag.trace.TraceFormatter;
import com.darchan.diag.trace.TraceListener;
import com.darchan.diag.trace.TraceRingBuffer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An interactive command-line debugger over any {@link ReplTarget}. Input and output are injected
 * ({@link Reader} / {@link PrintStream}), so sessions can be scripted and tested; {@code project.App debug}
 * connects it to the console.
 *
 * <p>Addresses and I/O ports are hexadecimal, written {@code 0100}, {@code 0x100} or {@code 100H}. Counts are
 * decimal (or hex with {@code 0x}). Every command that runs the machine ends by showing why it stopped, the next
 * instruction (disassembled) and the registers. Type {@code help} for the command list ({@link #HELP}).
 *
 * <p>A run in progress ({@code cont}, {@code until}, {@code step}/{@code next-cycles} with large counts) can be
 * stopped from another thread with {@link #interrupt()} (the console front end calls it on Ctrl-C). End of input
 * (Ctrl-D) quits like {@code quit}.
 */
public final class DebuggerRepl {

    public static final String PROMPT = "dbg> ";

    public static final String HELP = """
            Running (each shows the stop reason, the next instruction and the registers):
              step [n]                 execute n instructions (default 1)              alias: s
              next-cycles <n>          execute until n more cycles have passed          alias: nc
              cont [max-cycles]        run until a breakpoint/watchpoint/halt/Ctrl-C    alias: c
              until cycle <n>          run until the cycle count reaches n
              until instr <n>          run until n instructions have executed
            Breakpoints and watchpoints (each gets an id):
              break <addr>             stop before executing addr                       alias: b
              watch <r|w|rw|x> <addr[-addr]>  stop after a read/write/either/fetch(x) in the range
              io <in|out|inout> <port> stop after an IN/OUT on the port
              breaks                   list them                                        alias: info
              delete <id|all>          remove one or all                                alias: d
            Looking at state:
              regs                     registers                                        alias: r
              mem <addr> [len]         hex dump (default 64 bytes)                      alias: m
              dis [addr] [n]           disassemble n instructions (default: PC, 10)    alias: u
              history [n]              last n executed instructions (default 20)        alias: hist
              dump                     registers plus the debugger's dump ranges
            Tracing and snapshots:
              trace on [file]          trace every instruction to the console or a file
              trace off
              save <file.json>         save registers and memory
              load <file.json>         restore registers and memory
              diff <file.json>         compare the current state with a saved one
            Other:
              help                     this text                                        alias: h, ?
              quit                     leave (also Ctrl-D)                               alias: q, exit
            Addresses and ports are hex (0100, 0x100, 100H); counts are decimal.""";

    private static final long CHUNK = 100_000;

    private record Entry(String description, Runnable remove) {
    }

    private final ReplTarget machine;
    private final Debugger debugger;
    private final DebugTarget target;
    private final BufferedReader in;
    private final PrintStream out;
    private final int addressDigits;
    private final Map<Integer, Entry> entries = new LinkedHashMap<>();
    private int nextId = 1;
    private volatile boolean interruptRequested;
    private volatile boolean running;
    private TraceListener traceListener;
    private TextTraceWriter traceFile;

    /**
     * @param machine the machine to debug
     * @param in      where commands come from, one per line (any {@link Reader}; wrapped in a
     *                {@link BufferedReader} unless it already is one)
     * @param out     where output goes
     */
    public DebuggerRepl(ReplTarget machine, Reader in, PrintStream out) {
        this.machine = Objects.requireNonNull(machine);
        this.debugger = machine.debugger();
        this.target = debugger.target();
        Objects.requireNonNull(in);
        this.in = in instanceof BufferedReader b ? b : new BufferedReader(in);
        this.out = Objects.requireNonNull(out);
        this.addressDigits = (machine.addressBits() + 3) / 4;
        for (int pc : debugger.breakpoints()) {
            addBreakEntry(pc);
        }
        for (Watchpoint w : debugger.watchpoints()) {
            entries.put(nextId++, new Entry(w.toString(), () -> debugger.removeWatchpoint(w)));
        }
    }

    /** Asks a running command to stop after the current instruction. Safe to call from any thread. */
    public void interrupt() {
        interruptRequested = true;
    }

    /** true while a command is executing the machine */
    public boolean isRunning() {
        return running;
    }

    /**
     * Reads and executes commands until {@code quit} or end of input. A command that fails prints
     * {@code error: ...} and the session goes on; a trace file opened with {@code trace on FILE} is closed however
     * the session ends.
     */
    public void run() {
        try {
            out.println(machine.name() + " debugger. Type 'help' for commands.");
            showCurrent();
            while (true) {
                out.print(PROMPT);
                out.flush();
                String line;
                try {
                    line = in.readLine();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                if (line == null) {
                    out.println();
                    break;
                }
                if (!execute(line)) {
                    break;
                }
            }
        } finally {
            closeTraceFile();
        }
    }

    /**
     * Executes one command line.
     *
     * @return false if the command was {@code quit}
     */
    public boolean execute(String line) {
        String[] words = line.trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty() || words[0].startsWith("#")) {
            return true;
        }
        String cmd = words[0].toLowerCase(Locale.ROOT);
        String[] args = Arrays.copyOfRange(words, 1, words.length);
        interruptRequested = false;
        try {
            switch (cmd) {
                case "quit", "q", "exit" -> {
                    closeTraceFile();
                    return false;
                }
                case "help", "h", "?" -> out.println(HELP);
                case "step", "s" -> step(args.length > 0 ? count(args[0]) : 1);
                case "next-cycles", "nc" -> nextCycles(count(need(args, 0, "next-cycles <n>")));
                case "cont", "c", "continue", "run" -> cont(args.length > 0 ? count(args[0]) : Long.MAX_VALUE);
                case "until" -> until(args);
                case "break", "b" -> addBreak(address(need(args, 0, "break <addr>")));
                case "watch", "w" -> watch(args);
                case "io" -> io(args);
                case "breaks", "info", "watches" -> listEntries();
                case "delete", "d" -> delete(need(args, 0, "delete <id|all>"));
                case "regs", "r" -> out.println(registers());
                case "mem", "m", "x" -> mem(args);
                case "dis", "u" -> dis(args);
                case "trace" -> trace(args);
                case "history", "hist" -> history(args.length > 0 ? (int) count(args[0]) : 20);
                case "dump" -> out.println(debugger.dump());
                case "save" -> save(need(args, 0, "save <file.json>"));
                case "load" -> load(need(args, 0, "load <file.json>"));
                case "diff" -> diff(need(args, 0, "diff <file.json>"));
                default -> out.println("unknown command '" + words[0] + "' (type 'help')");
            }
        } catch (ExecutionFailure e) {
            out.println("the machine threw an exception: " + e.getCause());
            out.println(e.report());
        } catch (RuntimeException e) {
            // one bad command (a malformed snapshot file, a bad argument...) never ends the session
            out.println("error: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        } finally {
            running = false;
        }
        return true;
    }

    // ---- running ---------------------------------------------------------------------------------------------

    private void step(long n) {
        if (n < 1) {
            throw new IllegalArgumentException("step count must be at least 1");
        }
        running = true;
        long done = 0;
        StopReason r;
        do {
            long chunk = Math.min(CHUNK, n - done);
            r = debugger.step(chunk);
            done += chunk;
        } while (r.kind() == StopReason.Kind.STEPPED && done < n && !interruptRequested);
        if (r.kind() == StopReason.Kind.STEPPED) {
            r = new StopReason(StopReason.Kind.STEPPED, done + " step(s)", r.cycle(), r.pc());
        }
        report(r);
    }

    private void nextCycles(long n) {
        running = true;
        StopReason r = debugger.runUntil(() -> interruptRequested, n);
        if (r.kind() == StopReason.Kind.CYCLE_LIMIT) {
            r = new StopReason(StopReason.Kind.STEPPED, n + " cycle(s)", r.cycle(), r.pc());
        }
        report(r);
    }

    private void cont(long maxCycles) {
        running = true;
        report(debugger.runUntil(() -> interruptRequested, maxCycles));
    }

    private void until(String[] args) {
        String what = need(args, 0, "until cycle <n> | until instr <n>").toLowerCase(Locale.ROOT);
        long n = count(need(args, 1, "until " + what + " <n>"));
        running = true;
        switch (what) {
            case "cycle", "cycles" -> {
                if (target.cycleCount() >= n) {
                    throw new IllegalArgumentException("already at cycle " + target.cycleCount());
                }
                StopReason r = debugger.runUntil(() -> interruptRequested, n - target.cycleCount());
                report(r.kind() == StopReason.Kind.CYCLE_LIMIT
                        ? new StopReason(StopReason.Kind.CONDITION, "cycle " + n, r.cycle(), r.pc()) : r);
            }
            case "instr", "instruction", "instructions", "instrs" -> {
                if (target.instructionCount() >= n) {
                    throw new IllegalArgumentException("already at instruction " + target.instructionCount());
                }
                report(debugger.runUntil(() -> interruptRequested || target.instructionCount() >= n,
                        Long.MAX_VALUE));
            }
            default -> throw new IllegalArgumentException("until cycle <n> | until instr <n>");
        }
    }

    private void report(StopReason r) {
        if (interruptRequested) {
            out.println("[interrupted] at cycle " + target.cycleCount() + ", instruction " + target.instructionCount());
            interruptRequested = false;
        } else if (r.kind() == StopReason.Kind.CONDITION) {
            out.println("[reached] cycle " + target.cycleCount() + ", instruction " + target.instructionCount());
        } else {
            String label = r.kind() == StopReason.Kind.BREAKPOINT ? machine.addressLabels().get(r.pc()) : null;
            out.println("[" + r.kind() + "] " + r.detail() + (label == null ? "" : " (" + label + ")")
                    + " at cycle " + r.cycle() + ", instruction " + target.instructionCount());
        }
        showCurrent();
    }

    /** the next instruction and the registers */
    private void showCurrent() {
        out.println("-> " + listingLine(target.programCounter()));
        out.println("   " + registers());
    }

    private String registers() {
        return RegisterDump.format(target.snapshot());
    }

    // ---- breakpoints -----------------------------------------------------------------------------------------

    private void addBreak(int pc) {
        for (Map.Entry<Integer, Entry> e : entries.entrySet()) {
            if (e.getValue().description().equals(breakDescription(pc))) {
                out.println("breakpoint already set: #" + e.getKey());
                return;
            }
        }
        debugger.addBreakpoint(pc);
        int id = addBreakEntry(pc);
        out.println("#" + id + " " + entries.get(id).description());
    }

    private String breakDescription(int pc) {
        String label = machine.addressLabels().get(pc);
        return "break " + hex(pc) + (label == null ? "" : " (" + label + ")");
    }

    private int addBreakEntry(int pc) {
        int id = nextId++;
        entries.put(id, new Entry(breakDescription(pc), () -> debugger.removeBreakpoint(pc)));
        return id;
    }

    private void watch(String[] args) {
        String kind = need(args, 0, "watch <r|w|rw|x> <addr[-addr]>").toLowerCase(Locale.ROOT);
        Set<AccessType> types = switch (kind) {
            case "r", "read" -> EnumSet.of(AccessType.READ);
            case "w", "write" -> EnumSet.of(AccessType.WRITE);
            case "rw", "a", "access" -> EnumSet.of(AccessType.READ, AccessType.WRITE);
            case "x", "fetch", "exec" -> EnumSet.of(AccessType.FETCH);
            default -> throw new IllegalArgumentException("watch kind must be r, w, rw or x: " + kind);
        };
        String range = need(args, 1, "watch " + kind + " <addr[-addr]>");
        int dash = range.indexOf('-');
        int from = address(dash < 0 ? range : range.substring(0, dash));
        int to = dash < 0 ? from : address(range.substring(dash + 1));
        addWatch(new Watchpoint(types, from, to));
    }

    private void io(String[] args) {
        String dir = need(args, 0, "io <in|out|inout> <port>").toLowerCase(Locale.ROOT);
        int port = address(need(args, 1, "io " + dir + " <port>"));
        addWatch(switch (dir) {
            case "in" -> Watchpoint.ioIn(port);
            case "out" -> Watchpoint.ioOut(port);
            case "inout", "any", "both" -> Watchpoint.io(port);
            default -> throw new IllegalArgumentException("io direction must be in, out or inout: " + dir);
        });
    }

    private void addWatch(Watchpoint w) {
        debugger.addWatchpoint(w);
        int id = nextId++;
        entries.put(id, new Entry(w.toString(), () -> debugger.removeWatchpoint(w)));
        out.println("#" + id + " " + w);
    }

    private void listEntries() {
        if (entries.isEmpty()) {
            out.println("no breakpoints or watchpoints");
        }
        entries.forEach((id, e) -> out.println("#" + id + " " + e.description()));
    }

    private void delete(String what) {
        if (what.equalsIgnoreCase("all")) {
            entries.values().forEach(e -> e.remove().run());
            out.println("deleted " + entries.size() + " breakpoint(s)/watchpoint(s)");
            entries.clear();
            return;
        }
        int id = (int) count(what.startsWith("#") ? what.substring(1) : what);
        Entry e = entries.remove(id);
        if (e == null) {
            throw new IllegalArgumentException("no breakpoint or watchpoint #" + id);
        }
        e.remove().run();
        out.println("deleted #" + id + " " + e.description());
    }

    // ---- inspecting ------------------------------------------------------------------------------------------

    private void mem(String[] args) {
        int start = address(need(args, 0, "mem <addr> [len]"));
        int len = args.length > 1 ? (int) count(args[1]) : 64;
        out.println(debugger.hexDump(start, Math.min(len, (1 << machine.addressBits()) - start)));
    }

    private void dis(String[] args) {
        int address = args.length > 0 ? address(args[0]) : target.programCounter();
        int n = args.length > 1 ? (int) count(args[1]) : 10;
        int mask = (1 << machine.addressBits()) - 1;
        for (int i = 0; i < n; i++) {
            out.println((address == target.programCounter() ? "-> " : "   ") + listingLine(address));
            address = (address + Math.max(1, machine.instructionLength(address))) & mask;
        }
    }

    /** {@code 0100: 31 00 07  LXI SP,0700H} */
    private String listingLine(int address) {
        MemoryView mem = machine.memory();
        int len = machine.instructionLength(address);
        int mask = (1 << machine.addressBits()) - 1;
        StringBuilder sb = new StringBuilder(hex(address)).append(':');
        StringBuilder bytes = new StringBuilder();
        for (int k = 0; k < len; k++) {
            bytes.append(String.format(" %02X", mem.peek((address + k) & mask)));
        }
        sb.append(bytes).append(" ".repeat(Math.max(0, 3 * machine.maxInstructionLength() - bytes.length())));
        sb.append("  ").append(machine.disassemble(address));
        return sb.toString();
    }

    private void history(int n) {
        TraceRingBuffer ring = debugger.ringBuffer();
        if (ring == null || ring.size() == 0) {
            out.println("no history yet");
            return;
        }
        List<String> lines = ring.lines();
        lines.subList(Math.max(0, lines.size() - n), lines.size()).forEach(out::println);
    }

    // ---- tracing ---------------------------------------------------------------------------------------------

    private void trace(String[] args) {
        String mode = need(args, 0, "trace on [file] | trace off").toLowerCase(Locale.ROOT);
        switch (mode) {
            case "on" -> {
                stopTrace();
                if (args.length > 1) {
                    Path file = Path.of(args[1]);
                    TextTraceWriter writer = TextTraceWriter.toFile(file);
                    traceFile = writer;
                    TraceListener withHeader = new TraceListener() {
                        boolean header;

                        @Override
                        public void onInstruction(TraceEvent event) {
                            if (!header) {
                                writer.writeLine(TraceFormatter.header(event.layout()));
                                header = true;
                            }
                            writer.onInstruction(event);
                        }
                    };
                    traceListener = withHeader;
                    out.println("tracing to " + file);
                } else {
                    traceListener = event -> out.println(TraceFormatter.format(event));
                    out.println("tracing to the console");
                }
                debugger.addTraceListener(traceListener);
            }
            case "off" -> {
                if (traceListener == null) {
                    out.println("tracing is off");
                } else {
                    stopTrace();
                    out.println("tracing stopped");
                }
            }
            default -> throw new IllegalArgumentException("trace on [file] | trace off");
        }
    }

    private void stopTrace() {
        if (traceListener != null) {
            debugger.removeTraceListener(traceListener);
            traceListener = null;
        }
        closeTraceFile();
    }

    private void closeTraceFile() {
        if (traceFile != null) {
            traceFile.close();
            traceFile = null;
        }
    }

    // ---- snapshots -------------------------------------------------------------------------------------------

    private void save(String file) {
        Snapshot s = machine.fullSnapshot().withLabel("cycle " + target.cycleCount());
        SnapshotJson.save(s, Path.of(file));
        int bytes = s.regions().stream().mapToInt(r -> r.length()).sum();
        out.println("saved " + s.fields().size() + " field(s) and " + bytes + " byte(s) of memory to " + file);
    }

    private void load(String file) {
        machine.restoreFull(SnapshotJson.load(Path.of(file)));
        out.println("restored " + file);
        showCurrent();
    }

    private void diff(String file) {
        Snapshot saved = SnapshotJson.load(Path.of(file));
        out.println(saved.diff(machine.fullSnapshot().withLabel("now")).report());
    }

    // ---- parsing ---------------------------------------------------------------------------------------------

    private static String need(String[] args, int index, String usage) {
        if (args.length <= index) {
            throw new IllegalArgumentException("usage: " + usage);
        }
        return args[index];
    }

    /** a hex address: {@code 0100}, {@code 0x100}, {@code 100H} */
    int address(String text) {
        String t = text.trim();
        if (t.startsWith("0x") || t.startsWith("0X")) {
            t = t.substring(2);
        } else if (t.endsWith("h") || t.endsWith("H")) {
            t = t.substring(0, t.length() - 1);
        } else if (t.startsWith("$")) {
            t = t.substring(1);
        }
        int value;
        try {
            value = Integer.parseUnsignedInt(t, 16);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a hex address: " + text);
        }
        if (value < 0 || value >= 1L << machine.addressBits()) {
            throw new IllegalArgumentException("address out of range: " + text);
        }
        return value;
    }

    /** a decimal count, or hex with {@code 0x}; underscores allowed */
    static long count(String text) {
        String t = text.replace("_", "");
        try {
            return t.startsWith("0x") || t.startsWith("0X") ? Long.parseLong(t.substring(2), 16) : Long.parseLong(t);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number: " + text);
        }
    }

    private String hex(int value) {
        return String.format("%0" + addressDigits + "X", value);
    }
}
