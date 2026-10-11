package com.darchan;

import com.darchan.behavioral.bus.MemoryBus;
import com.darchan.behavioral.cpm.CpmMachine;
import com.darchan.behavioral.i8080.Cpu8080;
import com.darchan.behavioral.i8080.ReplTarget8080;
import com.darchan.behavioral.bus.Ram;
import com.darchan.cosim.AluDatapathCosim;
import com.darchan.diag.Snapshot;
import com.darchan.diag.compare.Divergence;
import com.darchan.diag.run.Debugger;
import com.darchan.diag.trace.InMemoryTrace;
import com.darchan.diag.trace.RegisterSpec;
import com.darchan.physical.comp.Counter;
import com.darchan.physical.comp.Register;
import com.darchan.physical.comp.TriStateBuffer;
import com.darchan.physical.comp.i8080.AluDatapath;
import com.darchan.physical.comp.i8080.RegisterFile;
import com.darchan.physical.logic.Gates;
import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.Clock;
import com.darchan.physical.signal.ConstantSignal;
import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.SimpleSignal;
import com.darchan.physical.sim.Simulator;
import com.darchan.physical.sim.StateElement;
import com.darchan.testutil.Buses;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Odds and ends: size limits, accessors, defaults and report wording that no behavioural test pins down. */
class MiscEdgeCasesTest {

    // ---- CP/M machine -----------------------------------------------------------------------------------------------

    @Test
    void theProgramMayFillTheTpaButNotMore() {
        new CpmMachine(new byte[0xC900 - CpmMachine.TPA]);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CpmMachine(new byte[0xC900 - CpmMachine.TPA + 1]));
        assertTrue(e.getMessage().contains("too large"), e.getMessage());
    }

    private static void callBdos(CpmMachine m, int function, int de) {
        m.cpu().setC(function);
        m.cpu().setDE(de);
        m.cpu().setSP(0xF000);
        m.cpu().setPC(CpmMachine.BDOS_ENTRY);
        m.cpu().step(); // the OUT of the BDOS stub
    }

    @Test
    void anUnterminatedStringStopsAfterTheWholeAddressSpace() {
        byte[] program = new byte[0xC800];
        java.util.Arrays.fill(program, (byte) 'A');
        CpmMachine m = new CpmMachine(program);
        callBdos(m, 9, 0x0100);
        // no '$' anywhere from 0100 on: 64 KiB are printed (wrapping around), then the call gives up
        assertEquals(Ram.SIZE, m.output().length());
    }

    @Test
    void unknownBdosFunctionsPrintNothingAndSingleCharactersPrint() {
        CpmMachine m = new CpmMachine(new byte[0]);
        callBdos(m, 7, 0);
        assertEquals("", m.output());
        callBdos(m, 2, 'x');
        callBdos(m, 2, 'y');
        assertEquals("xy", m.output());
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        m.echoTo(new java.io.PrintStream(bytes, true));
        callBdos(m, 2, 'z');
        assertEquals("z", bytes.toString());
        m.echoTo(null);
        callBdos(m, 2, 'q');
        assertEquals("z", bytes.toString());
        assertEquals("xyzq", m.output());
    }

    @Test
    void theStateSaverChecksTheConsoleLengthAndTheRamRegion() {
        CpmMachine m = new CpmMachine(new byte[0]);
        callBdos(m, 2, 'a');
        callBdos(m, 2, 'b');
        var saver = m.stateSaver();
        Snapshot saved = saver.save();
        assertEquals(2, saved.value(CpmMachine.CONSOLE_CHARS));
        callBdos(m, 2, 'c');
        saver.restore(saved);
        assertEquals("ab", m.output(), "output beyond the saved length is cut");
        saver.restore(saved); // exactly the current length is fine
        assertEquals("ab", m.output());
        Snapshot tooMany = Snapshot.builder(saved.machine()).fields(saved.fields().stream()
                .map(f -> f.name().equals(CpmMachine.CONSOLE_CHARS) ? com.darchan.diag.Field.dec(f.name(), 3) : f).toList())
                .region(saved.region("ram").orElseThrow()).build();
        assertThrows(IllegalArgumentException.class, () -> saver.restore(tooMany));
        Snapshot negative = Snapshot.builder(saved.machine()).fields(saved.fields().stream()
                .map(f -> f.name().equals(CpmMachine.CONSOLE_CHARS) ? com.darchan.diag.Field.dec(f.name(), -1) : f).toList())
                .region(saved.region("ram").orElseThrow()).build();
        assertThrows(IllegalArgumentException.class, () -> saver.restore(negative));
        Snapshot noRam = Snapshot.builder(saved.machine()).fields(saved.fields()).build();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> saver.restore(noRam));
        assertTrue(e.getMessage().contains("no ram region"), e.getMessage());
    }

    @Test
    void aReplTargetNeedsTheDebuggerOfItsOwnCpu() {
        Ram ram = new Ram();
        Cpu8080 a = new Cpu8080(ram);
        Cpu8080 b = new Cpu8080(ram);
        Debugger forB = new Debugger(b, ram::peek);
        assertThrows(IllegalArgumentException.class, () -> new ReplTarget8080(a, ram, forB, java.util.Map.of()));
        ReplTarget8080 ok = new ReplTarget8080(b, ram, forB, java.util.Map.of(5, "five"));
        assertEquals("i8080", ok.name());
        assertEquals(Map5.LABELS, ok.addressLabels());
        assertThrows(NullPointerException.class, () -> new ReplTarget8080(null, ram, forB, java.util.Map.of()));
    }

    private static final class Map5 {
        static final java.util.Map<Integer, String> LABELS = java.util.Map.of(5, "five");
    }

    // ---- memory bus defaults --------------------------------------------------------------------------------------------

    @Test
    void fetchAndPeekDefaultToRead() {
        int[] reads = {0};
        MemoryBus bus = new MemoryBus() {
            @Override
            public int read(int address) {
                reads[0]++;
                return address & 0xFF;
            }

            @Override
            public void write(int address, int value) {
            }
        };
        assertEquals(0x34, bus.fetch(0x1234));
        assertEquals(0x35, bus.peek(0x1235));
        assertEquals(2, reads[0]);
    }

    // ---- gate-level accessors and limits ------------------------------------------------------------------------------------

    @Test
    void sixtyFourBitRegistersExistButSixtyFiveDoNot() {
        Simulator sim = new Simulator();
        Clock clk = new Clock(sim, false);
        new Register(sim, clk, Buses.simpleBus(sim, 64, 0), false);
        assertThrows(IllegalArgumentException.class, () -> new Register(sim, clk, Buses.simpleBus(sim, 65, 0), false));
        assertThrows(com.darchan.physical.validation.UnexpectedBusWidthException.class,
                () -> new Register(sim, clk, Buses.simpleBus(sim, 1, 0), false));
        Register named = new Register(sim, clk, Buses.simpleBus(sim, 2, 0), null, false, "mine");
        assertEquals("mine", named.name());
        Counter counter = new Counter(sim, clk, 3, "cnt");
        assertEquals("cnt", counter.name());
        assertEquals("counter0", new Counter(sim, clk, 3).name());
    }

    @Test
    void accessorsReturnTheirComponentsParts() {
        Simulator sim = new Simulator();
        Clock clk = new Clock(sim, false);
        SimpleSignal enable = new SimpleSignal(sim, true);
        TriStateBuffer buffer = new TriStateBuffer(sim, new Bus(new SimpleSignal(sim, true)), enable);
        assertSame(enable, buffer.getEnable());
        RegisterFile rf = new RegisterFile(sim, clk, Buses.simpleBus(sim, 16, 0));
        assertSame(clk, rf.getClock());
        ISignal and = Gates.nand(sim, new SimpleSignal(sim, true), new SimpleSignal(sim, true));
        assertNotNull(and);
        sim.settle();
        assertFalse(and.isOn());
    }

    @Test
    void theDatapathNeverWritesTheRegisterFilesOwnFRegister() {
        Simulator sim = new Simulator();
        Clock clk = new Clock(sim, false);
        IBus c = Buses.simpleBus(sim, 30, 0);
        AluDatapath dp = new AluDatapath(sim, clk, c, "dp");
        sim.settle();
        // every write enable high, including the F slot, with the immediate on the data path
        Buses.drive(c, 0xFF | 1 << 16 | 1 << 20 | 0xFF << 21 | 1 << 29);
        sim.settle();
        clk.tick();
        clk.tick();
        StateElement regsF = sim.stateElements().stream().filter(e -> e.name().equals("dp.regs.F")).findFirst()
                .orElseThrow();
        for (int bit = 0; bit < 8; bit++) {
            assertEquals(com.darchan.physical.signal.LogicValue.ZERO, regsF.get(bit),
                    "the F slot's write enable is tied low; the flags live in their own flip-flops (bit " + bit + ")");
        }
        assertEquals(0xFF, Buses.toInt(dp.getRegister(RegisterFile.Reg.A)));
    }

    // ---- co-simulation ---------------------------------------------------------------------------------------------------------

    @Test
    void theCosimExposesItsPartsAndTracesInstructionBytesInOrder() {
        byte[] program = {0x3E, 0x42, 0x06, 0x07, 0x00}; // MVI A,42 ; MVI B,07 ; NOP
        AluDatapathCosim cosim = new AluDatapathCosim(program);
        assertEquals(3, cosim.instructionCount());
        assertEquals(0, cosim.programCounter());
        assertNotNull(cosim.behavioralDebugger());
        assertSame(cosim.behavioral(), cosim.behavioralDebugger().target());
        assertNotNull(cosim.simulator());
        assertNotNull(cosim.datapath());
        assertNotNull(cosim.probes());
        assertSame(cosim.gateLevel(), cosim.gateDebugger().target());
        InMemoryTrace trace = new InMemoryTrace();
        cosim.gateDebugger().addTraceListener(trace);
        cosim.gateLevel().step();
        cosim.gateLevel().step();
        assertTrue(trace.lines().get(0).contains("0000: 3E 42"), trace.lines().get(0));
        assertTrue(trace.lines().get(1).contains("0002: 06 07"), trace.lines().get(1));
        assertEquals(4, cosim.programCounter(), "the control unit is at the third instruction");
        assertEquals(0x42, cosim.probes().value("A"));
        assertEquals(0x07, cosim.probes().value("B"));
    }

    @Test
    void stuckAtFaultsTieTheChosenOperandBit() {
        for (boolean value : new boolean[]{false, true}) {
            IBus bus = Buses.simpleBus(new Simulator(), 8, value ? 0x00 : 0xFF);
            IBus tapped = AluDatapathCosim.stuckAt(3, value).apply(bus);
            assertEquals(8, tapped.getSignals().size());
            for (int i = 0; i < 8; i++) {
                if (i == 3) {
                    assertSame(value ? ConstantSignal.ON : ConstantSignal.OFF, tapped.getSignal(i), "bit 3 is tied");
                } else {
                    assertSame(bus.getSignal(i), tapped.getSignal(i), "bit " + i + " passes through");
                }
            }
        }
        // the fault is seen as a divergence on an instruction that exercises it
        AluDatapathCosim cosim = new AluDatapathCosim(new byte[]{0x06, 0x08, (byte) 0x80}, // MVI B,08 ; ADD B
                AluDatapathCosim.stuckAt(3, false));
        var result = cosim.lockstep().run(10);
        assertTrue(result.diverged(), result::report);
    }

    // ---- reports -----------------------------------------------------------------------------------------------------------------

    @Test
    void aLastMatchAtStepZeroIsStillAMatch() {
        var side = new Divergence.SideState("r", 1, 1, 1, List.of());
        Divergence d = new Divergence(5, 1, 0, side, side, null, "x");
        assertTrue(d.report().startsWith("LOCKSTEP DIVERGENCE after step 5 (comparison #1, last match after step 0)"),
                d::report);
        Divergence none = new Divergence(5, 1, -1, side, side, null, "x");
        assertTrue(none.report().contains("no earlier match"), none::report);
    }

    @Test
    void anInMemoryTraceOfNothingIsEmptyText() {
        InMemoryTrace trace = new InMemoryTrace();
        assertEquals("", trace.text());
        assertEquals("", RegisterSpec.of("A", 8).flagNames());
        assertEquals(0, trace.dropped());
    }

    @Test
    void onlyTheBdosStubsOutCountsAsABdosCall() {
        // an OUT 1 in the program itself (not at the stub's address) is an ordinary port write
        CpmMachine m = new CpmMachine(new byte[]{(byte) 0xD3, 0x01});
        m.cpu().setC(2);
        m.cpu().setE('Z');
        m.cpu().step();
        assertEquals("", m.output());
        // an OUT to another port from the stub address is not a call either
        CpmMachine other = new CpmMachine(new byte[0]);
        other.ram().write(CpmMachine.BDOS_ENTRY + 1, 0x02); // the stub now reads OUT 2
        other.cpu().setC(2);
        other.cpu().setE('Z');
        other.cpu().setPC(CpmMachine.BDOS_ENTRY);
        other.cpu().step();
        assertEquals("", other.output());
    }
}
