package com.darchan.physical.comp;

import com.darchan.physical.comp.i8080.Alu;
import com.darchan.physical.comp.i8080.AluDatapath;
import com.darchan.physical.comp.i8080.RegisterFile;
import com.darchan.physical.probe.Probe;
import com.darchan.physical.probe.ProbeSet;
import com.darchan.physical.probe.ProbeValue;
import com.darchan.physical.signal.AbstractSignal;
import com.darchan.physical.signal.AbstractSignalDecorator;
import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.Clock;
import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.LogicValue;
import com.darchan.physical.signal.SimpleRange;
import com.darchan.physical.signal.SimpleSignal;
import com.darchan.physical.sim.Simulator;
import com.darchan.physical.validation.UnexpectedBusWidthException;
import com.darchan.testutil.Buses;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Small contracts of the gate-level components: bus selection, datapath wiring, probes, signal arguments. */
class PhysicalEdgeCasesTest {

    private final Simulator sim = new Simulator();

    // ---- bus selector ---------------------------------------------------------------------------------------------

    @Test
    void theSelectedBusIsFollowedAndOthersAreIgnoredWhateverTheSelection() {
        IBus in = Buses.simpleBus(sim, 72, 0);
        BusSelector8By8Bit mux = new BusSelector8By8Bit(sim, in);
        sim.settle();
        int[] data = new int[8];
        for (int select = 0; select < 8; select++) {
            Buses.drive(in.slice(new SimpleRange(64, 71)), 1 << select);
            sim.settle();
            for (int round = 1; round <= 3; round++) {
                for (int bus = 0; bus < 8; bus++) {
                    int value = (bus * 37 + round * 91 + select * 13) & 0xFF;
                    data[bus] = value;
                    Buses.drive(in.slice(new SimpleRange(8 * bus, 8 * bus + 7)), value);
                    sim.settle();
                    assertEquals(data[select], Buses.toInt(mux.getOutputBus()),
                            "select " + select + " after writing bus " + bus + " round " + round);
                }
            }
        }
    }

    @Test
    void switchingSelectionRetargetsTheOutputAndForgetsTheOldBus() {
        IBus in = Buses.simpleBus(sim, 72, 0);
        BusSelector8By8Bit mux = new BusSelector8By8Bit(sim, in);
        SimpleRange selectBits = new SimpleRange(64, 71);
        Buses.drive(in.slice(new SimpleRange(0, 7)), 0xAA);
        Buses.drive(in.slice(new SimpleRange(8, 15)), 0x55);
        Buses.drive(in.slice(selectBits), 1);
        sim.settle();
        assertEquals(0xAA, Buses.toInt(mux.getOutputBus()));
        Buses.drive(in.slice(selectBits), 2);
        sim.settle();
        assertEquals(0x55, Buses.toInt(mux.getOutputBus()));
        Buses.drive(in.slice(new SimpleRange(0, 7)), 0x11); // the old bus changes: no effect
        sim.settle();
        assertEquals(0x55, Buses.toInt(mux.getOutputBus()));
        Buses.drive(in.slice(new SimpleRange(8, 15)), 0x66);
        sim.settle();
        assertEquals(0x66, Buses.toInt(mux.getOutputBus()));
        Buses.drive(in.slice(selectBits), 0);
        sim.settle();
        assertTrue(mux.getOutputBus().getSignals().stream().allMatch(s -> s.getValue() == LogicValue.Z));
        Buses.drive(in.slice(selectBits), 1);
        sim.settle();
        assertEquals(0x11, Buses.toInt(mux.getOutputBus()));
    }

    // ---- datapath ------------------------------------------------------------------------------------------------------

    private static final int IMM = 0, SRC = 8, USE_IMM = 16, OP = 17, PASS_B = 20, WE = 21, FLAGS_WE = 29;

    @Test
    void theDatapathChecksItsInputWidthAndItsOperandBTap() {
        Clock clk = new Clock(sim, false);
        assertThrows(UnexpectedBusWidthException.class, () -> new AluDatapath(sim, clk, Buses.simpleBus(sim, 29, 0)));
        assertThrows(UnexpectedBusWidthException.class, () -> new AluDatapath(sim, clk, Buses.simpleBus(sim, 31, 0)));
        assertThrows(UnexpectedBusWidthException.class, () -> new AluDatapath(sim, clk, Buses.simpleBus(sim, 30, 0),
                b -> new Bus(b.getSignal(0), b.getSignal(1))));
        assertThrows(UnexpectedBusWidthException.class, () -> new AluDatapath(sim, clk, Buses.simpleBus(sim, 30, 0),
                b -> new Bus(b.getSignals().toArray(new ISignal[0])[0])));
        assertThrows(NullPointerException.class, () -> new AluDatapath(sim, clk, Buses.simpleBus(sim, 30, 0), (java.util.function.UnaryOperator<IBus>) null));
        AluDatapath ok = new AluDatapath(sim, clk, Buses.simpleBus(sim, 30, 0));
        assertEquals(30, ok.getInputBus().getSignals().size());
        assertEquals(64, ok.getOutputBus().getSignals().size());
        assertEquals(30, ok.getAllowedInputBusWidth().getMin());
        assertEquals(30, ok.getAllowedInputBusWidth().getMax());
        assertEquals(64, ok.getAllowedOutputBusWidth().getMin());
        assertEquals(64, ok.getAllowedOutputBusWidth().getMax());
        assertEquals(8, ok.getOperandB().getSignals().size());
    }

    @Test
    void theOutputBusShowsTheFlagsRegisterInTheFSlotAndTheRegistersElsewhere() {
        Clock clk = new Clock(sim, false);
        IBus c = Buses.simpleBus(sim, 30, 0);
        AluDatapath dp = new AluDatapath(sim, clk, c, "dp");
        sim.settle();
        // MVI A,0xF0 ; MVI B,0x0F ; ADD B (A = FF: S set, P set, no carry) with flags loaded
        Buses.drive(c.slice(new SimpleRange(IMM, IMM + 7)), 0xF0);
        Buses.drive(c.slice(new SimpleRange(USE_IMM, USE_IMM)), 1);
        Buses.drive(c.slice(new SimpleRange(PASS_B, PASS_B)), 1);
        Buses.drive(c.slice(new SimpleRange(WE, WE + 7)), 1 << RegisterFile.Reg.A.slot());
        sim.settle();
        clk.tick();
        clk.tick();
        Buses.drive(c.slice(new SimpleRange(IMM, IMM + 7)), 0x0F);
        Buses.drive(c.slice(new SimpleRange(WE, WE + 7)), 1 << RegisterFile.Reg.B.slot());
        sim.settle();
        clk.tick();
        clk.tick();
        Buses.drive(c.slice(new SimpleRange(SRC, SRC + 7)), 1 << RegisterFile.Reg.B.slot());
        Buses.drive(c.slice(new SimpleRange(USE_IMM, USE_IMM)), 0);
        Buses.drive(c.slice(new SimpleRange(PASS_B, PASS_B)), 0);
        Buses.drive(c.slice(new SimpleRange(OP, OP + 2)), Alu.Op.ADD.code());
        Buses.drive(c.slice(new SimpleRange(WE, WE + 7)), 1 << RegisterFile.Reg.A.slot());
        Buses.drive(c.slice(new SimpleRange(FLAGS_WE, FLAGS_WE)), 1);
        sim.settle();
        clk.tick();
        clk.tick();
        int expectedFlags = 0x80 | 0x04 | 0x02; // S, P and the always-one bit
        assertEquals(expectedFlags, Buses.toInt(dp.getRegister(RegisterFile.Reg.F)));
        assertEquals(0xFF, Buses.toInt(dp.getRegister(RegisterFile.Reg.A)));
        assertEquals(0x0F, Buses.toInt(dp.getRegister(RegisterFile.Reg.B)));
        IBus out = dp.getOutputBus();
        for (RegisterFile.Reg r : RegisterFile.Reg.values()) {
            assertEquals(Buses.toInt(dp.getRegister(r)), Buses.toInt(out.slice(r.outputRange())), "slot " + r);
        }
        assertEquals(expectedFlags, Buses.toInt(out.slice(RegisterFile.Reg.F.outputRange())));
        assertEquals(0xFF, Buses.toInt(out.slice(RegisterFile.Reg.A.outputRange())));
    }

    // ---- probes ----------------------------------------------------------------------------------------------------------

    @Test
    void probeSetAddReturnsTheProbeAndKnowsItsNames() {
        ProbeSet probes = new ProbeSet(sim);
        SimpleSignal s = new SimpleSignal(sim, true);
        Probe a = probes.add("a.sig", s);
        assertEquals("a.sig", a.name());
        assertEquals(1, a.width());
        Probe b = probes.add("bus", Buses.simpleBus(sim, 3, 5));
        assertEquals(3, b.width());
        Probe c = probes.add("list", List.of(s, s));
        assertEquals(2, c.width());
        assertTrue(probes.contains("a.sig"));
        assertTrue(probes.contains("bus"));
        assertFalse(probes.contains("a"));
        assertFalse(probes.contains("a.sig.x"));
        assertFalse(probes.contains("nope"));
        assertSame(sim, probes.simulator());
        assertSame(b, probes.get("bus"));
        assertEquals(List.of("a.sig", "bus", "list"), probes.probes().stream().map(Probe::name).toList());
        assertEquals(5, probes.value("bus"));
        assertEquals("5", probes.read("bus").toString().replaceFirst("^0*", ""));
        assertThrows(IllegalArgumentException.class, () -> probes.get("nope"));
        // a name cannot be both a signal and a scope, in either order
        assertThrows(IllegalArgumentException.class, () -> probes.add("a.sig.deeper", s));
        assertThrows(IllegalArgumentException.class, () -> probes.add("a", s));
        assertThrows(IllegalArgumentException.class, () -> probes.add("bus", s));
        assertThrows(IllegalArgumentException.class, () -> probes.add("m.Z", s));
        assertThrows(IllegalArgumentException.class, () -> probes.add("m.X", s));
        probes.add("m.Zed", s);
        probes.add("ab", s); // shares a prefix with "a.sig" but is a different name
    }

    @Test
    void probeValueMasksAreValidatedAndRendered() {
        assertThrows(IllegalArgumentException.class, () -> new ProbeValue(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ProbeValue(65, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ProbeValue(4, 0x10, 0, 0), "ones outside the width");
        assertThrows(IllegalArgumentException.class, () -> new ProbeValue(4, 0, 0x10, 0), "z outside the width");
        assertThrows(IllegalArgumentException.class, () -> new ProbeValue(4, 0, 0, 0x10), "x outside the width");
        assertThrows(IllegalArgumentException.class, () -> new ProbeValue(4, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ProbeValue(4, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProbeValue(4, 0, 1, 1));
        assertEquals(-1L, new ProbeValue(64, -1L, 0, 0).value());
        new ProbeValue(1, 1, 0, 0);
        new ProbeValue(64, 0, Long.MIN_VALUE, 1);

        ProbeValue v = new ProbeValue(4, 0b0001, 0b0100, 0b1000);
        assertEquals(LogicValue.ONE, v.bit(0));
        assertEquals(LogicValue.ZERO, v.bit(1));
        assertEquals(LogicValue.Z, v.bit(2));
        assertEquals(LogicValue.X, v.bit(3));
        assertThrows(IndexOutOfBoundsException.class, () -> v.bit(4));
        assertThrows(IndexOutOfBoundsException.class, () -> v.bit(-1));
        assertEquals("xz01", v.bits());
        assertEquals("xz01", v.toString());
        assertFalse(v.isKnown());
        assertEquals(1, v.value());
        assertEquals("3F", new ProbeValue(6, 0x3F, 0, 0).toString());
        assertEquals("0A", new ProbeValue(8, 0x0A, 0, 0).toString());
        assertEquals("Z", new ProbeValue(5, 0, 0x1F, 0).toString());
        assertEquals("X", new ProbeValue(5, 0, 0, 0x1F).toString());
        assertEquals("xzzz", new ProbeValue(4, 0, 0b0111, 0b1000).toString());
        assertEquals("Z", new ProbeValue(64, 0, -1L, 0).toString());
        assertEquals("X", new ProbeValue(64, 0, 0, -1L).toString());
        assertEquals("FFFFFFFFFFFFFFFF", new ProbeValue(64, -1L, 0, 0).toString());
        assertTrue(new ProbeValue(2, 3, 0, 0).isKnown());
    }

    // ---- signals -----------------------------------------------------------------------------------------------------------

    private static final class Plain extends AbstractSignal {
        Plain(Simulator sim, boolean on) {
            super(sim, on);
        }

        Plain(Simulator sim, LogicValue v) {
            super(sim, v);
        }

        void write(LogicValue v) {
            scheduleValue(v);
        }
    }

    @Test
    void aSignalNeedsASimulatorAndAnInitialValueAndRejectsNullWrites() {
        assertThrows(IllegalArgumentException.class, () -> new Plain(null, false));
        assertThrows(IllegalArgumentException.class, () -> new Plain(null, LogicValue.X));
        assertThrows(IllegalArgumentException.class, () -> new Plain(sim, (LogicValue) null));
        Plain p = new Plain(sim, LogicValue.X);
        assertSame(sim, p.getSimulator());
        assertThrows(IllegalArgumentException.class, () -> p.write(null));
        assertEquals(LogicValue.X, p.getValue());
        p.write(LogicValue.ONE);
        assertEquals(LogicValue.X, p.getValue(), "writes land in the next delta");
        sim.settle();
        assertEquals(LogicValue.ONE, p.getValue());
        assertTrue(p.isOn());
    }

    @Test
    void aDecoratorDelegatesBothViewsOfTheValue() {
        SimpleSignal inner = new SimpleSignal(sim, LogicValue.ONE);
        AbstractSignalDecorator d = new AbstractSignalDecorator(inner) { };
        assertTrue(d.isOn());
        assertEquals(LogicValue.ONE, d.getValue());
        inner.setValue(LogicValue.X);
        sim.settle();
        assertFalse(d.isOn());
        assertEquals(LogicValue.X, d.getValue());
        inner.setOn(false);
        sim.settle();
        assertEquals(LogicValue.ZERO, d.getValue());
    }

    @Test
    void componentsReportTheirBuses() {
        Clock clk = new Clock(sim, false);
        Counter counter = new Counter(sim, clk, 5);
        assertEquals(0, counter.getInputBus().getSignals().size());
        assertEquals(0, counter.getAllowedInputBusWidth().getMax());
        assertEquals(5, counter.getOutputBus().getSignals().size());
        assertEquals(1, counter.getAllowedOutputBusWidth().getMin());
        assertEquals(63, counter.getAllowedOutputBusWidth().getMax());
        assertEquals(5, counter.getNextState().getSignals().size());
        assertThrows(IllegalArgumentException.class, () -> new Counter(sim, clk, 0));
        assertThrows(IllegalArgumentException.class, () -> new Counter(sim, clk, 64));
        assertEquals(63, new Counter(sim, clk, 63).width());
        assertEquals(1, new Counter(sim, clk, 1).width());
    }
}
