package com.darchan.behavioral.cpm;

import com.darchan.diag.run.StopReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CpmMachineTest {

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            b[i] = (byte) values[i];
        }
        return b;
    }

    @Test
    void printsWithBdosFunctions2And9AndExitsViaJumpToZero() {
        CpmMachine m = new CpmMachine(bytes(
                0x11, 0x12, 0x01,       // 0100 LXI D,0112
                0x0E, 0x09,             // 0103 MVI C,9
                0xCD, 0x05, 0x00,       // 0105 CALL 5
                0x1E, 0x21,             // 0108 MVI E,'!'
                0x0E, 0x02,             // 010A MVI C,2
                0xCD, 0x05, 0x00,       // 010C CALL 5
                0xC3, 0x00, 0x00,       // 010F JMP 0
                'H', 'i', '$'));        // 0112
        StopReason stop = m.run(10_000);
        assertEquals(StopReason.Kind.BREAKPOINT, stop.kind(), m::failureReport);
        assertTrue(m.exited());
        assertEquals("Hi!", m.output());
    }

    @Test
    void bdosFunction0Exits() {
        CpmMachine m = new CpmMachine(bytes(0x0E, 0x00, 0xCD, 0x05, 0x00, 0x76));
        m.run(10_000);
        assertTrue(m.exited());
        assertEquals(10 + 7 + 17, m.cpu().cycleCount(), "MVI + CALL + OUT; the RET is never reached");
    }

    @Test
    void memoryLayoutMatchesTheReferenceHarnesses() {
        CpmMachine m = new CpmMachine(new byte[0]);
        assertEquals(0x76, m.ram().peek(0x0000));
        assertEquals(0xD3, m.ram().peek(0x0005));
        assertEquals(0x01, m.ram().peek(0x0006));
        assertEquals(0xC9, m.ram().peek(0x0007));
        assertEquals(0x0100, m.cpu().pc());
    }

    @Test
    void aProgramThatNeverExitsProducesADiagnosticReport() {
        CpmMachine m = new CpmMachine(bytes(0x3C, 0xC3, 0x00, 0x01)); // INR A; JMP 0100
        StopReason stop = m.run(1_000);
        assertEquals(StopReason.Kind.CYCLE_LIMIT, stop.kind());
        assertFalse(m.exited());
        String report = m.failureReport();
        assertTrue(report.contains("traced instruction(s) (i8080)"), report);
        assertTrue(report.contains("JMP 0100H"), report);
        assertTrue(report.contains("registers: PC="), report);
        assertTrue(report.endsWith("--- CP/M console output ---\n"), report);
    }
}
