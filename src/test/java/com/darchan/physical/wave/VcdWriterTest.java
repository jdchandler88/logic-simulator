package com.darchan.physical.wave;

import com.darchan.physical.comp.Counter;
import com.darchan.physical.logic.InverterLogicUnit;
import com.darchan.physical.probe.ProbeSet;
import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.Clock;
import com.darchan.physical.signal.LogicSignal;
import com.darchan.physical.signal.LogicValue;
import com.darchan.physical.signal.SimpleSignal;
import com.darchan.physical.sim.Simulator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VcdWriterTest {

    private final Simulator sim = new Simulator();
    private final Clock clk = new Clock(sim, false);
    private final Counter counter = new Counter(sim, clk, 4);
    private final ProbeSet probes = new ProbeSet(sim);

    VcdWriterTest() {
        sim.settle();
        probes.add("clk", clk);
        probes.add("counter.q", counter.getOutputBus());
        probes.add("counter.next", counter.getNextState());
    }

    @Test
    void settleTimingGivesOneTickPerClockEdge(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("counter.vcd");
        try (VcdWriter vcd = VcdWriter.builder(probes).topScope("tb").open(file)) {
            assertEquals(VcdWriter.Timing.SETTLE, vcd.timing());
            for (int i = 0; i < 40; i++) {
                clk.tick();
            }
        }
        VcdReader r = new VcdReader(Files.readString(file));
        assertEquals("1ns", r.timescale);
        assertEquals(List.of("tb", "tb.counter"), r.scopes);
        assertEquals(List.of("tb.clk", "tb.counter.q", "tb.counter.next"), List.copyOf(r.varsByName.keySet()));
        assertEquals(1, r.varsByName.get("tb.clk").width());
        assertEquals(4, r.varsByName.get("tb.counter.q").width());
        assertEquals(0, r.numberAt("tb.counter.q", 0));
        for (int tick = 1; tick <= 40; tick++) {
            assertEquals(tick % 2, r.numberAt("tb.clk", tick), "clock at tick " + tick);
            assertEquals((tick + 1) / 2 % 16, r.numberAt("tb.counter.q", tick), "count at tick " + tick);
            assertEquals(((tick + 1) / 2 + 1) % 16, r.numberAt("tb.counter.next", tick));
        }
        assertEquals(41, r.endTime(), "a final time stamp gives the last values a width");
        String text = Files.readString(file);
        assertTrue(text.contains("$var wire 4 \" q [3:0] $end"), text);
        assertTrue(text.contains("#3\n1!\nb0010 \"\nb0011 #\n"), text);
    }

    @Test
    void deltaTimingShowsTheRippleInsideASettle() {
        StringWriter out = new StringWriter();
        long startDelta = sim.deltaCycle();
        VcdWriter vcd = VcdWriter.builder(probes).timing(VcdWriter.Timing.DELTA).to(out);
        for (int i = 0; i < 16; i++) { // 8 rising edges: 0 -> 8
            clk.tick();
        }
        vcd.close();
        VcdReader r = new VcdReader(out.toString());
        assertEquals(startDelta, r.timestamps.get(0));
        // the clock rises in one delta, the count changes a delta later, the next-state logic ripples after that
        assertTrue(r.timestamps.size() > 2 * 16, "more distinct times than settles: " + r.timestamps.size());
        assertEquals(8, r.numberAt("top.counter.q", r.endTime()));
        // 7 -> 8 flips all four bits at once (they are flip-flops on one clock)...
        long qChange = r.changes("top.counter.q").lastKey();
        assertEquals("1000", r.valueAt("top.counter.q", qChange));
        assertEquals("0111", r.valueAt("top.counter.q", qChange - 1));
        // ...but the next state reaches 1001 only after the gates have settled, some deltas later
        long nextSettled = r.changes("top.counter.next").lastKey();
        assertEquals("1001", r.valueAt("top.counter.next", nextSettled));
        assertTrue(nextSettled > qChange, "next state settles after the flip-flops switch");
        long rise = r.changes("top.clk").headMap(qChange, false).lastKey();
        assertEquals("1", r.valueAt("top.clk", rise));
        assertEquals(qChange - 1, rise, "the flip-flops switch one delta after the clock edge");
    }

    @Test
    void simulatedTimeWithTransportDelays() {
        Simulator s = new Simulator();
        SimpleSignal in = new SimpleSignal(s, false);
        LogicSignal out = new LogicSignal(s, new Bus(in), new InverterLogicUnit(), 5);
        s.runUntil(20);
        ProbeSet p = new ProbeSet(s);
        p.add("in", in);
        p.add("out", out);
        StringWriter text = new StringWriter();
        VcdWriter vcd = VcdWriter.builder(p).timing(VcdWriter.Timing.SIM_TIME).timescale("10 ps").to(text);
        in.setOn(true);
        s.runUntil(40);
        in.setOn(false);
        s.runUntil(60);
        vcd.close();
        VcdReader r = new VcdReader(text.toString());
        assertEquals("10ps", r.timescale);
        assertEquals("1", r.valueAt("top.out", 20));
        assertEquals("1", r.valueAt("top.in", 20));
        assertEquals("0", r.valueAt("top.out", 25), "inverter output follows 5 time units later");
        assertEquals("0", r.valueAt("top.in", 40));
        assertEquals("1", r.valueAt("top.out", 45));
        assertEquals(List.of(20L, 25L, 40L, 45L, 60L), r.timestamps);
    }

    @Test
    void highImpedanceAndUnknownAreWrittenAsZAndX() {
        Simulator s = new Simulator();
        SimpleSignal a = new SimpleSignal(s, LogicValue.Z);
        SimpleSignal b0 = new SimpleSignal(s, true);
        SimpleSignal b1 = new SimpleSignal(s, LogicValue.X);
        SimpleSignal b2 = new SimpleSignal(s, LogicValue.Z);
        ProbeSet p = new ProbeSet(s);
        p.add("bus.en", a);
        p.add("bus.data", new Bus(b0, b1, b2));
        StringWriter text = new StringWriter();
        VcdWriter vcd = VcdWriter.builder(p).to(text);
        a.setValue(LogicValue.X);
        b2.setValue(LogicValue.ONE);
        s.settle();
        vcd.close();
        VcdReader r = new VcdReader(text.toString());
        assertEquals("z", r.valueAt("top.bus.en", 0));
        assertEquals("zx1", r.valueAt("top.bus.data", 0));
        assertEquals("x", r.valueAt("top.bus.en", 1));
        assertEquals("1x1", r.valueAt("top.bus.data", 1));
    }

    @Test
    void onlyChosenProbesAreRecordedAndClosingDetaches() {
        StringWriter text = new StringWriter();
        VcdWriter vcd = VcdWriter.builder(probes).probes("counter.q").to(text);
        clk.tick();
        clk.tick();
        vcd.close();
        String before = text.toString();
        clk.tick();
        assertEquals(before, text.toString(), "no output after close");
        VcdReader r = new VcdReader(before);
        assertEquals(List.of("top.counter.q"), List.copyOf(r.varsByName.keySet()));
        assertEquals(1, vcd.changesWritten());
        assertThrows(IllegalArgumentException.class, () -> VcdWriter.builder(probes).probes("nope").to(new StringWriter()));
        assertThrows(IllegalArgumentException.class, () -> VcdWriter.builder(probes).timescale("3ns"));
    }

    @Test
    void identifiersArePrintableAndUnique() {
        assertEquals("!", VcdWriter.identifier(0));
        assertEquals("~", VcdWriter.identifier(93));
        assertEquals("!!", VcdWriter.identifier(94));
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            String id = VcdWriter.identifier(i);
            assertTrue(id.chars().allMatch(c -> c >= 33 && c <= 126));
            assertTrue(seen.add(id), id);
        }
    }
    @Test
    void aBadProbeNameNeitherCreatesNorLeaksTheFile(@TempDir Path dir) throws IOException {
        Path fds = Path.of("/proc/self/fd");
        long before = Files.isDirectory(fds) ? countEntries(fds) : -1;
        for (int i = 0; i < 50; i++) {
            Path f = dir.resolve("w" + i + ".vcd");
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> VcdWriter.builder(probes).probes("nope").open(f));
            assertTrue(e.getMessage().contains("no probe nope"), e.getMessage());
            assertFalse(Files.exists(f), "validated before the file is created");
        }
        if (before >= 0) {
            assertTrue(countEntries(fds) < before + 50, "no descriptor leaked per failed open");
        }
        assertThrows(IllegalArgumentException.class,
                () -> VcdWriter.builder(new ProbeSet(new Simulator())).open(dir.resolve("empty.vcd")));
        assertFalse(Files.exists(dir.resolve("empty.vcd")));
    }

    private static long countEntries(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.count();
        }
    }
}
