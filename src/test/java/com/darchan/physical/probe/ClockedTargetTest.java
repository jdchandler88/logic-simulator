package com.darchan.physical.probe;

import com.darchan.diag.Snapshot;
import com.darchan.diag.compare.GoldenTrace;
import com.darchan.diag.compare.GoldenTraceResult;
import com.darchan.diag.compare.Lockstep;
import com.darchan.diag.compare.LockstepResult;
import com.darchan.diag.run.Debugger;
import com.darchan.diag.run.StopReason;
import com.darchan.diag.trace.InMemoryTrace;
import com.darchan.physical.comp.Counter;
import com.darchan.physical.comp.Register;
import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.Clock;
import com.darchan.physical.signal.SimpleSignal;
import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.Wire;
import com.darchan.physical.sim.Simulator;
import com.darchan.testutil.Buses;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A gate-level 4-bit counter driven through the CPU-agnostic Debugger. */
class ClockedTargetTest {

    private final Simulator sim = new Simulator();
    private final Clock clk = new Clock(sim, false);
    private final Counter counter = new Counter(sim, clk, 4);
    private final ProbeSet probes = new ProbeSet(sim);
    private final ClockedTarget target;

    ClockedTargetTest() {
        sim.settle();
        probes.add("count", counter.getOutputBus());
        probes.add("next", counter.getNextState());
        target = new ClockedTarget("counter4", clk, probes);
    }

    @Test
    void aStepIsOneClockCycle() {
        assertEquals(1, target.step());
        assertEquals(1, target.cycleCount());
        assertEquals(1, target.instructionCount());
        assertEquals(1, probes.value("count"));
        assertFalse(clk.isOn(), "the clock is low again after a step");
        for (int i = 0; i < 20; i++) {
            target.step();
        }
        assertEquals(21 % 16, probes.value("count"));
    }

    @Test
    void snapshotsHoldProbesStorageAndCounters() {
        target.step();
        target.step();
        Snapshot s = target.snapshot();
        assertEquals("counter4", s.machine());
        assertEquals(List.of("count", "next", "state.counter0", "CYCLES", "INSTRUCTIONS"), s.fieldNames());
        assertEquals(2, s.value("count"));
        assertEquals(3, s.value("next"));
        assertEquals(2, s.value("state.counter0"));
        assertEquals(2, s.value("CYCLES"));
        assertEquals(2, s.value("INSTRUCTIONS"));
        target.step();
        target.restore(s);
        assertEquals(s, target.snapshot());
    }

    @Test
    void theDebuggerRunsAGateLevelCircuit() {
        target.setProgramCounter(() -> (int) probes.value("count"));
        Debugger dbg = new Debugger(target, null);
        InMemoryTrace trace = new InMemoryTrace();
        dbg.addTraceListener(trace);
        dbg.addBreakpoint(5);
        StopReason r = dbg.run(1000);
        assertEquals(StopReason.Kind.BREAKPOINT, r.kind());
        assertEquals(5, target.cycleCount());
        assertEquals(5, trace.lines().size());
        assertEquals("000000000000 000000000000 0000:     CLK            count=0 next=1", trace.lines().get(0));
        assertEquals("000000000004 000000000004 0004:     CLK            count=4 next=5", trace.lines().get(4));
        assertTrue(dbg.ringBuffer().dump().contains("(counter4)"));
    }

    @Test
    void theStimulusDrivesInputsBeforeEachRisingEdge() {
        Simulator s2 = new Simulator();
        Clock c2 = new Clock(s2, false);
        IBus in = Buses.simpleBus(s2, 4, 0);
        Register reg = new Register(s2, c2, in, false);
        ProbeSet p = new ProbeSet(s2);
        p.add("in", in);
        p.add("reg", reg.getOutputBus());
        ClockedTarget t = new ClockedTarget("reg4", c2, p);
        t.setStimulus(cycle -> Buses.drive(in, (int) (cycle * 3) & 0xF));
        t.step();
        assertEquals(0, p.value("reg"));
        t.step();
        assertEquals(3, p.value("reg"));
        t.step();
        assertEquals(6, p.value("reg"));
    }

    @Test
    void theClockMustStartLow() {
        Simulator s2 = new Simulator();
        assertThrows(IllegalArgumentException.class, () -> new ClockedTarget("x", new Clock(s2, true), new ProbeSet(s2)));
    }
    /** a 4-bit register loading a bus whose low nibble is driven and whose high bit floats or fights */
    private static final class UnknownBits {
        final Simulator sim = new Simulator();
        final Clock clk = new Clock(sim, false);
        final SimpleSignal[] drivers = new SimpleSignal[]{new SimpleSignal(sim, true), new SimpleSignal(sim, false),
                new SimpleSignal(sim, false), new SimpleSignal(sim, false)};
        final Wire[] bus = {new Wire(sim), new Wire(sim), new Wire(sim), new Wire(sim)};
        final ProbeSet probes = new ProbeSet(sim);
        final ClockedTarget target;

        UnknownBits() {
            for (int i = 0; i < 3; i++) {
                bus[i].connect(drivers[i]);
            }
            // bus[3] has no driver until the stimulus adds two fighting ones
            Register reg = new Register(sim, clk, new Bus(bus), null, false, "r");
            sim.settle();
            probes.add("B", new Bus(bus));
            probes.add("R", reg.getOutputBus());
            target = new ClockedTarget("bits", clk, probes);
        }
    }

    @Test
    void traceColumnsShowZAndX() {
        UnknownBits c = new UnknownBits();
        InMemoryTrace trace = new InMemoryTrace();
        new Debugger(c.target, null).addTraceListener(trace);
        c.target.step();
        c.target.step();
        // the bus's top bit floats: 4 bits, one digit, mixed known/Z: '?'; the register latched it as X
        assertEquals("000000000000 000000000000 0000:     CLK            B=? R=0", trace.lines().get(0));
        assertEquals("000000000001 000000000001 0000:     CLK            B=? R=?", trace.lines().get(1));
        assertEquals(0x8, c.target.snapshot().value("R.X"));
    }

    @Test
    void aGoldenZeroAgainstALiveXIsAMismatch() {
        UnknownBits c = new UnknownBits();
        for (int i = 0; i < 3; i++) {
            c.bus[3].connect(c.drivers[i]); // drives 1, 0, 0: conflicting, so X
        }
        c.sim.settle();
        String golden = "000000000000 000000000000 0000:     CLK            B=1 R=0\n";
        GoldenTraceResult r = GoldenTrace.ofText("golden", golden).compare(new Debugger(c.target, null), 10);
        assertFalse(r.matched(), r::report);
        assertTrue(r.mismatch().differingColumns().contains("B: expected 1, actual ?"),
                r.mismatch().differingColumns().toString());
        // and the state a run stops in is rendered the same way when compared with the next golden line
        UnknownBits d = new UnknownBits();
        Debugger dbg = new Debugger(d.target, null);
        dbg.step(1);
        GoldenTraceResult stopped = GoldenTrace.ofText("golden",
                "000000000001 000000000001 0000:     CLK            B=1 R=1\n").compare(dbg, 0);
        assertFalse(stopped.matched(), stopped::report);
        assertTrue(stopped.mismatch().differingColumns().contains("R: expected 1, actual ?"),
                stopped.mismatch().differingColumns().toString());
    }

    @Test
    void lockstepCompareOnlySeesAFloatingBus() {
        // reference: a bus driven to 0000; candidate: a bus nobody drives (all Z, which reads as 0000)
        Simulator refSim = new Simulator();
        Clock refClk = new Clock(refSim, false);
        ProbeSet refProbes = new ProbeSet(refSim);
        refProbes.add("A", Buses.simpleBus(refSim, 4, 0));
        refSim.settle();
        Simulator candSim = new Simulator();
        Clock candClk = new Clock(candSim, false);
        ProbeSet candProbes = new ProbeSet(candSim);
        candProbes.add("A", Wire.createBus(candSim, 4));
        candSim.settle();
        LockstepResult r = Lockstep.builder("driven", new Debugger(new ClockedTarget("driven", refClk, refProbes), null),
                        "floating", new Debugger(new ClockedTarget("floating", candClk, candProbes), null))
                .compareOnly("A").build().run(5);
        assertTrue(r.diverged(), "a floating bus must not compare equal to 0000: " + r.report());
        assertEquals("A.Z", r.divergence().diff().fieldDifferences().get(0).name(), r::report);
        assertTrue(r.report().contains("expected=<missing>  actual=F"), r::report);
    }
}
