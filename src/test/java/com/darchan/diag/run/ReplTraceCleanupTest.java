package com.darchan.diag.run;

import com.darchan.diag.MemoryView;
import com.darchan.diag.Snapshot;
import com.darchan.diag.repl.DebuggerRepl;
import com.darchan.diag.repl.ReplTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ReplTraceCleanupTest {

    @TempDir
    Path dir;

    /**
     * Regression: ending a session with a trace open (quit or end of input) closed the trace file but left its
     * listener on the debugger, so the next instruction run on that debugger threw "cannot write trace".
     */
    @Test
    void endingASessionWithTracingOnLeavesTheDebuggerUsable() {
        for (String ending : new String[]{"quit\n", ""}) {
            ToyCpu fresh = new ToyCpu();
            Debugger d = new Debugger(fresh, null);
            fresh.load(0x00, 0x00, 0x00, 0x00);
            ReplTarget target = new ReplTarget() {
                @Override public String name() { return "toy"; }
                @Override public Debugger debugger() { return d; }
                @Override public MemoryView memory() { return a -> 0; }
                @Override public int addressBits() { return 8; }
                @Override public int maxInstructionLength() { return 1; }
                @Override public int instructionLength(int address) { return 1; }
                @Override public String disassemble(int address) { return "NOP"; }
                @Override public Snapshot fullSnapshot() { return fresh.snapshot(); }
                @Override public void restoreFull(Snapshot s) { fresh.restore(s); }
            };
            for (String trace : new String[]{"trace on\n", "trace on " + dir.resolve("x.trace") + "\n"}) {
                new DebuggerRepl(target, new StringReader(trace + "step\n" + ending),
                        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)).run();
                long before = fresh.instructions;
                assertDoesNotThrow(() -> d.step(), "after a session ending with '" + ending.strip() + "' and " + trace.strip());
                assertEquals(before + 1, fresh.instructions);
            }
        }
    }
}
