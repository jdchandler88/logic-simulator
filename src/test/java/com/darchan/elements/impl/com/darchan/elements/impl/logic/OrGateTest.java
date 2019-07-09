package com.darchan.elements.impl.com.darchan.elements.impl.logic;

import com.darchan.com.darchan.validation.UnexpectedBusWidthException;
import com.darchan.elements.iface.IBus;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.ConstantSignal;
import com.darchan.elements.impl.logic.OrGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class OrGateTest {

    private OrGate cut;

    @BeforeEach
    void init() {
        cut = new OrGate();
    }

    @ParameterizedTest
    @MethodSource("getTestValues")
    void logicShouldSatisfyValues(IBus inputBus, boolean expectedValue) {
        assertEquals(expectedValue, cut.evaluate(inputBus));
    }

    static Stream<Arguments> getTestValues() {
        return Stream.of(
                Arguments.of(new Bus(ConstantSignal.ON, ConstantSignal.ON), true),
                Arguments.of(new Bus(ConstantSignal.ON, ConstantSignal.OFF), true),
                Arguments.of(new Bus(ConstantSignal.OFF, ConstantSignal.ON), true),
                Arguments.of(new Bus(ConstantSignal.OFF, ConstantSignal.OFF), false),

                Arguments.of(new Bus(ConstantSignal.ON, ConstantSignal.ON, ConstantSignal.ON), true),
                Arguments.of(new Bus(ConstantSignal.OFF, ConstantSignal.ON, ConstantSignal.ON), true),
                Arguments.of(new Bus(ConstantSignal.ON, ConstantSignal.OFF, ConstantSignal.ON), true),
                Arguments.of(new Bus(ConstantSignal.ON, ConstantSignal.OFF, ConstantSignal.OFF), true),
                Arguments.of(new Bus(ConstantSignal.OFF, ConstantSignal.ON, ConstantSignal.ON), true),
                Arguments.of(new Bus(ConstantSignal.OFF, ConstantSignal.ON, ConstantSignal.OFF), true),
                Arguments.of(new Bus(ConstantSignal.OFF, ConstantSignal.OFF, ConstantSignal.ON), true),
                Arguments.of(new Bus(ConstantSignal.OFF, ConstantSignal.OFF, ConstantSignal.OFF), false)
        );
    }

    @Test
    void shouldThrowExceptionIfInputBusIsSmallerThanTwo() {
        IBus smallBus = new Bus(ConstantSignal.ON);
        assertThrows(UnexpectedBusWidthException.class, () -> cut.evaluate(smallBus));
    }

}
