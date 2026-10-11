package com.darchan;

import com.darchan.behavioral.bus.IoBus;
import com.darchan.behavioral.bus.ObservedIoBus;
import com.darchan.behavioral.cpm.CpmMachine;
import com.darchan.diag.MemoryRegion;
import com.darchan.physical.comp.Inverter;
import com.darchan.physical.comp.Register;
import com.darchan.physical.comp.arith.RippleCarryAdder;
import com.darchan.physical.logic.Gates;
import com.darchan.physical.probe.ProbeSet;
import com.darchan.physical.signal.Clock;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.ISignalChangeListener;
import com.darchan.diag.compare.Checkpoint;
import com.darchan.physical.signal.LogicValue;
import com.darchan.physical.signal.SimpleSignal;
import com.darchan.physical.sim.Simulator;
import com.darchan.physical.validation.UnexpectedBusWidthException;
import com.darchan.physical.wave.VcdWriter;
import com.darchan.testutil.Buses;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

/** Leftovers of the second triage round: accessors, messages and a few lifecycle details. */
class FinalEdgeCasesTest {

    @Test
    void accessorsAndDefaultsReturnWhatTheyShould() {
        IoBus io = new IoBus() {
            @Override
            public int in(int port) {
                return 7;
            }

            @Override
            public void out(int port, int value) {
            }
        };
        assertSame(io, new ObservedIoBus(io, (t, a, v) -> { }).inner());
        assertEquals(5, new Checkpoint.AtPc(5, 1).address());
        UnexpectedBusWidthException e = assertThrows(UnexpectedBusWidthException.class,
                () -> new Inverter(new Simulator(), Buses.simpleBus(new Simulator(), 3, 0)));
        assertEquals(3, e.getActualWidth());
        assertEquals(1, e.getMinimumWidth());
        assertEquals(1, e.getMaximumWidth());
        ISignal onlyIsOn = new ISignal() {
            @Override
            public boolean isOn() {
                return true;
            }

            @Override
            public void addSignalChangeListener(ISignalChangeListener listener) {
            }

            @Override
            public void removeSignalChangeListener(ISignalChangeListener listener) {
            }
        };
        assertEquals(LogicValue.ONE, onlyIsOn.getValue(), "a two-valued signal derives its level from isOn()");
        Simulator sim = new Simulator();
        RippleCarryAdder adder = new RippleCarryAdder(sim, 5, Buses.simpleBus(sim, 11, 0));
        assertEquals(6, adder.getAllowedOutputBusWidth().getMin());
        assertEquals(6, adder.getAllowedOutputBusWidth().getMax());
        assertEquals(11, adder.getAllowedInputBusWidth().getMin());
    }

    @Test
    void aRegisterOverSixtyFourBitsSaysSo() {
        Simulator sim = new Simulator();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Register(sim, new Clock(sim, false), Buses.simpleBus(sim, 65, 0), false));
        assertTrue(e.getMessage().contains("at most 64 bits"), e.getMessage());
    }

    @Test
    void aMemoryRegionNamesTheAddressItDoesNotCover() {
        IndexOutOfBoundsException e = assertThrows(IndexOutOfBoundsException.class,
                () -> new MemoryRegion("r", 10, new byte[2]).byteAt(12));
        assertTrue(e.getMessage().contains("outside r[A+2]"), e.getMessage());
        assertThrows(IndexOutOfBoundsException.class, () -> new MemoryRegion("r", 10, new byte[2]).byteAt(9));
    }

    @Test
    void anEmptyConsoleCanBeSavedAndRestored() {
        CpmMachine m = new CpmMachine(new byte[0]);
        var saver = m.stateSaver();
        var saved = saver.save();
        assertEquals(0, saved.value(CpmMachine.CONSOLE_CHARS));
        saver.restore(saved);
        assertEquals("", m.output());
    }

    // ---- VCD glitches and closing mid-run --------------------------------------------------------------------------------

    @Test
    void aGlitchWithinOneSettleIsNotRecordedAsAChange() {
        Simulator sim = new Simulator();
        SimpleSignal a = new SimpleSignal(sim, false);
        // y = a AND NOT(NOT(NOT a)): after a rises, y is 1 for two deltas, then back to 0 (a static hazard)
        ISignal slowNotA = Gates.not(sim, Gates.not(sim, Gates.not(sim, a)));
        ISignal y = Gates.and(sim, a, slowNotA);
        sim.settle();
        assertFalse(y.isOn());
        ProbeSet probes = new ProbeSet(sim);
        probes.add("a", a);
        probes.add("y", y);
        StringWriter out = new StringWriter();
        VcdWriter vcd = VcdWriter.builder(probes).to(out);
        a.setOn(true);
        sim.settle();
        assertFalse(y.isOn(), "the glitch is over by the end of the settle");
        vcd.close();
        assertEquals(1, vcd.changesWritten(), "only a changed in the tick; y went 0 -> 1 -> 0");
        assertFalse(out.toString().contains("1\""), out.toString());
    }

    @Test
    void closingInsideARunStillWritesTheChangesAlreadySeen() {
        Simulator sim = new Simulator();
        SimpleSignal a = new SimpleSignal(sim, false);
        ProbeSet probes = new ProbeSet(sim);
        probes.add("a", a);
        StringWriter out = new StringWriter();
        VcdWriter vcd = VcdWriter.builder(probes).to(out);
        a.addSignalChangeListener((o, n) -> vcd.close()); // runs after the writer's own listener, mid-run
        a.setOn(true);
        sim.settle();
        assertTrue(out.toString().contains("1!"), "the change seen before close() is written: " + out);
        assertEquals(1, vcd.changesWritten());
    }

    @Test
    void aClosedWriterNoLongerListensToTheSimulatorOrItsSignals() {
        Simulator sim = new Simulator();
        SimpleSignal a = new SimpleSignal(sim, false);
        ProbeSet probes = new ProbeSet(sim);
        probes.add("a", a);
        StringWriter out = new StringWriter();
        VcdWriter vcd = VcdWriter.builder(probes).timing(VcdWriter.Timing.SIM_TIME).to(out);
        vcd.close();
        int length = out.toString().length();
        // if the writer were still attached, changes would be recorded; count what the simulator still does to it
        a.setOn(true);
        sim.scheduleAfter(3, () -> a.setOn(false));
        sim.settle();
        assertEquals(length, out.toString().length());
        assertEquals(0, vcd.changesWritten());
        // the signal has no writer listener left: a second writer on the same signal is the only one that sees it
        StringWriter second = new StringWriter();
        VcdWriter other = VcdWriter.builder(probes).to(second);
        a.setOn(true);
        sim.settle();
        other.close();
        assertEquals(1, other.changesWritten());
    }
}
