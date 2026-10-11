package com.darchan.diag.run;

import com.darchan.diag.Snapshot;
import com.darchan.diag.trace.RegisterSpec;
import com.darchan.diag.trace.TraceEvent;
import com.darchan.diag.trace.TraceLayout;
import com.darchan.diag.trace.TraceListener;

import java.util.List;

/**
 * A minimal accumulator machine used to test the diagnostics layer without depending on a real core.
 * <pre>
 * 00       NOP        4 cycles
 * 01 nn    LDA nn     7 cycles  (data read)
 * 02 nn    STA nn     7 cycles  (data write)
 * 03       INC        4 cycles  (A += 1, Z flag)
 * 04       HLT        4 cycles  (then idles 4 cycles per step)
 * 05 nn    OUT nn     7 cycles
 * 06 nn    JMP nn     7 cycles
 * FF       crash      throws IllegalStateException
 * </pre>
 */
final class ToyCpu implements DebugTarget {

    static final TraceLayout LAYOUT = new TraceLayout("toy", 8, 2, 8,
            List.of(RegisterSpec.of("A", 8), RegisterSpec.flags("F", "Z.......")));

    final int[] mem = new int[256];
    BusAccessListener bus = (t, a, v) -> { };
    int a, pc, f;
    long cycles, instructions;
    boolean halted;
    private TraceListener trace;
    private final TraceEvent event = new TraceEvent(LAYOUT, ToyCpu::disassemble);

    static String disassemble(int address, int[] bytes, int length) {
        return switch (bytes[0]) {
            case 0x00 -> "NOP";
            case 0x01 -> "LDA " + hex(bytes[1]);
            case 0x02 -> "STA " + hex(bytes[1]);
            case 0x03 -> "INC";
            case 0x04 -> "HLT";
            case 0x05 -> "OUT " + hex(bytes[1]);
            case 0x06 -> "JMP " + hex(bytes[1]);
            default -> "???";
        };
    }

    static int length(int opcode) {
        return opcode == 1 || opcode == 2 || opcode == 5 || opcode == 6 ? 2 : 1;
    }

    private static String hex(int v) {
        return String.format("%02X", v);
    }

    private int fetch() {
        int v = mem[pc];
        bus.onAccess(AccessType.FETCH, pc, v);
        pc = (pc + 1) & 0xFF;
        return v;
    }

    @Override
    public int step() {
        if (halted) {
            cycles += 4;
            return 4;
        }
        if (trace != null) {
            event.begin(cycles, instructions, pc, false);
            int op = mem[pc];
            event.addByte(op);
            if (length(op) == 2) {
                event.addByte(mem[(pc + 1) & 0xFF]);
            }
            event.setRegister(0, a);
            event.setRegister(1, f);
            trace.onInstruction(event);
        }
        int op = fetch();
        int n;
        switch (op) {
            case 0x00 -> n = 4;
            case 0x01 -> {
                int addr = fetch();
                a = mem[addr];
                bus.onAccess(AccessType.READ, addr, a);
                n = 7;
            }
            case 0x02 -> {
                int addr = fetch();
                mem[addr] = a;
                bus.onAccess(AccessType.WRITE, addr, a);
                n = 7;
            }
            case 0x03 -> {
                a = (a + 1) & 0xFF;
                f = a == 0 ? 0x80 : 0;
                n = 4;
            }
            case 0x04 -> {
                halted = true;
                n = 4;
            }
            case 0x05 -> {
                bus.onAccess(AccessType.IO_OUT, fetch(), a);
                n = 7;
            }
            case 0x06 -> {
                pc = fetch();
                n = 7;
            }
            default -> throw new IllegalStateException("crash opcode " + hex(op));
        }
        cycles += n;
        instructions++;
        return n;
    }

    @Override
    public long cycleCount() {
        return cycles;
    }

    @Override
    public long instructionCount() {
        return instructions;
    }

    @Override
    public int programCounter() {
        return pc;
    }

    @Override
    public boolean isHalted() {
        return halted;
    }

    @Override
    public void setTraceListener(TraceListener listener) {
        trace = listener;
    }

    @Override
    public Snapshot snapshot() {
        return Snapshot.builder("toy").hex("PC", 8, pc).hex("A", 8, a).hex("F", 8, f).bool("HALT", halted)
                .dec("CYCLES", cycles).dec("INSTRUCTIONS", instructions).build();
    }

    @Override
    public void restore(Snapshot s) {
        pc = (int) s.value("PC");
        a = (int) s.value("A");
        f = (int) s.value("F");
        halted = s.value("HALT") != 0;
        cycles = s.value("CYCLES");
        instructions = s.value("INSTRUCTIONS");
    }

    void load(int... program) {
        for (int i = 0; i < program.length; i++) {
            mem[i] = program[i];
        }
    }
}
