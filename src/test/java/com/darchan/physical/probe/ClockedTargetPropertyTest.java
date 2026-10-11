package com.darchan.physical.probe;

import com.darchan.diag.Snapshot;
import com.darchan.physical.comp.i8080.AluDatapath;
import com.darchan.physical.comp.i8080.RegisterFile;
import com.darchan.physical.signal.Clock;
import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.LogicValue;
import com.darchan.physical.signal.SimpleSignal;
import com.darchan.physical.sim.Simulator;
import com.darchan.testutil.Buses;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Snapshot/restore of a gate-level {@link ClockedTarget} on random stimulus: the register-file datapath is driven
 * by a random program of control words (some control bits unknown, so X reaches the storage), run for a while,
 * snapshotted, run further and restored; the restore must bring back exactly the snapshotted state and the same
 * future.
 */
class ClockedTargetPropertyTest {

    /** one cycle's control inputs: the known levels, and the bits that are X instead */
    record Control(int word, int unknown) {
    }

    @Provide
    Arbitrary<List<Control>> program() {
        Arbitrary<Integer> word = Arbitraries.integers().between(0, (1 << 30) - 1);
        // sparse unknown bits (about 1 in 16) so that most state stays interesting
        Arbitrary<Integer> unknown = Combinators.combine(word, word, word, word)
                .as((a, b, c, d) -> a & b & c & d);
        return Combinators.combine(word, unknown).as(Control::new).list().ofMinSize(1).ofMaxSize(12);
    }

    private static final class Circuit {
        final Simulator sim = new Simulator();
        final Clock clk = new Clock(sim, false);
        final IBus controls = Buses.simpleBus(sim, 30, 0);
        final AluDatapath dp = new AluDatapath(sim, clk, controls, "dp");
        final ProbeSet probes = new ProbeSet(sim);
        final ClockedTarget target;

        Circuit(List<Control> program) {
            sim.settle();
            for (RegisterFile.Reg r : RegisterFile.Reg.values()) {
                probes.add(r.name(), dp.getRegister(r));
            }
            probes.add("alu", dp.getAluOutput());
            target = new ClockedTarget("dp", clk, probes);
            target.addInputs("ctl", controls);
            target.setStimulus(cycle -> {
                Control c = program.get((int) (cycle % program.size()));
                for (int i = 0; i < 30; i++) {
                    SimpleSignal s = (SimpleSignal) controls.getSignal(i);
                    if ((c.unknown() >> i & 1) != 0) {
                        s.setValue(LogicValue.X);
                    } else {
                        s.setOn((c.word() >> i & 1) != 0);
                    }
                }
            });
        }

        List<Snapshot> run(int steps) {
            List<Snapshot> out = new ArrayList<>();
            for (int i = 0; i < steps; i++) {
                target.step();
                out.add(target.snapshot());
            }
            return out;
        }
    }

    @Property(tries = 100)
    void restoreBringsBackTheSnapshottedStateAndTheSameFuture(@ForAll("program") List<Control> program,
                                                              @ForAll @IntRange(min = 0, max = 15) int before,
                                                              @ForAll @IntRange(min = 1, max = 15) int after) {
        Circuit c = new Circuit(program);
        c.run(before);
        Snapshot saved = c.target.snapshot();
        List<Snapshot> firstFuture = c.run(after);

        c.target.restore(saved);
        assertEquals(saved, c.target.snapshot(), "restored state equals the snapshot, probes and masks included");
        assertEquals(saved.value(ClockedTarget.CYCLES), c.target.cycleCount());
        assertEquals(before, c.target.instructionCount());
        assertEquals(firstFuture, c.run(after), "the run after a restore repeats the original run");
    }

    @Property(tries = 50)
    void aSnapshotRestoresIntoAnIdenticallyBuiltCircuit(@ForAll("program") List<Control> program,
                                                        @ForAll @IntRange(min = 0, max = 15) int before,
                                                        @ForAll @IntRange(min = 1, max = 10) int after) {
        Circuit a = new Circuit(program);
        Circuit b = new Circuit(program);
        a.run(before);
        b.run(3); // some other state first
        b.target.restore(a.target.snapshot());
        assertEquals(a.target.snapshot(), b.target.snapshot());
        assertEquals(a.run(after), b.run(after));
    }
}
