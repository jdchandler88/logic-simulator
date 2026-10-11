package com.darchan.physical.logic;

import com.darchan.physical.validation.UnexpectedBusWidthException;
import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.ConstantSignal;
import com.darchan.physical.signal.LogicSignal;
import com.darchan.physical.sim.Simulator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AndGateTest {

    private final Simulator sim = new Simulator();

    private AndGate cut = new AndGate();

    @ParameterizedTest
    @MethodSource("getInputs")
    void andUnitShouldReturnAppropriateOutput(IBus inputBus, boolean expectedValue) {
        assertEquals(expectedValue, cut.evaluate(inputBus));
    }

    static Stream<Arguments> getInputs() {
        return Stream.of(
                Arguments.of(new Bus(ConstantSignal.ON, ConstantSignal.ON), true),
                Arguments.of(new Bus(ConstantSignal.ON, ConstantSignal.OFF), false),
                Arguments.of(new Bus(ConstantSignal.OFF, ConstantSignal.ON), false),
                Arguments.of(new Bus(ConstantSignal.OFF, ConstantSignal.OFF), false),

                Arguments.of(new Bus(ConstantSignal.ON, ConstantSignal.ON, ConstantSignal.ON), true),
                Arguments.of(new Bus(ConstantSignal.OFF, ConstantSignal.ON, ConstantSignal.ON), false),
                Arguments.of(new Bus(ConstantSignal.ON, ConstantSignal.OFF, ConstantSignal.ON), false),
                Arguments.of(new Bus(ConstantSignal.ON, ConstantSignal.OFF, ConstantSignal.OFF), false),
                Arguments.of(new Bus(ConstantSignal.OFF, ConstantSignal.ON, ConstantSignal.ON), false),
                Arguments.of(new Bus(ConstantSignal.OFF, ConstantSignal.ON, ConstantSignal.OFF), false),
                Arguments.of(new Bus(ConstantSignal.OFF, ConstantSignal.OFF, ConstantSignal.ON), false),
                Arguments.of(new Bus(ConstantSignal.OFF, ConstantSignal.OFF, ConstantSignal.OFF), false)
        );
    }

    @Test
    void allowedWidthIsTwoOrMore() {
        assertAll(
                () -> assertEquals(2, cut.getAllowedInputBusWidth().getMin()),
                () -> assertEquals(Integer.MAX_VALUE, cut.getAllowedInputBusWidth().getMax())
        );
    }

    //width is validated once, when the gate is wired up, not on every evaluate
    @Test
    void shouldThrowExceptionIfNumberOfInputsIsZero() {
        IBus bus = new Bus();
        assertAll(
                () -> assertThrows(UnexpectedBusWidthException.class, () -> cut.validateInputBus(bus)),
                () -> assertThrows(UnexpectedBusWidthException.class, () -> new LogicSignal(sim, bus, cut))
        );
    }

    @Test
    void shouldThrowExceptionIfNumberOfInputsIsOne() {
        IBus bus = new Bus(ConstantSignal.ON);
        assertAll(
                () -> assertThrows(UnexpectedBusWidthException.class, () -> cut.validateInputBus(bus)),
                () -> assertThrows(UnexpectedBusWidthException.class, () -> new LogicSignal(sim, bus, cut))
        );
    }

}
