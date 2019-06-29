package com.darchan.elements.impl.com.darchan.elements.impl.logic;

import com.darchan.com.darchan.validation.UnexpectedBusWidthException;
import com.darchan.elements.iface.IBus;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.ConstantSignal;
import com.darchan.elements.impl.logic.AndGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AndGateTest {

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
    void shouldThrowExceptionIfNumberOfInputsIsZero() {
        assertThrows(UnexpectedBusWidthException.class, () -> {
            IBus bus = new Bus();
            cut.evaluate(bus);
        });
    }

    @Test
    void shouldThrowExceptionIfNumberOfInputsIsOne() {
        assertThrows(UnexpectedBusWidthException.class, () -> {
            IBus bus = new Bus(ConstantSignal.ON);
            cut.evaluate(bus);
        });
    }

}
