package com.darchan.physical.comp;

import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.Clock;
import com.darchan.physical.signal.IBus;
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
import net.jqwik.api.constraints.Size;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Registers and counters against plain-Java models over random sequences of data, write enables and clock edges. */
class RegisterPropertyTest {

    /** the inputs for one clock cycle, and different inputs applied while the clock is high */
    record Cycle(int data, boolean enable, int lateData, boolean lateEnable) {
    }

    @Provide
    Arbitrary<Cycle> cycles() {
        return Combinators.combine(Arbitraries.integers().between(0, 0xFFFF), Arbitraries.of(true, false),
                Arbitraries.integers().between(0, 0xFFFF), Arbitraries.of(true, false)).as(Cycle::new);
    }

    @Provide
    Arbitrary<List<Cycle>> cycleLists() {
        return cycles().list().ofMaxSize(25);
    }

    @Property(tries = 300)
    void aRegisterWithAWriteEnableLoadsOnlyOnEnabledRisingEdges(
            @ForAll @IntRange(min = 2, max = 16) int width,
            @ForAll("cycleLists") List<Cycle> cycles) {
        Simulator sim = new Simulator();
        Clock clk = new Clock(sim, false);
        IBus data = Buses.simpleBus(sim, width, 0);
        SimpleSignal en = new SimpleSignal(sim, false);
        Register reg = new Register(sim, clk, data, en, false);
        sim.settle();
        int mask = (1 << width) - 1;
        int model = 0;
        for (Cycle c : cycles) {
            Buses.drive(data, c.data());
            en.setOn(c.enable());
            sim.settle();
            assertEquals(model, Buses.toInt(reg.getOutputBus()), "before the edge");
            clk.tick(); // rising edge
            if (c.enable()) {
                model = c.data() & mask;
            }
            assertEquals(model, Buses.toInt(reg.getOutputBus()), "after the rising edge");
            // inputs changing while the clock is high, and the falling edge, change nothing
            Buses.drive(data, c.lateData());
            en.setOn(c.lateEnable());
            sim.settle();
            assertEquals(model, Buses.toInt(reg.getOutputBus()), "inputs changed while the clock is high");
            clk.tick(); // falling edge
            assertEquals(model, Buses.toInt(reg.getOutputBus()), "after the falling edge");
        }
    }

    @Property(tries = 300)
    void aRegisterWithoutAnEnableLoadsOnEveryRisingEdge(
            @ForAll @IntRange(min = 2, max = 16) int width,
            @ForAll("cycleLists") List<Cycle> cycles) {
        Simulator sim = new Simulator();
        Clock clk = new Clock(sim, false);
        IBus data = Buses.simpleBus(sim, width, 0);
        Register reg = new Register(sim, clk, data, false);
        sim.settle();
        int mask = (1 << width) - 1;
        int model = 0;
        for (Cycle c : cycles) {
            Buses.drive(data, c.data());
            sim.settle();
            clk.tick();
            model = c.data() & mask;
            assertEquals(model, Buses.toInt(reg.getOutputBus()));
            Buses.drive(data, c.lateData());
            sim.settle();
            clk.tick();
            assertEquals(model, Buses.toInt(reg.getOutputBus()), "the falling edge does not load");
        }
    }

    @Property(tries = 200)
    void aCounterCountsRisingEdgesModuloItsWidth(@ForAll @IntRange(min = 1, max = 8) int width,
                                                 @ForAll @IntRange(min = 0, max = 600) int edges) {
        Simulator sim = new Simulator();
        Clock clk = new Clock(sim, false);
        Counter counter = new Counter(sim, clk, width);
        sim.settle();
        for (int i = 1; i <= edges; i++) {
            clk.tick(); // rise
            assertEquals(i & (1 << width) - 1, Buses.toInt(counter.getOutputBus()));
            clk.tick(); // fall: no count
            assertEquals(i & (1 << width) - 1, Buses.toInt(counter.getOutputBus()));
        }
    }

    @Property(tries = 100)
    void aShiftRegisterMovesEachBitOnePositionPerEdge(@ForAll @Size(min = 1, max = 20) List<Boolean> input) {
        Simulator sim = new Simulator();
        Clock clk = new Clock(sim, false);
        SimpleSignal in = new SimpleSignal(sim, false);
        FlipFlop a = new FlipFlop(sim, clk, new Bus(in), false);
        FlipFlop b = new FlipFlop(sim, clk, a.getOutputBus(), false);
        FlipFlop c = new FlipFlop(sim, clk, b.getOutputBus(), false);
        sim.settle();
        boolean[] history = new boolean[input.size() + 3];
        for (int i = 0; i < input.size(); i++) {
            in.setOn(input.get(i));
            sim.settle();
            clk.tick();
            clk.tick();
            history[i + 3] = input.get(i);
            assertEquals(history[i + 3], a.get(0).isOn(), "stage 1");
            assertEquals(history[i + 2], b.get(0).isOn(), "stage 2 holds the previous input");
            assertEquals(history[i + 1], c.get(0).isOn(), "stage 3 holds the one before");
        }
    }
}
