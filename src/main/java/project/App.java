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
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Entry point.
 * <ul>
 *   <li>no arguments: small gate-level demo, a clock driving an inverter;</li>
 *   <li>{@code run-com FILE [--trace TRACEFILE] [--golden GOLDENTRACE [--ignore-columns COL,...]] [--max-cycles N]}:
 *       runs a CP/M .COM program on the behavioral 8080, printing its console output, optionally writing a
 *       per-instruction trace or comparing every instruction against a recorded golden trace (exit code 1 on the
 *       first mismatch, with a report).</li>
 *   <li>{@code debug FILE.COM} or {@code debug --raw FILE [--org ADDR] [--pc ADDR]}: the interactive command-line
 *       debugger ({@link DebuggerRepl}) on a CP/M program or on a raw 8080 binary loaded at {@code ADDR} (hex,
 *       default 0000). Ctrl-C stops a running command; Ctrl-D or {@code quit} leaves.</li>
 *   <li>{@code vcd-demo FILE [--cycles N] [--timing settle|delta|sim-time]}: simulates a gate-level 4-bit counter
 *       and writes its clock, count and next-state logic to a VCD file for GTKWave or Surfer, then prints the
 *       simulator statistics.</li>
 * </ul>
 */
public class App {

	public String getGreeting() {
        return "Hello world.";
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("run-com")) {
            System.exit(runCom(args));
        }
        if (args.length > 0 && args[0].equals("debug")) {
            System.exit(debug(args));
        }
        if (args.length > 0 && args[0].equals("vcd-demo")) {
            System.exit(vcdDemo(args));
        }
    	Simulator sim = new Simulator();

    	//create clock source
    	IClock src = new Clock(sim, false);
    	IBus inputBus = new Bus(src);

    	//create inverter
    	Inverter inverter = new Inverter(sim, inputBus);

    	for (int i=0; i<10; i++) {
    		System.out.print(new SignalNameDecorator(src, "CLOCK"));
    		System.out.print(" ");
        	System.out.print(new SignalNameDecorator(inverter.getOutputBus().getSignals().get(0), "OUT"));
        	System.out.println();
        	//tick() toggles the clock and settles the circuit
        	src.tick();
    	}
    }

    private static int runCom(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: run-com FILE [--trace TRACEFILE] [--golden GOLDENTRACE [--ignore-columns COL,...]]"
                    + " [--max-cycles N]");
            return 2;
        }
        Path program = Path.of(args[1]);
        Path traceFile = null;
        Path goldenFile = null;
        String[] ignoreColumns = new String[0];
        long maxCycles = 50_000_000_000L;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--trace" -> traceFile = Path.of(args[++i]);
                case "--golden" -> goldenFile = Path.of(args[++i]);
                case "--ignore-columns" -> ignoreColumns = args[++i].split(",");
                case "--max-cycles" -> maxCycles = Long.parseLong(args[++i].replace("_", ""));
                default -> {
                    System.err.println("unknown option " + args[i]);
                    return 2;
                }
            }
        }
        CpmMachine machine = CpmMachine.load(program);
        machine.echoTo(System.out);
        TextTraceWriter trace = null;
        if (traceFile != null) {
            trace = TextTraceWriter.toFile(traceFile);
            trace.writeLine(TraceFormatter.header(Cpu8080.TRACE_LAYOUT));
            machine.debugger().addTraceListener(trace);
        }
        if (goldenFile != null) {
            GoldenTraceResult result = GoldenTrace.of(goldenFile).ignoreColumns(ignoreColumns)
                    .compare(machine.debugger(), maxCycles);
            System.out.println();
            System.out.println(result.report());
            if (trace != null) {
                trace.close();
            }
            return result.matched() ? 0 : 1;
        }
        long t0 = System.nanoTime();
        StopReason stop;
        try {
            stop = machine.run(maxCycles);
        } finally {
            if (trace != null) {
                trace.close();
            }
        }
        double seconds = (System.nanoTime() - t0) / 1e9;
        Cpu8080 cpu = machine.cpu();
        System.out.printf("%n[%s: %,d instructions, %,d cycles, %.2f s, %.0f MHz emulated]%n", stop,
                cpu.instructionCount(), cpu.cycleCount(), seconds, cpu.cycleCount() / seconds / 1e6);
        if (!machine.exited()) {
            System.err.println(machine.failureReport());
            return 1;
        }
        return 0;
    }

    private static int vcdDemo(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: vcd-demo FILE [--cycles N] [--timing settle|delta|sim-time]");
            return 2;
        }
        Path file = Path.of(args[1]);
        int cycles = 20;
        VcdWriter.Timing timing = VcdWriter.Timing.SETTLE;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--cycles" -> cycles = Integer.parseInt(args[++i]);
                case "--timing" -> timing = VcdWriter.Timing.valueOf(args[++i].toUpperCase().replace('-', '_'));
                default -> {
                    System.err.println("unknown option " + args[i]);
                    return 2;
                }
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
        System.out.println("wrote " + file + " (" + cycles + " clock cycles, timing " + timing + ")");
        System.out.println(sim.stats().report());
        System.out.println("open it with: gtkwave " + file + "   or   surfer " + file);
        return 0;
    }

    private static int debug(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: debug FILE.COM | debug --raw FILE [--org ADDR] [--pc ADDR]   (ADDR in hex)");
            return 2;
        }
        ReplTarget target;
        if (args[1].equals("--raw")) {
            if (args.length < 3) {
                System.err.println("usage: debug --raw FILE [--org ADDR] [--pc ADDR]");
                return 2;
            }
            Path file = Path.of(args[2]);
            int org = 0;
            Integer pc = null;
            for (int i = 3; i < args.length; i++) {
                switch (args[i]) {
                    case "--org" -> org = parseHex(args[++i]);
                    case "--pc" -> pc = parseHex(args[++i]);
                    default -> {
                        System.err.println("unknown option " + args[i]);
                        return 2;
                    }
                }
            }
            Ram ram = new Ram();
            try {
                ram.load(org, Files.readAllBytes(file));
            } catch (IOException e) {
                System.err.println("cannot read " + file + ": " + e.getMessage());
                return 1;
            }
            Cpu8080 cpu = new Cpu8080(ram, IoBus.UNCONNECTED);
            cpu.setPC(pc != null ? pc : org);
            target = new ReplTarget8080(cpu, ram, Debug8080.attach(cpu), Map.of());
        } else {
            CpmMachine machine = CpmMachine.load(Path.of(args[1]));
            machine.echoTo(System.out);
            target = machine.replTarget();
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        DebuggerRepl repl = new DebuggerRepl(target, in, System.out);
        boolean handled = onInterrupt(() -> {
            if (repl.isRunning()) {
                repl.interrupt();
            } else {
                System.out.print("\n(Ctrl-C stops a running command; type 'quit' or press Ctrl-D to leave)\n"
                        + DebuggerRepl.PROMPT);
                System.out.flush();
            }
        });
        if (!handled) {
            System.out.println("(Ctrl-C handling unavailable: Ctrl-C will end the session)");
        }
        repl.run();
        return 0;
    }

    private static int parseHex(String text) {
        String t = text.startsWith("0x") || text.startsWith("0X") ? text.substring(2)
                : text.endsWith("h") || text.endsWith("H") ? text.substring(0, text.length() - 1) : text;
        return Integer.parseInt(t, 16);
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
