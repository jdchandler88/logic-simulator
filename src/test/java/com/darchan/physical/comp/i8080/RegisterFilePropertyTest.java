package com.darchan.physical.comp.i8080;

import com.darchan.physical.signal.Clock;
import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.SimpleRange;
import com.darchan.physical.sim.Simulator;
import com.darchan.testutil.Buses;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The register file against a model: per-register write enables, one shared data bus, one clock. */
class RegisterFilePropertyTest {

    record Cycle(int data, int enables, int lateData, int lateEnables) {
    }

    @Provide
    Arbitrary<Cycle> cycles() {
        Arbitrary<Integer> b = Arbitraries.integers().between(0, 255);
        return Combinators.combine(b, b, b, b).as(Cycle::new);
    }

    @Provide
    Arbitrary<List<Cycle>> cycleLists() {
        return cycles().list().ofMaxSize(25);
    }

    private static void assertFile(RegisterFile rf, int[] model, String when) {
        for (RegisterFile.Reg r : RegisterFile.Reg.values()) {
            assertEquals(model[r.slot()], Buses.toInt(rf.getRegisterOutput(r)), when + ": register " + r);
            assertEquals(model[r.slot()], Buses.toInt(rf.getOutputBus().slice(r.outputRange())),
                    when + ": slice of the output bus for " + r);
        }
        // the pairs are 16-bit slices with the high register in the high byte (HL, DE, BC, PSW)
        assertEquals(model[1] << 8 | model[0], Buses.toInt(rf.getOutputBus().slice(new SimpleRange(0, 15))), when + ": HL");
        assertEquals(model[3] << 8 | model[2], Buses.toInt(rf.getOutputBus().slice(new SimpleRange(16, 31))), when + ": DE");
        assertEquals(model[5] << 8 | model[4], Buses.toInt(rf.getOutputBus().slice(new SimpleRange(32, 47))), when + ": BC");
    }

    @Property(tries = 200)
    void writeEnableSequencesMatchAModel(@ForAll("cycleLists") List<Cycle> cycles) {
        Simulator sim = new Simulator();
        Clock clk = new Clock(sim, false);
        IBus in = Buses.simpleBus(sim, 16, 0);
        RegisterFile rf = new RegisterFile(sim, clk, in);
        sim.settle();
        int[] model = new int[8]; // slot order L H E D C B F A, all reset to 0
        for (Cycle c : cycles) {
            Buses.drive(in, c.data() | c.enables() << 8);
            sim.settle();
            assertFile(rf, model, "before the edge");
            clk.tick(); // rising edge
            for (int slot = 0; slot < 8; slot++) {
                if ((c.enables() >> slot & 1) != 0) {
                    model[slot] = c.data();
                }
            }
            assertFile(rf, model, "after the rising edge");
            Buses.drive(in, c.lateData() | c.lateEnables() << 8);
            sim.settle();
            assertFile(rf, model, "inputs changed while the clock is high");
            clk.tick(); // falling edge
            assertFile(rf, model, "after the falling edge");
        }
    }
}
