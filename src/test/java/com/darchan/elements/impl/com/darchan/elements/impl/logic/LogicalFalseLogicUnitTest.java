package com.darchan.elements.impl.com.darchan.elements.impl.logic;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.ConstantSignal;
import com.darchan.elements.impl.logic.LogicalFalseLogicUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

public class LogicalFalseLogicUnitTest {

    @ParameterizedTest
    @MethodSource("getSignals")
    public void shouldAlwaysReturnTrue(IBus input) {
        assertFalse(new LogicalFalseLogicUnit().evaluate(input));
    }

    public static Stream<Arguments> getSignals() {
        return Stream.of(
            Arguments.of(new Bus(ConstantSignal.ON)),
            Arguments.of(new Bus(ConstantSignal.OFF)),
            Arguments.of(new Bus(ConstantSignal.ON, ConstantSignal.ON)),
            Arguments.of(new Bus(ConstantSignal.OFF, ConstantSignal.OFF))
        );
    }


}
