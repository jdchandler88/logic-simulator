package project;

import com.darchan.behavioral.cpm.CpmMachine;
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
import com.darchan.physical.sim.Simulator;

import java.nio.file.Path;

/**
 * Entry point.
 * <ul>
 *   <li>no arguments: small gate-level demo, a clock driving an inverter;</li>
 *   <li>{@code run-com FILE [--trace TRACEFILE] [--max-cycles N]}: runs a CP/M .COM program on the behavioral
 *       8080, printing its console output, optionally writing a per-instruction trace.</li>
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
            System.err.println("usage: run-com FILE [--trace TRACEFILE] [--max-cycles N]");
            return 2;
        }
        Path program = Path.of(args[1]);
        Path traceFile = null;
        long maxCycles = 50_000_000_000L;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--trace" -> traceFile = Path.of(args[++i]);
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

}
