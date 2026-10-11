package com.darchan.physical.wave;

import com.darchan.physical.probe.ProbeSet;
import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.SimpleSignal;
import com.darchan.physical.sim.Simulator;
import com.darchan.testutil.Buses;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Exact output shapes, option checks and lifecycle of the VCD writer. */
class VcdWriterEdgeCasesTest {

    private final Simulator sim = new Simulator();
    private final SimpleSignal bit = new SimpleSignal(sim, false);
    private final com.darchan.physical.signal.IBus pair = Buses.simpleBus(sim, 2, 0);
    private final ProbeSet probes = new ProbeSet(sim);

    VcdWriterEdgeCasesTest() {
        probes.add("a", bit);
        probes.add("m.b", pair);
    }

    @Test
    void theHeaderHasDateVersionTimescaleScopesAndInitialValues() {
        StringWriter out = new StringWriter();
        VcdWriter vcd = VcdWriter.builder(probes).date("some day").timescale("10 ps").topScope("tb")
                .timing(VcdWriter.Timing.SIM_TIME).to(out);
        vcd.close();
        assertEquals("$date\n  some day\n$end\n"
                + "$version\n  logic-simulator VcdWriter\n$end\n"
                + "$comment\n  time mapping: one tick per simulated time unit\n$end\n"
                + "$timescale 10 ps $end\n"
                + "$scope module tb $end\n"
                + "$var wire 1 ! a $end\n"
                + "$scope module m $end\n"
                + "$var wire 2 \" b [1:0] $end\n"
                + "$upscope $end\n"
                + "$upscope $end\n"
                + "$enddefinitions $end\n"
                + "#0\n$dumpvars\n0!\nb00 \"\n$end\n#1\n", out.toString());
    }

    @Test
    void withoutADateThereIsNoDateSection() {
        StringWriter out = new StringWriter();
        VcdWriter.builder(probes).to(out).close();
        assertFalse(out.toString().contains("$date"), out.toString());
        assertTrue(out.toString().startsWith("$version\n"), out.toString());
        assertTrue(out.toString().contains("one tick per settle()/runUntil() call"), out.toString());
        StringWriter delta = new StringWriter();
        VcdWriter.builder(probes).timing(VcdWriter.Timing.DELTA).to(delta).close();
        assertTrue(delta.toString().contains("one tick per delta cycle"), delta.toString());
    }

    @Test
    void scalarsAndVectorsHaveTheirOwnValueSyntax() {
        StringWriter out = new StringWriter();
        VcdWriter vcd = VcdWriter.builder(probes).to(out);
        bit.setOn(true);
        Buses.drive(pair, 2);
        sim.settle();
        vcd.close();
        String text = out.toString();
        assertTrue(text.contains("#1\n1!\nb10 \"\n"), text);
        assertEquals(2, vcd.changesWritten());
    }

    @Test
    void aValueThatChangesAndChangesBackInOneTickIsNotWritten() {
        StringWriter out = new StringWriter();
        VcdWriter vcd = VcdWriter.builder(probes).timing(VcdWriter.Timing.DELTA).to(out);
        bit.setOn(true);
        sim.settle();     // delta 1: a = 1
        bit.setOn(false);
        sim.settle();     // delta 2: a = 0 again
        vcd.close();
        assertEquals(2, vcd.changesWritten());
        // with settle timing both happen in different settles too; now put them in one tick
        StringWriter second = new StringWriter();
        Simulator s2 = new Simulator();
        SimpleSignal x = new SimpleSignal(s2, false);
        ProbeSet p2 = new ProbeSet(s2);
        p2.add("x", x);
        VcdWriter v2 = VcdWriter.builder(p2).timing(VcdWriter.Timing.SIM_TIME).to(second);
        s2.scheduleAfter(5, () -> x.setOn(true));
        s2.scheduleAfter(5, () -> x.setOn(false));
        s2.runUntil(10);
        v2.close();
        assertEquals(0, v2.changesWritten(), "the net never changed value");
        assertFalse(second.toString().contains("#5"), second.toString());
    }

    @Test
    void timeStampsAreWrittenOnlyWhenTimeMovesAndTheLastTimeIsReported() {
        StringWriter out = new StringWriter();
        VcdWriter vcd = VcdWriter.builder(probes).timing(VcdWriter.Timing.SIM_TIME).to(out);
        assertEquals(0, vcd.lastTime());
        sim.scheduleAfter(7, () -> bit.setOn(true));
        sim.runUntil(7);
        assertEquals(7, vcd.lastTime());
        sim.runUntil(20);
        assertEquals(7, vcd.lastTime(), "nothing changed after 7");
        vcd.close();
        assertTrue(out.toString().endsWith("#7\n1!\n#20\n"), out.toString());
        assertEquals(VcdWriter.Timing.SIM_TIME, vcd.timing());
    }

    @Test
    void closingFlushesPendingChangesDetachesAndIsIdempotent() {
        StringWriter out = new StringWriter();
        VcdWriter vcd = VcdWriter.builder(probes).timing(VcdWriter.Timing.SIM_TIME).to(out);
        // a change that has not been flushed by a run: the signal changes inside a listener-visible delta, but the
        // run has not completed when close() is called from the outside
        bit.setOn(true);
        sim.settle();
        long observersBefore = out.toString().length();
        vcd.close();
        String closed = out.toString();
        assertTrue(closed.length() > observersBefore, "the final time stamp was written");
        vcd.close(); // idempotent
        assertEquals(closed, out.toString());
        // once closed, the writer no longer listens to signals or to the simulator
        bit.setOn(false);
        Buses.drive(pair, 3);
        sim.settle();
        sim.runUntil(50);
        assertEquals(closed, out.toString());
        assertEquals(1, vcd.changesWritten());
    }

    @Test
    void pendingChangesAreWrittenAtCloseEvenWithoutAFinishedRun() {
        // SETTLE timing flushes at the end of a run; a change made after the last run is flushed by close()
        StringWriter out = new StringWriter();
        VcdWriter vcd = VcdWriter.builder(probes).to(out);
        bit.setOn(true);
        sim.settle();
        vcd.flush();
        assertTrue(out.toString().contains("1!"), "written when the settle completed");
        // schedule a change and let a delta commit it without completing a run: use a listener to close mid-run
        SimpleSignal trigger = new SimpleSignal(sim, false);
        trigger.addSignalChangeListener((o, n) -> {
            Buses.drive(pair, 1);
        });
        trigger.setOn(true);
        sim.settle();
        vcd.close();
        assertTrue(out.toString().contains("b01 \""), out.toString());
    }

    @Test
    void flushPushesBufferedTextToTheUnderlyingWriter() {
        StringWriter underlying = new StringWriter();
        VcdWriter vcd = VcdWriter.builder(probes).to(underlying);
        assertEquals("", underlying.toString(), "still in the writer's buffer");
        vcd.flush();
        assertTrue(underlying.toString().contains("$enddefinitions"), underlying.toString());
        vcd.close();
        // an already buffered writer is used as is
        BufferedWriter buffered = new BufferedWriter(new StringWriter());
        VcdWriter.builder(probes).to(buffered).close();
    }

    @Test
    void optionsAreValidated() {
        VcdWriter.Builder b = VcdWriter.builder(probes);
        assertSame(b, b.date("x"));
        assertSame(b, b.timescale("1ns"));
        assertSame(b, b.topScope("t"));
        assertSame(b, b.timing(VcdWriter.Timing.DELTA));
        for (String ok : new String[]{"1s", "10ms", "100 us", "1 ns", "10ps", "100fs"}) {
            b.timescale(ok);
        }
        for (String bad : new String[]{"2ns", "1", "1 hours", "ns", "1  ns", "1000ns", ""}) {
            assertThrows(IllegalArgumentException.class, () -> b.timescale(bad), bad);
        }
        for (String bad : new String[]{"", "a b", "a.b", "a-b", "é"}) {
            assertThrows(IllegalArgumentException.class, () -> b.topScope(bad), bad);
        }
        b.topScope("A_1");
        assertThrows(NullPointerException.class, () -> b.timing(null));
        assertThrows(IllegalArgumentException.class, () -> VcdWriter.builder(new ProbeSet(sim)).to(new StringWriter()));
        assertThrows(IllegalArgumentException.class, () -> VcdWriter.builder(probes).probes("nope").to(new StringWriter()));
    }

    @Test
    void theStaticOpenWritesAFileAndFailsCleanlyForABadPath(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("x.vcd");
        try (VcdWriter vcd = VcdWriter.open(file, probes, VcdWriter.Timing.DELTA)) {
            assertEquals(VcdWriter.Timing.DELTA, vcd.timing());
            bit.setOn(true);
            sim.settle();
        }
        String text = Files.readString(file);
        assertTrue(text.contains("one tick per delta cycle"), text);
        assertTrue(text.contains("1!"), text);
        assertThrows(UncheckedIOException.class, () -> VcdWriter.open(dir.resolve("no/dir/x.vcd"), probes,
                VcdWriter.Timing.SETTLE));
    }

    @Test
    void aWriterThatFailsIsReportedAsUnchecked() {
        boolean[] failing = {false};
        Writer broken = new Writer() {
            @Override
            public void write(char[] cbuf, int off, int len) throws IOException {
                if (failing[0]) {
                    throw new IOException("disk full");
                }
            }

            @Override
            public void flush() throws IOException {
                if (failing[0]) {
                    throw new IOException("disk full");
                }
            }

            @Override
            public void close() throws IOException {
                throw new IOException("close failed");
            }
        };
        VcdWriter vcd = VcdWriter.builder(probes).to(new BufferedWriter(broken));
        failing[0] = true;
        assertThrows(UncheckedIOException.class, vcd::flush);
        assertThrows(UncheckedIOException.class, vcd::close);
    }

    @Test
    void identifiersCoverAllNinetyFourPrintableCodesAndRollOver() {
        assertEquals("!", VcdWriter.identifier(0));
        assertEquals("\"", VcdWriter.identifier(1));
        assertEquals("~", VcdWriter.identifier(93));
        assertEquals("!!", VcdWriter.identifier(94));
        assertEquals("\"!", VcdWriter.identifier(95));
        assertEquals("~!", VcdWriter.identifier(94 + 93));
        assertEquals("!\"", VcdWriter.identifier(94 * 2));
        assertEquals("!!!", VcdWriter.identifier(94 + 94 * 94));
    }

    @Test
    void probesCanBeSelectedByName() {
        StringWriter out = new StringWriter();
        VcdWriter vcd = VcdWriter.builder(probes).probes("m.b").to(out);
        vcd.close();
        assertTrue(out.toString().contains("$var wire 2 ! b [1:0] $end"), out.toString());
        assertFalse(out.toString().contains(" a $end"), out.toString());
    }

    @Test
    void aBusWithOneBitIsAScalar() {
        Simulator s = new Simulator();
        ProbeSet p = new ProbeSet(s);
        p.add("one", new Bus(new SimpleSignal(s, true)));
        StringWriter out = new StringWriter();
        VcdWriter.builder(p).to(out).close();
        assertTrue(out.toString().contains("$var wire 1 ! one $end\n"), out.toString());
        assertTrue(out.toString().contains("$dumpvars\n1!\n$end"), out.toString());
    }
}
