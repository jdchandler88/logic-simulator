package com.darchan.physical.logic;

import com.darchan.physical.validation.UnexpectedBusWidthException;
import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.ConstantSignal;
import com.darchan.physical.signal.LogicSignal;
import com.darchan.physical.sim.Simulator;
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
    void allowedWidthIsTwoOrMore() {
        assertAll(
                () -> assertEquals(2, cut.getAllowedInputBusWidth().getMin()),
                () -> assertEquals(Integer.MAX_VALUE, cut.getAllowedInputBusWidth().getMax())
        );
    }

    //width is validated once, when the gate is wired up, not on every evaluate
    @Test
    void shouldThrowExceptionIfInputBusIsSmallerThanTwo() {
        IBus smallBus = new Bus(ConstantSignal.ON);
        assertAll(
                () -> assertThrows(UnexpectedBusWidthException.class, () -> cut.validateInputBus(smallBus)),
                () -> assertThrows(UnexpectedBusWidthException.class, () -> new LogicSignal(new Simulator(), smallBus, cut))
        );
    }

}
