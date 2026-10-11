package com.darchan.behavioral.cpm;

import com.darchan.behavioral.bus.IoBus;
import com.darchan.behavioral.bus.Ram;
import com.darchan.behavioral.i8080.Cpu8080;
import com.darchan.behavioral.i8080.Debug8080;
import com.darchan.behavioral.i8080.ReplTarget8080;
import com.darchan.diag.MemoryRegion;
import com.darchan.diag.Snapshot;
import com.darchan.diag.compare.StateSaver;
import com.darchan.diag.run.Debugger;
import com.darchan.diag.run.StopReason;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Just enough CP/M to run {@code .COM} programs such as the classic 8080 CPU diagnostics: 64 KiB RAM, the program
 * loaded at {@link #TPA 0100h}, and a stub BDOS.
 *
 * <p>Memory set-up (the same bytes the widely used reference harnesses use, so cycle counts are comparable):
 * <pre>
 * 0000: 76         HLT       warm boot: reaching 0000 ends the program (the run stops before executing it)
 * 0005: D3 01      OUT 01h   BDOS entry: the OUT is trapped and BDOS is emulated in Java
 * 0007: C9         RET
 * </pre>
 * Programs read the top of the TPA from 0006/0007 (here C901h). Supported BDOS functions (in C): 0 (system reset:
 * exit), 2 (console output of E) and 9 (print the {@code '$'}-terminated string at DE). Others are ignored and
 * logged. {@code IN} from any port returns 00.
 *
 * <p>A {@link Debugger} is always attached (with watchpoint decorators and a trace ring buffer) and has a
 * breakpoint on 0000; {@link #failureReport()} shows recent trace, registers and the console output.
 */
public final class CpmMachine {

    public static final int TPA = 0x0100;
    public static final int BDOS_ENTRY = 0x0005;
    public static final int BDOS_TRAP_PORT = 0x01;

    private static final System.Logger LOG = System.getLogger(CpmMachine.class.getName());

    private final Ram ram = new Ram();
    private final Cpu8080 cpu;
    private final Debugger debugger;
    private final StringBuilder output = new StringBuilder();
    private PrintStream echo;

    public CpmMachine(byte[] program) {
        if (program.length > 0xC900 - TPA) {
            throw new IllegalArgumentException("program too large for the TPA: " + program.length + " bytes");
        }
        ram.load(TPA, program);
        ram.write(0x0000, 0x76);
        ram.write(BDOS_ENTRY, 0xD3);
        ram.write(BDOS_ENTRY + 1, BDOS_TRAP_PORT);
        ram.write(BDOS_ENTRY + 2, 0xC9);
        cpu = new Cpu8080(ram, new IoBus() {
            @Override
            public int in(int port) {
                return 0x00;
            }

            @Override
            public void out(int port, int value) {
                // only the OUT in the BDOS stub is a BDOS call; after fetching it the PC is BDOS_ENTRY + 2
                if (port == BDOS_TRAP_PORT && cpu.pc() == BDOS_ENTRY + 2) {
                    bdos();
                }
            }
        });
        cpu.setPC(TPA);
        debugger = Debug8080.attach(cpu);
        debugger.addBreakpoint(0x0000);
    }

    public static CpmMachine load(Path comFile) {
        try {
            return new CpmMachine(Files.readAllBytes(comFile));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + comFile, e);
        }
    }

    /** Also print console output to {@code stream} as it is produced ({@code null} to stop). */
    public void echoTo(PrintStream stream) {
        this.echo = stream;
    }

    /**
     * Runs until the program exits (jumps to 0000), another debugger stop (breakpoint, watchpoint, halt), or
     * {@code maxCycles} more cycles have passed.
     */
    public StopReason run(long maxCycles) {
        return debugger.run(maxCycles);
    }

    /** true when the CPU is at 0000, i.e. the program has returned to CP/M */
    public boolean exited() {
        return cpu.pc() == 0x0000;
    }

    public String output() {
        return output.toString();
    }

    public Cpu8080 cpu() {
        return cpu;
    }

    public Ram ram() {
        return ram;
    }

    public Debugger debugger() {
        return debugger;
    }

    /** A REPL target for this machine (address 0000 is labelled as the program exit). */
    public ReplTarget8080 replTarget() {
        return new ReplTarget8080(cpu, ram, debugger, Map.of(0x0000, "program exit (CP/M warm boot)",
                BDOS_ENTRY, "BDOS call"));
    }

    /** name of the console-output length field in {@link #stateSaver()} snapshots */
    public static final String CONSOLE_CHARS = "CONSOLE_CHARS";

    /**
     * Saves and restores everything this machine's behaviour depends on: the CPU's registers, flags and counters,
     * all 64 KiB of RAM (region {@code ram}), and the length of the console output (field {@link #CONSOLE_CHARS};
     * restoring truncates the output to it, so re-executed BDOS calls do not print twice, though text already
     * echoed to a stream cannot be taken back). Use it for lockstep narrowing by restore
     * ({@code Lockstep.Builder.restoreWith}); the CPU's own snapshot leaves memory out.
     */
    public StateSaver stateSaver() {
        return new StateSaver() {
            @Override
            public Snapshot save() {
                Snapshot cpuState = cpu.snapshot();
                return Snapshot.builder(cpuState.machine()).label(cpuState.label()).fields(cpuState.fields())
                        .dec(CONSOLE_CHARS, output.length())
                        .region("ram", ram::peek, 0, Ram.SIZE)
                        .build();
            }

            @Override
            public void restore(Snapshot saved) {
                MemoryRegion memory = saved.region("ram").orElseThrow(
                        () -> new IllegalArgumentException("not a CpmMachine state: no ram region"));
                long chars = saved.value(CONSOLE_CHARS);
                if (chars < 0 || chars > output.length()) {
                    throw new IllegalArgumentException(CONSOLE_CHARS + " = " + chars + " but only " + output.length()
                            + " character(s) were printed");
                }
                cpu.restore(saved);
                ram.restore(memory);
                output.setLength((int) chars);
            }
        };
    }

    /** Recent trace, registers and everything printed so far. */
    public String failureReport() {
        return debugger.failureReport() + "\n--- CP/M console output ---\n" + output;
    }

    private void bdos() {
        switch (cpu.c()) {
            case 0 -> cpu.setPC(0x0000);
            case 2 -> print((char) cpu.e());
            case 9 -> {
                int addr = cpu.de();
                for (int n = 0; n < Ram.SIZE; n++) {
                    int ch = ram.peek((addr + n) & 0xFFFF);
                    if (ch == '$') {
                        break;
                    }
                    print((char) ch);
                }
            }
            default -> LOG.log(Level.DEBUG, () -> "ignoring BDOS function " + cpu.c());
        }
    }

    private void print(char ch) {
        output.append(ch);
        if (echo != null) {
            echo.print(ch);
        }
    }
}
