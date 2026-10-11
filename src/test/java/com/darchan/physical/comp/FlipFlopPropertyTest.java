package com.darchan.physical.comp;

import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.LogicValue;
import com.darchan.physical.signal.SimpleSignal;
import com.darchan.physical.sim.Simulator;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.List;

import static com.darchan.physical.signal.LogicValue.ONE;
import static com.darchan.physical.signal.LogicValue.X;
import static com.darchan.physical.signal.LogicValue.Z;
import static com.darchan.physical.signal.LogicValue.ZERO;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A flip-flop driven by random four-valued clock, D and write-enable sequences behaves like the table in the README
 * (the pessimistic Verilog-UDP rule): a real 0 to 1 edge latches D (Z reads as X); a transition that may be a rising
 * edge (0 to X/Z, X/Z to 1) keeps Q only if D equals Q and otherwise makes it X; every other transition holds Q.
 */
class FlipFlopPropertyTest {

    /** one step: new D, new enable, new clock level (applied in that order, settling after each) */
    record Step(LogicValue d, LogicValue enable, LogicValue clock) {
    }

    @Provide
    Arbitrary<LogicValue> levels() {
        return Arbitraries.of(ZERO, ONE, Z, X);
    }

    @Provide
    Arbitrary<Step> steps() {
        Arbitrary<LogicValue> l = levels();
        return Combinators.combine(l, l, l).as(Step::new);
    }

    @Provide
    Arbitrary<List<Step>> stepLists() {
        return steps().list().ofMaxSize(30);
    }

    /** the expected Q after a clock transition, given the sampled D (after the write-enable mux if any) */
    private static LogicValue expected(LogicValue from, LogicValue to, LogicValue sampled, LogicValue q) {
        LogicValue d = sampled.isKnown() ? sampled : X;
        if (from == ZERO && to == ONE) {
            return d;
        }
        boolean mayRise = from == ZERO && !to.isKnown() || !from.isKnown() && to == ONE;
        if (mayRise) {
            return d == q ? q : X;
        }
        return q;
    }

    @Property
    void aPlainFlipFlopFollowsTheClockTable(@ForAll("levels") LogicValue initialClock,
                                            @ForAll("levels") LogicValue initialQ,
                                            @ForAll("stepLists") List<Step> steps) {
        Simulator sim = new Simulator();
        SimpleSignal clk = new SimpleSignal(sim, initialClock);
        SimpleSignal d = new SimpleSignal(sim, ZERO);
        FlipFlop ff = new FlipFlop(sim, clk, new Bus(d), false);
        ff.force(0, initialQ);
        sim.settle();
        LogicValue q = initialQ;
        LogicValue clock = initialClock;
        for (Step s : steps) {
            d.setValue(s.d());
            sim.settle();
            clk.setValue(s.clock());
            sim.settle();
            if (s.clock() != clock) { // no change, no event
                q = expected(clock, s.clock(), s.d(), q);
            }
            clock = s.clock();
            assertEquals(q, ff.get(0), "after " + s + " (clock was " + clock + ")");
            assertEquals(q, ff.getOutputBus().getSignal(0).getValue());
        }
    }

    @Property
    void aWriteEnableHoldsQUnlessEnabledAndAnUnknownEnableLatchesX(@ForAll("levels") LogicValue initialQ,
                                                                   @ForAll("stepLists") List<Step> steps) {
        Simulator sim = new Simulator();
        SimpleSignal clk = new SimpleSignal(sim, ZERO);
        SimpleSignal d = new SimpleSignal(sim, ZERO);
        SimpleSignal en = new SimpleSignal(sim, ZERO);
        FlipFlop ff = new FlipFlop(sim, clk, new Bus(d), en, false);
        ff.force(0, initialQ);
        sim.settle();
        LogicValue q = initialQ;
        LogicValue clock = ZERO;
        for (Step s : steps) {
            d.setValue(s.d());
            en.setValue(s.enable());
            sim.settle();
            // D' = (EN AND D) OR (NOT EN AND Q), evaluated with the four-valued gates
            LogicValue sampled = s.enable().and(s.d()).or(s.enable().not().and(q));
            clk.setValue(s.clock());
            sim.settle();
            if (s.clock() != clock) {
                q = expected(clock, s.clock(), sampled, q);
            }
            clock = s.clock();
            assertEquals(q, ff.get(0), "after " + s);
        }
    }
}
