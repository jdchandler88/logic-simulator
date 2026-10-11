package com.darchan.physical.probe;

import com.darchan.diag.Snapshot;
import com.darchan.physical.comp.Counter;
import com.darchan.physical.comp.FlipFlop;
import com.darchan.physical.comp.Register;
import com.darchan.physical.comp.i8080.AluDatapath;
import com.darchan.physical.comp.i8080.RegisterFile;
import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.Clock;
import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.LogicValue;
import com.darchan.physical.signal.SimpleSignal;
import com.darchan.physical.signal.Wire;
import com.darchan.physical.sim.Simulator;
import com.darchan.physical.sim.StateElement;
import com.darchan.testutil.Buses;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.darchan.physical.logic.Gates.nor;
import static org.junit.jupiter.api.Assertions.*;

/** Reading and forcing storage elements, and lossless snapshot/restore of a {@link ClockedTarget}. */
class GateLevelRestoreTest {

    // ---- storage elements --------------------------------------------------------------------------------------

    @Test
    void flipFlopsRegisterThemselvesWithTheirSimulator() {
        Simulator sim = new Simulator();
        Clock clk = new Clock(sim, false);
        SimpleSignal d = new SimpleSignal(sim, true);
        FlipFlop a = new FlipFlop(sim, clk, new Bus(d), false);
        FlipFlop b = new FlipFlop(sim, clk, new Bus(d), null, true, "ctl.busy");
        assertEquals(List.of(a, b), sim.stateElements());
        assertEquals("ff0", a.name());
        assertEquals("ctl.busy", b.name());
        assertEquals(1, a.width());
        assertEquals(LogicValue.ZERO, a.get(0));
        assertEquals(LogicValue.ONE, b.get(0));
        a.force(0, LogicValue.ONE);
        b.force(0, LogicValue.X);
        sim.settle();
        assertEquals(LogicValue.ONE, a.getOutputBus().getSignal(0).getValue());
        assertEquals(LogicValue.X, b.get(0));
    }

    @Test
    void registersAndCountersAreOneElementEach() {
        Simulator sim = new Simulator();
        Clock clk = new Clock(sim, false);
        IBus in = Buses.simpleBus(sim, 4, 0);
        Register reg = new Register(sim, clk, in, null, false, "acc");
        Counter counter = new Counter(sim, clk, 3);
        Register anonymous = new Register(sim, clk, in, false);
        List<StateElement> all = sim.stateElements();
        assertEquals(3, all.size(), "the flip-flops inside are not registered separately: " + all);
        assertEquals("acc", all.get(0).name());
        assertEquals(4, all.get(0).width());
        assertSame(counter, all.get(1));
        assertEquals("counter0", counter.name());
        assertEquals("reg0", anonymous.name());
        reg.force(2, LogicValue.ONE);
        counter.force(0, LogicValue.ONE);
        counter.force(1, LogicValue.ONE);
        sim.settle();
        assertEquals(4, Buses.toInt(reg.getOutputBus()));
        assertEquals(3, Buses.toInt(counter.getOutputBus()));
        clk.tick();
        assertEquals(4, Buses.toInt(counter.getOutputBus()), "the counter counts on from the forced state");
    }

    @Test
    void namesAreHierarchicalAndStable() {
        List<String> first = datapathStateNames();
        assertEquals(List.of("dp.regs.L", "dp.regs.H", "dp.regs.E", "dp.regs.D", "dp.regs.C", "dp.regs.B",
                "dp.regs.F", "dp.regs.A", "dp.flag_CY", "dp.flag_P", "dp.flag_AC", "dp.flag_Z", "dp.flag_S"),
                first);
        assertEquals(first, datapathStateNames(), "identically built circuits get identical names");
    }

    private static List<String> datapathStateNames() {
        Simulator sim = new Simulator();
        Clock clk = new Clock(sim, false);
        new AluDatapath(sim, clk, Buses.simpleBus(sim, 30, 0), "dp");
        List<String> names = new ArrayList<>();
        sim.stateElements().forEach(e -> names.add(e.name()));
        return names;
    }

    @Test
    void defaultNamesAreNumberedInConstructionOrder() {
        Simulator sim = new Simulator();
        Clock clk = new Clock(sim, false);
        new RegisterFile(sim, clk, Buses.simpleBus(sim, 16, 0));
        new RegisterFile(sim, clk, Buses.simpleBus(sim, 16, 0));
        new AluDatapath(sim, clk, Buses.simpleBus(sim, 30, 0));
        List<String> names = sim.stateElements().stream().map(StateElement::name).toList();
        assertEquals("regfile0.L", names.get(0));
        assertEquals("regfile1.A", names.get(15));
        assertEquals("datapath0.regs.L", names.get(16));
        assertEquals("datapath0.flag_S", names.get(names.size() - 1));
    }

    @Test
    void duplicateOrMalformedNamesAreRejected() {
        Simulator sim = new Simulator();
        Clock clk = new Clock(sim, false);
        SimpleSignal d = new SimpleSignal(sim, false);
        new FlipFlop(sim, clk, new Bus(d), null, false, "x.y");
        assertThrows(IllegalArgumentException.class, () -> new FlipFlop(sim, clk, new Bus(d), null, false, "x.y"));
        assertThrows(IllegalArgumentException.class, () -> new FlipFlop(sim, clk, new Bus(d), null, false, "a b"));
        assertThrows(IllegalArgumentException.class, () -> new FlipFlop(sim, clk, new Bus(d), null, false, ".a"));
        // a last segment Z or X would be mistaken for a snapshot mask field
        assertThrows(IllegalArgumentException.class, () -> new FlipFlop(sim, clk, new Bus(d), null, false, "f.Z"));
        assertThrows(IllegalArgumentException.class, () -> new FlipFlop(sim, clk, new Bus(d), null, false, "X"));
        // an automatic name skips names already taken explicitly
        new FlipFlop(sim, clk, new Bus(d), null, false, "ff0");
        assertEquals("ff1", new FlipFlop(sim, clk, new Bus(d), false).name());
    }

    // ---- ClockedTarget snapshot / restore ----------------------------------------------------------------------

    @Test
    void snapshotsHoldStorageElements() {
        Simulator sim = new Simulator();
        Clock clk = new Clock(sim, false);
        Counter counter = new Counter(sim, clk, 4);
        sim.settle();
        ProbeSet probes = new ProbeSet(sim);
        probes.add("count", counter.getOutputBus());
        ClockedTarget t = new ClockedTarget("counter4", clk, probes);
        t.step();
        t.step();
        Snapshot s = t.snapshot();
        assertEquals(List.of("count", "state.counter0", "CYCLES", "INSTRUCTIONS"), s.fieldNames());
        assertEquals(2, s.value("state.counter0"));
        assertTrue(t.isSnapshotComplete());
    }

    @Test
    void aCounterRoundTrips() {
        Simulator sim = new Simulator();
        Clock clk = new Clock(sim, false);
        Counter counter = new Counter(sim, clk, 4);
        sim.settle();
        ProbeSet probes = new ProbeSet(sim);
        probes.add("count", counter.getOutputBus());
        probes.add("next", counter.getNextState());
        ClockedTarget t = new ClockedTarget("counter4", clk, probes);
        for (int i = 0; i < 7; i++) {
            t.step();
        }
        Snapshot at7 = t.snapshot();
        for (int i = 0; i < 20; i++) {
            t.step();
        }
        t.restore(at7);
        assertEquals(at7, t.snapshot());
        assertEquals(7, probes.value("count"));
        assertEquals(8, probes.value("next"), "combinational logic settled after the restore");
        assertEquals(7, t.cycleCount());
        t.step();
        assertEquals(8, probes.value("count"));
    }

    /** the datapath with a random control stimulus: state depends on every cycle */
    private static final class RandomDatapath {
        final Simulator sim = new Simulator();
        final Clock clk = new Clock(sim, false);
        final IBus controls = Buses.simpleBus(sim, 30, 0);
        final AluDatapath dp = new AluDatapath(sim, clk, controls, "dp");
        final ProbeSet probes = new ProbeSet(sim);
        final ClockedTarget target;
        long lfsr = 0xACE1;

        RandomDatapath() {
            sim.settle();
            for (RegisterFile.Reg r : RegisterFile.Reg.values()) {
                probes.add(r.name(), dp.getRegister(r));
            }
            probes.add("alu", dp.getAluOutput());
            target = new ClockedTarget("dp", clk, probes);
            // the stimulus depends on the cycle number and on the testbench's own state (an LFSR), which is
            // registered so that a restore puts it back too
            target.addState("lfsr", 16, () -> lfsr, v -> lfsr = v);
            target.addInputs("ctl", controls);
            target.setStimulus(cycle -> {
                lfsr = (lfsr >> 1) ^ (-(lfsr & 1) & 0xB400);
                long bits = new Random(cycle * 31 + lfsr).nextLong() & ((1L << 30) - 1);
                bits &= ~(1L << (21 + RegisterFile.Reg.F.slot())); // the F slot's write enable is ignored anyway
                Buses.drive(controls, (int) bits);
            });
        }
    }

    @Test
    void aDatapathRoundTripsLosslessly() {
        RandomDatapath c = new RandomDatapath();
        for (int i = 0; i < 40; i++) {
            c.target.step();
        }
        Snapshot at40 = c.target.snapshot();
        List<Snapshot> firstRun = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            c.target.step();
            firstRun.add(c.target.snapshot());
        }
        c.target.restore(at40);
        assertEquals(at40, c.target.snapshot(), "the restored state is the saved one, probes included");
        List<Snapshot> secondRun = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            c.target.step();
            secondRun.add(c.target.snapshot());
        }
        assertEquals(firstRun, secondRun);
        assertNotEquals(firstRun.get(0).value("state.dp.regs.A"), firstRun.get(24).value("state.dp.regs.A"),
                "the run actually changed state");
    }

    @Test
    void aSnapshotCanBeRestoredIntoAnIdenticallyBuiltCircuit() {
        RandomDatapath a = new RandomDatapath();
        RandomDatapath b = new RandomDatapath();
        for (int i = 0; i < 30; i++) {
            a.target.step();
        }
        b.target.restore(a.target.snapshot());
        for (int i = 0; i < 10; i++) {
            a.target.step();
            b.target.step();
            assertEquals(a.target.snapshot(), b.target.snapshot());
        }
    }

    @Test
    void unknownLevelsInStorageAreRestored() {
        Simulator sim = new Simulator();
        Clock clk = new Clock(sim, false);
        IBus in = Buses.simpleBus(sim, 4, 0);
        Register reg = new Register(sim, clk, in, null, false, "r");
        ProbeSet probes = new ProbeSet(sim);
        probes.add("r", reg.getOutputBus());
        ClockedTarget t = new ClockedTarget("r", clk, probes);
        reg.force(1, LogicValue.X);
        reg.force(3, LogicValue.Z);
        sim.settle();
        Snapshot s = t.snapshot();
        assertEquals(0x2, s.value("state.r.X"));
        assertEquals(0x8, s.value("state.r.Z"));
        reg.force(1, LogicValue.ZERO);
        reg.force(3, LogicValue.ONE);
        sim.settle();
        t.restore(s);
        assertEquals(LogicValue.X, reg.get(1));
        assertEquals(LogicValue.Z, reg.get(3));
    }

    @Test
    void snapshotsOfOtherCircuitsAreRejectedBeforeAnythingChanges() {
        RandomDatapath a = new RandomDatapath();
        a.target.step();
        Snapshot before = a.target.snapshot();
        Snapshot otherMachine = Snapshot.builder("other").build();
        assertThrows(IllegalArgumentException.class, () -> a.target.restore(otherMachine));
        Snapshot.Builder missing = Snapshot.builder("dp");
        before.fields().stream().filter(f -> !f.name().equals("state.dp.flag_Z")).forEach(missing::field);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> a.target.restore(missing.build()));
        assertTrue(e.getMessage().contains("state.dp.flag_Z"), e.getMessage());
        Snapshot.Builder extra = Snapshot.builder("dp").fields(before.fields()).hex("state.dp.ghost", 1, 1);
        e = assertThrows(IllegalArgumentException.class, () -> a.target.restore(extra.build()));
        assertTrue(e.getMessage().contains("state.dp.ghost"), e.getMessage());
        assertEquals(before, a.target.snapshot(), "a rejected snapshot leaves the circuit untouched");
    }

    @Test
    void stateOutsideTheStorageElementsMakesRestoreFailLoudly() {
        // a NOR latch built from gates holds state that no storage element knows about
        Simulator sim = new Simulator();
        Clock clk = new Clock(sim, false);
        SimpleSignal set = new SimpleSignal(sim, false);
        SimpleSignal reset = new SimpleSignal(sim, true);
        Wire q = new Wire(sim);
        Wire qBar = new Wire(sim);
        q.connect(nor(sim, reset, qBar));
        qBar.connect(nor(sim, set, q));
        sim.settle();
        reset.setOn(false);
        sim.settle();
        ProbeSet probes = new ProbeSet(sim);
        probes.add("q", (ISignal) q);
        ClockedTarget t = new ClockedTarget("latch", clk, probes);
        t.setStimulus(cycle -> set.setOn(cycle == 2));
        t.step();
        Snapshot clear = t.snapshot();
        t.step();
        t.step();
        assertEquals(1, probes.value("q"), "the latch was set in cycle 2");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> t.restore(clear));
        assertTrue(e.getMessage().contains("restore is not lossless"), e.getMessage());
        assertTrue(e.getMessage().contains("q"), e.getMessage());
    }
}
