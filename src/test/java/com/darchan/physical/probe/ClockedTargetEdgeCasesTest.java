package com.darchan.physical.probe;

import com.darchan.diag.Field;
import com.darchan.diag.Snapshot;
import com.darchan.diag.trace.InMemoryTrace;
import com.darchan.diag.trace.RegisterSpec;
import com.darchan.diag.trace.TraceLayout;
import com.darchan.diag.run.Debugger;
import com.darchan.physical.comp.Counter;
import com.darchan.physical.comp.Register;
import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.Clock;
import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.LogicValue;
import com.darchan.physical.signal.SimpleSignal;
import com.darchan.physical.sim.Simulator;
import com.darchan.testutil.Buses;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Name, width and consistency checks of {@link ClockedTarget}, its trace layout and restore failures. */
class ClockedTargetEdgeCasesTest {

    private final Simulator sim = new Simulator();
    private final Clock clk = new Clock(sim, false);
    private final IBus in = Buses.simpleBus(sim, 4, 0);
    private final Register reg = new Register(sim, clk, in, null, false, "r");
    private final ProbeSet probes = new ProbeSet(sim);
    private final ClockedTarget target;

    ClockedTargetEdgeCasesTest() {
        sim.settle();
        probes.add("in", in);
        probes.add("r", reg.getOutputBus());
        target = new ClockedTarget("m", clk, probes);
    }

    // ---- names and widths ------------------------------------------------------------------------------------------

    @Test
    void reservedProbeNamesAreRejectedAtConstructionAndAtSnapshotTime() {
        for (String reserved : new String[]{"CYCLES", "INSTRUCTIONS", "state.x", "tb.x", "in.x"}) {
            Simulator s = new Simulator();
            ProbeSet p = new ProbeSet(s);
            p.add(reserved, new SimpleSignal(s, false));
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new ClockedTarget("m", new Clock(s, false), p), reserved);
            assertTrue(e.getMessage().contains(reserved), e.getMessage());
        }
        // names that merely contain or resemble them are fine
        Simulator s = new Simulator();
        ProbeSet p = new ProbeSet(s);
        p.add("CYCLES2", new SimpleSignal(s, false));
        p.add("state", new SimpleSignal(s, false));
        p.add("tbx", new SimpleSignal(s, false));
        p.add("input", new SimpleSignal(s, false));
        new ClockedTarget("m", new Clock(s, false), p);
        // a probe added after construction is checked when a snapshot is taken
        Simulator s2 = new Simulator();
        Clock c2 = new Clock(s2, false);
        ProbeSet p2 = new ProbeSet(s2);
        ClockedTarget t2 = new ClockedTarget("m", c2, p2);
        p2.add("CYCLES", new SimpleSignal(s2, false));
        IllegalArgumentException late = assertThrows(IllegalArgumentException.class, t2::snapshot);
        assertTrue(late.getMessage().contains("a probe may not be called CYCLES"), late.getMessage());
    }

    @Test
    void testbenchStateNeedsAValidUniqueNameAndWidth() {
        AtomicLong v = new AtomicLong();
        assertThrows(IllegalArgumentException.class, () -> target.addState("bad name", 8, v::get, v::set));
        assertThrows(IllegalArgumentException.class, () -> target.addState("", 8, v::get, v::set));
        assertThrows(IllegalArgumentException.class, () -> target.addState("pc", 0, v::get, v::set));
        assertThrows(IllegalArgumentException.class, () -> target.addState("pc", 65, v::get, v::set));
        assertThrows(NullPointerException.class, () -> target.addState("pc", 8, null, v::set));
        assertThrows(NullPointerException.class, () -> target.addState("pc", 8, v::get, null));
        target.addState("pc", 1, v::get, v::set);
        target.addState("wide", 64, v::get, v::set);
        assertThrows(IllegalArgumentException.class, () -> target.addState("pc", 8, v::get, v::set), "duplicate");
        Snapshot s = target.snapshot();
        assertEquals(1, s.field("tb.pc").orElseThrow().width());
        assertEquals(64, s.field("tb.wide").orElseThrow().width());
    }

    @Test
    void inputsMustBeUniqueSimpleSignalsOfAValidWidth() {
        target.addInputs("ctl", in);
        assertThrows(IllegalArgumentException.class, () -> target.addInputs("ctl", in), "duplicate");
        assertThrows(IllegalArgumentException.class, () -> target.addInputs("bad name", in));
        Simulator s = new Simulator();
        IBus gates = new Bus(com.darchan.physical.logic.Gates.not(s, new SimpleSignal(s, false)));
        assertThrows(IllegalArgumentException.class, () -> target.addInputs("gate", gates));
        assertThrows(IllegalArgumentException.class, () -> target.addInputs("none", new Bus()));
        assertEquals(List.of("in", "r", "state.r", "in.ctl", "CYCLES", "INSTRUCTIONS"), target.snapshot().fieldNames());
    }

    @Test
    void severalTestbenchStatesAndInputsAreEachSavedAndRestored() {
        AtomicLong first = new AtomicLong(0x11);
        AtomicLong second = new AtomicLong(0x22);
        IBus other = Buses.simpleBus(sim, 3, 0b101);
        target.addState("one", 8, first::get, first::set);
        target.addState("two", 8, second::get, second::set);
        target.addInputs("a", in);
        target.addInputs("b", other);
        Buses.drive(in, 0b0110);
        sim.settle();
        Snapshot s = target.snapshot();
        assertEquals(0x11, s.value("tb.one"));
        assertEquals(0x22, s.value("tb.two"));
        assertEquals(0b0110, s.value("in.a"));
        assertEquals(0b101, s.value("in.b"));
        first.set(0);
        second.set(0);
        Buses.drive(in, 0);
        Buses.drive(other, 0);
        sim.settle();
        target.restore(s);
        assertEquals(0x11, first.get());
        assertEquals(0x22, second.get());
        assertEquals(0b0110, Buses.toInt(in));
        assertEquals(0b101, Buses.toInt(other));
    }

    @Test
    void instructionLengthsAreOneToFour() {
        for (int bad : new int[]{0, 5, -1}) {
            assertThrows(IllegalArgumentException.class,
                    () -> target.setInstruction(() -> 0, () -> 1, bad, (a, b, l) -> "X"), "max length " + bad);
        }
        target.setInstruction(() -> 0x12, () -> 1, 1, (a, b, l) -> "X");
        target.setInstruction(() -> 0x1234, () -> 2, 4, (a, b, l) -> "X");
        assertEquals(4, target.traceLayout().maxInstructionLength());
        assertThrows(NullPointerException.class, () -> target.setInstruction(null, () -> 1, 1, (a, b, l) -> "X"));
    }

    // ---- trace layout ---------------------------------------------------------------------------------------------------

    @Test
    void theTraceLayoutFollowsTheSettings() {
        TraceLayout layout = target.traceLayout();
        assertEquals("m", layout.cpu());
        assertEquals(16, layout.pcWidth());
        assertEquals(1, layout.maxInstructionLength());
        assertTrue(layout.unknownBits());
        assertEquals(List.of("in", "r"), layout.registers().stream().map(RegisterSpec::name).toList());
        assertEquals(List.of(4, 4), layout.registers().stream().map(RegisterSpec::width).toList());
        assertSame(layout, target.traceLayout(), "the layout is built once");

        target.setPcWidth(8);
        assertEquals(8, target.traceLayout().pcWidth());
        target.setInstruction(() -> 0xBEEF, () -> 2, 3, (pc, bytes, len) -> "DEC");
        assertEquals(3, target.traceLayout().maxInstructionLength());
        target.setTraceRegisters(List.of(RegisterSpec.of("r", 4)));
        assertEquals(1, target.traceLayout().registers().size());
        target.setProgramCounter(() -> 0x7B);
        InMemoryTrace trace = new InMemoryTrace();
        new Debugger(target, null).addTraceListener(trace);
        target.step();
        assertEquals("000000000000 000000000000 7B: EF BE     DEC            r=0", trace.lines().get(0));
    }

    @Test
    void traceRegistersMustMatchProbeWidths() {
        assertThrows(IllegalArgumentException.class, () -> target.setTraceRegisters(List.of(RegisterSpec.of("r", 5))));
        assertThrows(IllegalArgumentException.class, () -> target.setTraceRegisters(List.of(RegisterSpec.of("nope", 4))));
        target.setTraceRegisters(List.of(RegisterSpec.of("r", 4), RegisterSpec.flags("in", "SZ.C")));
        assertEquals(2, target.traceLayout().registers().size());
    }

    @Test
    void onlyProbesOfAtMost32BitsAreTracedByDefault() {
        Simulator s = new Simulator();
        ProbeSet p = new ProbeSet(s);
        p.add("narrow", Buses.simpleBus(s, 32, 0));
        p.add("wide", Buses.simpleBus(s, 33, 0));
        ClockedTarget t = new ClockedTarget("w", new Clock(s, false), p);
        assertEquals(List.of("narrow"), t.traceLayout().registers().stream().map(RegisterSpec::name).toList());
    }

    @Test
    void accessorsReturnWhatWasConfigured() {
        assertSame(probes, target.probes());
        assertSame(clk, target.clock());
        assertEquals(0, target.programCounter());
        target.setProgramCounter(() -> 42);
        assertEquals(42, target.programCounter());
        assertTrue(target.toString().startsWith("m @ cycle 0: "), target.toString());
        target.step();
        assertTrue(target.toString().startsWith("m @ cycle 1: "), target.toString());
        assertTrue(target.isSnapshotComplete());
    }

    @Test
    void cyclesPerStepChargesTheCountButTheStepCountStaysOne() {
        int[] cost = {3};
        target.setCyclesPerStep(() -> cost[0]);
        assertEquals(3, target.step());
        assertEquals(3, target.cycleCount());
        assertEquals(1, target.instructionCount());
        cost[0] = 1;
        assertEquals(1, target.step());
        assertEquals(4, target.cycleCount());
        cost[0] = 0;
        assertThrows(IllegalStateException.class, target::step);
        assertEquals(2, target.instructionCount(), "a rejected step does not run");
        assertThrows(NullPointerException.class, () -> target.setCyclesPerStep(null));
        assertThrows(NullPointerException.class, () -> target.setStimulus(null));
        assertThrows(NullPointerException.class, () -> target.setProgramCounter(null));
    }

    // ---- restore failures ----------------------------------------------------------------------------------------------------

    private Snapshot.Builder copyOf(Snapshot s) {
        return Snapshot.builder(s.machine()).fields(s.fields());
    }

    private Snapshot without(Snapshot s, String name) {
        Snapshot.Builder b = Snapshot.builder(s.machine());
        s.fields().stream().filter(f -> !f.name().equals(name)).forEach(b::field);
        return b.build();
    }

    private Snapshot replacing(Snapshot s, Field f) {
        Snapshot.Builder b = Snapshot.builder(s.machine());
        s.fields().forEach(old -> b.field(old.name().equals(f.name()) ? f : old));
        return b.build();
    }

    @Test
    void aSnapshotWithTheWrongShapeIsRejectedAndLeavesTheCircuitAlone() {
        target.step();
        Snapshot ok = target.snapshot();
        Snapshot other = Snapshot.builder("other").fields(ok.fields()).build();
        assertThrows(IllegalArgumentException.class, () -> target.restore(other));
        assertThrows(IllegalArgumentException.class, () -> target.restore(without(ok, "state.r")));
        assertThrows(IllegalArgumentException.class, () -> target.restore(without(ok, "CYCLES")));
        // a width that does not match the storage element
        assertThrows(IllegalArgumentException.class,
                () -> target.restore(replacing(ok, Field.hex("state.r", 8, ok.value("state.r")))));
        // masks of the wrong width
        assertThrows(IllegalArgumentException.class,
                () -> target.restore(copyOf(ok).hex("state.r.X", 8, 1).build()));
        assertThrows(IllegalArgumentException.class,
                () -> target.restore(copyOf(ok).hex("state.r.Z", 8, 1).build()));
        // overlapping value / Z / X masks
        assertThrows(IllegalArgumentException.class,
                () -> target.restore(copyOf(ok).hex("state.r.X", 4, 1).hex("state.r.Z", 4, 1).build()),
                "a bit cannot be both Z and X");
        Snapshot ones = replacing(ok, Field.hex("state.r", 4, 1));
        assertThrows(IllegalArgumentException.class,
                () -> target.restore(Snapshot.builder("m").fields(ones.fields()).hex("state.r.X", 4, 1).build()),
                "a bit cannot be both 1 and X");
        assertThrows(IllegalArgumentException.class,
                () -> target.restore(Snapshot.builder("m").fields(ones.fields()).hex("state.r.Z", 4, 1).build()),
                "a bit cannot be both 1 and Z");
        // the circuit did not change
        assertEquals(ok, target.snapshot());
    }

    @Test
    void negativeCountersAreRejected() {
        target.step();
        Snapshot ok = target.snapshot();
        assertThrows(IllegalArgumentException.class, () -> target.restore(replacing(ok, Field.dec("CYCLES", -1))));
        assertThrows(IllegalArgumentException.class, () -> target.restore(replacing(ok, Field.dec("INSTRUCTIONS", -1))));
        target.restore(replacing(replacing(ok, Field.dec("CYCLES", 0)), Field.dec("INSTRUCTIONS", 0)));
        assertEquals(0, target.cycleCount());
        assertEquals(0, target.instructionCount());
    }

    @Test
    void testbenchStateAndInputsAreCheckedAndRestored() {
        AtomicLong v = new AtomicLong(5);
        target.addState("pc", 8, v::get, v::set);
        target.addInputs("ctl", in);
        Buses.drive(in, 0b1010);
        ((SimpleSignal) in.getSignal(0)).setValue(LogicValue.Z);
        sim.settle();
        Snapshot s = target.snapshot();
        assertEquals(5, s.value("tb.pc"));
        assertEquals(0b1010 & ~1, s.value("in.ctl"));
        assertEquals(1, s.value("in.ctl.Z"));
        v.set(77);
        Buses.drive(in, 0b0101);
        sim.settle();
        target.restore(s);
        assertEquals(5, v.get());
        assertEquals(LogicValue.Z, in.getSignal(0).getValue());
        assertEquals(LogicValue.ONE, in.getSignal(3).getValue());
        // missing or mis-sized testbench state / input fields
        assertThrows(IllegalArgumentException.class, () -> target.restore(without(s, "tb.pc")));
        assertThrows(IllegalArgumentException.class,
                () -> target.restore(replacing(s, Field.hex("tb.pc", 16, 5))));
        assertThrows(IllegalArgumentException.class, () -> target.restore(without(s, "in.ctl")));
        assertThrows(IllegalArgumentException.class,
                () -> target.restore(replacing(s, Field.hex("in.ctl", 8, 0))));
        assertThrows(IllegalArgumentException.class,
                () -> target.restore(Snapshot.builder("m").fields(s.fields()).hex("state.ghost", 1, 0).build()));
    }

    @Test
    void aLosslessRestoreChecksEveryProbeMask() {
        // a probe on a signal that is not part of any storage element and not an input: restore cannot bring it back
        Simulator s = new Simulator();
        Clock c = new Clock(s, false);
        SimpleSignal hidden = new SimpleSignal(s, false);
        Counter counter = new Counter(s, c, 2);
        ProbeSet p = new ProbeSet(s);
        p.add("hidden", hidden);
        p.add("count", counter.getOutputBus());
        s.settle();
        ClockedTarget t = new ClockedTarget("h", c, p);
        Snapshot before = t.snapshot();
        hidden.setOn(true);
        s.settle();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> t.restore(before));
        assertTrue(e.getMessage().contains("hidden expected=0 actual=1"), e.getMessage());
        // an X on the hidden probe differs in its mask, not its value
        hidden.setOn(false);
        s.settle();
        Snapshot clean = t.snapshot();
        hidden.setValue(LogicValue.X);
        s.settle();
        IllegalStateException x = assertThrows(IllegalStateException.class, () -> t.restore(clean));
        assertTrue(x.getMessage().contains("hidden.X expected=<none> actual=1"), x.getMessage());
    }
}
