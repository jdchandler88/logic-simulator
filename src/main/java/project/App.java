package project;

import com.darchan.behavioral.bus.IoBus;
import com.darchan.behavioral.bus.Ram;
import com.darchan.behavioral.cpm.CpmMachine;
import com.darchan.behavioral.i8080.Debug8080;
import com.darchan.behavioral.i8080.ReplTarget8080;
import com.darchan.diag.repl.DebuggerRepl;
import com.darchan.diag.repl.ReplTarget;
import com.darchan.diag.compare.GoldenTrace;
import com.darchan.diag.compare.GoldenTraceResult;
import com.darchan.diag.run.ExecutionFailure;
import com.darchan.diag.run.StopReason;
import com.darchan.diag.trace.TextTraceWriter;
import com.darchan.diag.trace.TraceFormatter;
import com.darchan.behavioral.i8080.Cpu8080;
import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.IClock;
import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.Clock;
import com.darchan.physical.signal.SignalNameDecorator;
import com.darchan.physical.comp.Inverter;
import com.darchan.physical.comp.Counter;
import com.darchan.physical.probe.ProbeSet;
import com.darchan.physical.sim.Simulator;
import com.darchan.physical.wave.VcdWriter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Entry point.
 * <ul>
 *   <li>no arguments: small gate-level demo, a clock driving an inverter;</li>
 *   <li>{@code run-com FILE [--trace TRACEFILE] [--golden GOLDENTRACE [--ignore-columns COL,...]] [--max-cycles N]}:
 *       runs a CP/M .COM program on the behavioral 8080, printing its console output, optionally writing a
 *       per-instruction trace or comparing every instruction against a recorded golden trace.</li>
 *   <li>{@code debug FILE.COM} or {@code debug --raw FILE [--org ADDR] [--pc ADDR]}: the interactive command-line
 *       debugger ({@link DebuggerRepl}) on a CP/M program or on a raw 8080 binary loaded at {@code ADDR} (hex,
 *       default 0000). Ctrl-C stops a running command; Ctrl-D or {@code quit} leaves.</li>
 *   <li>{@code vcd-demo FILE [--cycles N] [--timing settle|delta|sim-time]}: simulates a gate-level 4-bit counter
 *       and writes its clock, count and next-state logic to a VCD file for GTKWave or Surfer, then prints the
 *       simulator statistics.</li>
 * </ul>
 *
 * <p>Exit codes: {@value #OK} success; {@value #FAILED} failure; {@value #USAGE} bad arguments. For {@code run-com}
 * without {@code --golden}, success means the program exited to CP/M (jumped to 0000) within the cycle limit. With
 * {@code --golden}, success means no golden line differed and either every golden line was reached, or the run
 * stopped only because a {@code --max-cycles} given on the command line ran out (a deliberately shortened check;
 * the output says how many lines were verified; running out of the default limit is a failure). A mismatch, or a run that stops early for any other reason (the program exited, halted or hit a
 * breakpoint before the golden trace ended), is a failure; such a stop is also compared against the next golden
 * line, so the report shows what differs.
 */
public class App {

    public static final int OK = 0;
    public static final int FAILED = 1;
    public static final int USAGE = 2;

    private static final String RUN_COM_USAGE = "usage: run-com FILE [--trace TRACEFILE] [--golden GOLDENTRACE"
            + " [--ignore-columns COL,...]] [--max-cycles N]";
    private static final String DEBUG_USAGE = "usage: debug FILE.COM | debug --raw FILE [--org ADDR] [--pc ADDR]"
            + "   (ADDR in hex)";
    private static final String VCD_USAGE = "usage: vcd-demo FILE [--cycles N] [--timing settle|delta|sim-time]";

	public String getGreeting() {
        return "Hello world.";
    }

    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (args.length > 0) {
            System.exit(code);
        }
    }

    /**
     * Runs one command line, writing to {@code out} and {@code err} instead of the console streams (the
     * interactive {@code debug} command still reads {@code System.in}).
     *
     * @return the process exit code
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            demo(out);
            return OK;
        }
        try {
            return switch (args[0]) {
                case "run-com" -> runCom(args, out, err);
                case "debug" -> debug(args, out, err);
                case "vcd-demo" -> vcdDemo(args, out, err);
                default -> {
                    err.println("unknown command " + args[0] + " (expected run-com, debug or vcd-demo)");
                    yield USAGE;
                }
            };
        } catch (UsageException e) {
            err.println(e.getMessage());
            return USAGE;
        } catch (ExecutionFailure e) {
            err.println(e.getMessage());
            err.println(e.report());
            return FAILED;
        } catch (UncheckedIOException e) {
            err.println("error: " + e.getMessage() + (e.getCause() == null ? "" : " (" + e.getCause() + ")"));
            return FAILED;
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return FAILED;
        }
    }

    /** a bad command line; reported with exit code {@link #USAGE} */
    private static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    /** the value after option {@code args[i]} */
    private static String value(String[] args, int i, String usage) {
        if (i + 1 >= args.length) {
            throw new UsageException("option " + args[i] + " needs a value\n" + usage);
        }
        return args[i + 1];
    }

    private static long number(String text, String option, String usage) {
        try {
            long n = Long.parseLong(text.replace("_", ""));
            if (n < 0) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new UsageException(option + " needs a non-negative number: " + text + "\n" + usage);
        }
    }

    /** small gate-level demo: a clock driving an inverter */
    private static void demo(PrintStream out) {
    	Simulator sim = new Simulator();

    	//create clock source
    	IClock src = new Clock(sim, false);
    	IBus inputBus = new Bus(src);

    	//create inverter
    	Inverter inverter = new Inverter(sim, inputBus);

    	for (int i=0; i<10; i++) {
    		out.print(new SignalNameDecorator(src, "CLOCK"));
    		out.print(" ");
        	out.print(new SignalNameDecorator(inverter.getOutputBus().getSignals().get(0), "OUT"));
        	out.println();
        	//tick() toggles the clock and settles the circuit
        	src.tick();
    	}
    }

    private static int runCom(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 2) {
            err.println(RUN_COM_USAGE);
            return USAGE;
        }
        Path program = Path.of(args[1]);
        Path traceFile = null;
        Path goldenFile = null;
        String[] ignoreColumns = new String[0];
        long maxCycles = 50_000_000_000L;
        boolean maxCyclesGiven = false;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--trace" -> traceFile = Path.of(value(args, i++, RUN_COM_USAGE));
                case "--golden" -> goldenFile = Path.of(value(args, i++, RUN_COM_USAGE));
                case "--ignore-columns" -> ignoreColumns = value(args, i++, RUN_COM_USAGE).split(",");
                case "--max-cycles" -> {
                    maxCycles = number(value(args, i++, RUN_COM_USAGE), "--max-cycles", RUN_COM_USAGE);
                    maxCyclesGiven = true;
                }
                default -> throw new UsageException("unknown option " + args[i] + "\n" + RUN_COM_USAGE);
            }
        }
        if (ignoreColumns.length > 0 && goldenFile == null) {
            throw new UsageException("--ignore-columns needs --golden\n" + RUN_COM_USAGE);
        }
        CpmMachine machine = CpmMachine.load(program);
        machine.echoTo(out);
        TextTraceWriter trace = null;
        try {
            if (traceFile != null) {
                trace = TextTraceWriter.toFile(traceFile);
                trace.writeLine(TraceFormatter.header(Cpu8080.TRACE_LAYOUT));
                machine.debugger().addTraceListener(trace);
            }
            if (goldenFile != null) {
                GoldenTraceResult result = GoldenTrace.of(goldenFile).ignoreColumns(ignoreColumns)
                        .compare(machine.debugger(), maxCycles);
                out.println();
                out.println(result.report());
                if (result.verified()) {
                    return OK;
                }
                if (result.matched() && maxCyclesGiven && result.stop().kind() == StopReason.Kind.CYCLE_LIMIT) {
                    out.println("(--max-cycles " + maxCycles + " ended the run: only the first "
                            + result.linesCompared() + " golden line(s) were checked)");
                    return OK;
                }
                if (result.matched()) {
                    err.println("golden trace not completed: the live run stopped (" + result.stop()
                            + ") before the golden trace ended");
                }
                return FAILED;
            }
            long t0 = System.nanoTime();
            StopReason stop = machine.run(maxCycles);
            double seconds = (System.nanoTime() - t0) / 1e9;
            Cpu8080 cpu = machine.cpu();
            out.printf("%n[%s: %,d instructions, %,d cycles, %.2f s, %.0f MHz emulated]%n", stop,
                    cpu.instructionCount(), cpu.cycleCount(), seconds, cpu.cycleCount() / seconds / 1e6);
            if (!machine.exited()) {
                err.println(machine.failureReport());
                return FAILED;
            }
            return OK;
        } finally {
            if (trace != null) {
                trace.close();
            }
        }
    }

    private static int vcdDemo(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 2) {
            err.println(VCD_USAGE);
            return USAGE;
        }
        Path file = Path.of(args[1]);
        int cycles = 20;
        VcdWriter.Timing timing = VcdWriter.Timing.SETTLE;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--cycles" -> cycles = (int) Math.min(Integer.MAX_VALUE,
                        number(value(args, i++, VCD_USAGE), "--cycles", VCD_USAGE));
                case "--timing" -> timing = timing(value(args, i++, VCD_USAGE));
                default -> throw new UsageException("unknown option " + args[i] + "\n" + VCD_USAGE);
            }
        }
        Simulator sim = new Simulator();
        Clock clk = new Clock(sim, false);
        Counter counter = new Counter(sim, clk, 4);
        sim.settle();
        ProbeSet probes = new ProbeSet(sim);
        probes.add("clk", clk);
        probes.add("counter.q", counter.getOutputBus());
        probes.add("counter.next", counter.getNextState());
        for (int bit = 0; bit < 4; bit++) {
            probes.add("counter.bits.q" + bit, counter.getOutputBus().getSignal(bit));
        }
        sim.resetStats();
        try (VcdWriter vcd = VcdWriter.builder(probes).timing(timing).topScope("counter4").open(file)) {
            for (int i = 0; i < 2 * cycles; i++) {
                clk.tick();
            }
        }
        out.println("wrote " + file + " (" + cycles + " clock cycles, timing " + timing + ")");
        out.println(sim.stats().report());
        out.println("open it with: gtkwave " + file + "   or   surfer " + file);
        return OK;
    }

    private static int debug(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 2) {
            err.println(DEBUG_USAGE);
            return USAGE;
        }
        ReplTarget target;
        if (args[1].equals("--raw")) {
            if (args.length < 3) {
                err.println(DEBUG_USAGE);
                return USAGE;
            }
            Path file = Path.of(args[2]);
            int org = 0;
            Integer pc = null;
            for (int i = 3; i < args.length; i++) {
                switch (args[i]) {
                    case "--org" -> org = parseHex(value(args, i++, DEBUG_USAGE));
                    case "--pc" -> pc = parseHex(value(args, i++, DEBUG_USAGE));
                    default -> throw new UsageException("unknown option " + args[i] + "\n" + DEBUG_USAGE);
                }
            }
            Ram ram = new Ram();
            try {
                ram.load(org, Files.readAllBytes(file));
            } catch (IOException e) {
                err.println("cannot read " + file + ": " + e.getMessage());
                return FAILED;
            }
            Cpu8080 cpu = new Cpu8080(ram, IoBus.UNCONNECTED);
            cpu.setPC(pc != null ? pc : org);
            target = new ReplTarget8080(cpu, ram, Debug8080.attach(cpu), Map.of());
        } else {
            CpmMachine machine = CpmMachine.load(Path.of(args[1]));
            machine.echoTo(out);
            target = machine.replTarget();
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        DebuggerRepl repl = new DebuggerRepl(target, in, out);
        boolean handled = onInterrupt(() -> {
            if (repl.isRunning()) {
                repl.interrupt();
            } else {
                out.print("\n(Ctrl-C stops a running command; type 'quit' or press Ctrl-D to leave)\n"
                        + DebuggerRepl.PROMPT);
                out.flush();
            }
        });
        if (!handled) {
            out.println("(Ctrl-C handling unavailable: Ctrl-C will end the session)");
        }
        repl.run();
        return OK;
    }

    private static int parseHex(String text) {
        String t = text.startsWith("0x") || text.startsWith("0X") ? text.substring(2)
                : text.endsWith("h") || text.endsWith("H") ? text.substring(0, text.length() - 1) : text;
        if (!t.matches("[0-9A-Fa-f]{1,4}")) {
            throw new UsageException("not a 16-bit hex address: " + text + "\n" + DEBUG_USAGE);
        }
        return Integer.parseInt(t, 16);
    }

    private static VcdWriter.Timing timing(String text) {
        try {
            return VcdWriter.Timing.valueOf(text.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new UsageException("unknown timing " + text + "\n" + VCD_USAGE);
        }
    }

    /**
     * Installs a SIGINT (Ctrl-C) handler through {@code sun.misc.Signal}, looked up reflectively because it is not a
     * standard API. Returns false if that is not possible on this JVM.
     */
    static boolean onInterrupt(Runnable action) {
        try {
            Class<?> signalClass = Class.forName("sun.misc.Signal");
            Class<?> handlerClass = Class.forName("sun.misc.SignalHandler");
            Object signal = signalClass.getConstructor(String.class).newInstance("INT");
            Object handler = Proxy.newProxyInstance(handlerClass.getClassLoader(), new Class<?>[]{handlerClass},
                    (proxy, method, methodArgs) -> switch (method.getName()) {
                        case "handle" -> {
                            action.run();
                            yield null;
                        }
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == methodArgs[0];
                        default -> "Ctrl-C handler";
                    });
            signalClass.getMethod("handle", signalClass, handlerClass).invoke(null, signal, handler);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }
}
