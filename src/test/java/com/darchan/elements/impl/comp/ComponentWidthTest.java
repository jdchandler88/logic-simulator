package com.darchan.elements.impl.comp;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.IComponent;
import com.darchan.elements.iface.IRange;
import com.darchan.elements.impl.Clock;
import com.darchan.elements.impl.ConstantSignal;
import com.darchan.elements.impl.comp.cpu.intel8080.RegisterFile;
import com.darchan.sim.Simulator;
import com.darchan.testutil.Buses;
import com.darchan.validation.UnexpectedBusWidthException;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every component declares real allowed widths, and rejects a wrongly-sized input bus when it is constructed.
 */
class ComponentWidthTest {

    /**
     * A component factory together with one valid and some invalid input widths.
     */
    record Spec(BiFunction<Simulator, IBus, IComponent> factory, int validWidth, int... invalidWidths) {}

    static Stream<Arguments> components() {
        return Stream.of(
                Arguments.of(Named.of("Inverter", new Spec((sim, bus) -> new Inverter(sim, bus), 1, 0, 2))),
                Arguments.of(Named.of("FlipFlop",
                        new Spec((sim, bus) -> new FlipFlop(sim, new Clock(sim, false), bus, false), 1, 0, 2))),
                Arguments.of(Named.of("Register",
                        new Spec((sim, bus) -> new Register(sim, new Clock(sim, false), bus, false), 8, 0, 1))),
                Arguments.of(Named.of("BusSelector8By8Bit",
                        new Spec((sim, bus) -> new BusSelector8By8Bit(sim, bus), 72, 64, 71, 73))),
                Arguments.of(Named.of("TriStateBuffer",
                        new Spec((sim, bus) -> new TriStateBuffer(sim, bus, ConstantSignal.ON), 8, 0))),
                Arguments.of(Named.of("RegisterFile",
                        new Spec((sim, bus) -> new RegisterFile(sim, bus), 24, 16, 23, 25)))
        );
    }

    @ParameterizedTest
    @MethodSource("components")
    void declaredWidthsMatchTheBuses(Spec spec) {
        Simulator sim = new Simulator();
        IComponent component = spec.factory().apply(sim, Buses.simpleBus(sim, spec.validWidth(), 0));
        IRange in = component.getAllowedInputBusWidth();
        IRange out = component.getAllowedOutputBusWidth();
        assertAll(
                () -> assertNotNull(in),
                () -> assertNotNull(out),
                () -> assertWithin(in, component.getInputBus()),
                () -> assertWithin(out, component.getOutputBus())
        );
    }

    @ParameterizedTest
    @MethodSource("components")
    void wrongInputWidthIsRejectedAtConstruction(Spec spec) {
        for (int width : spec.invalidWidths()) {
            Simulator sim = new Simulator();
            IBus bus = Buses.simpleBus(sim, width, 0);
            assertThrows(UnexpectedBusWidthException.class, () -> spec.factory().apply(sim, bus),
                    "width " + width);
        }
    }

    private static void assertWithin(IRange range, IBus bus) {
        int width = bus.getSignals().size();
        assertTrue(width >= range.getMin() && width <= range.getMax(),
                "width " + width + " not in [" + range.getMin() + ", " + range.getMax() + "]");
    }
}
