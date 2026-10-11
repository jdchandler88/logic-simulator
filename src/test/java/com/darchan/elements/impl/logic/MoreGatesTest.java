package com.darchan.elements.impl.logic;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ILogicUnit;
import com.darchan.elements.iface.ISignal;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.ConstantSignal;
import com.darchan.validation.UnexpectedBusWidthException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive truth tables for XOR, NAND and NOR with 2 to 5 inputs.
 */
class MoreGatesTest {

    static Stream<Arguments> gates() {
        //expected output as a function of the number of inputs that are on, and the width
        return Stream.of(
                Arguments.of(new XorGate(), (Expectation) (ones, width) -> ones % 2 == 1),
                Arguments.of(new NandGate(), (Expectation) (ones, width) -> ones != width),
                Arguments.of(new NorGate(), (Expectation) (ones, width) -> ones == 0)
        );
    }

    interface Expectation {
        boolean expected(int ones, int width);
    }

    @ParameterizedTest
    @MethodSource("gates")
    void truthTable(ILogicUnit gate, Expectation expectation) {
        for (int width = 2; width <= 5; width++) {
            for (int bits = 0; bits < (1 << width); bits++) {
                IBus bus = bus(width, bits);
                gate.validateInputBus(bus);
                assertEquals(expectation.expected(Integer.bitCount(bits), width), gate.evaluate(bus),
                        gate.getClass().getSimpleName() + " width " + width + " inputs " + Integer.toBinaryString(bits));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("gates")
    void fewerThanTwoInputsAreRejected(ILogicUnit gate, Expectation ignored) {
        assertAll(
                () -> assertEquals(2, gate.getAllowedInputBusWidth().getMin()),
                () -> assertEquals(Integer.MAX_VALUE, gate.getAllowedInputBusWidth().getMax()),
                () -> assertThrows(UnexpectedBusWidthException.class, () -> gate.validateInputBus(new Bus())),
                () -> assertThrows(UnexpectedBusWidthException.class,
                        () -> gate.validateInputBus(new Bus(ConstantSignal.ON)))
        );
    }

    private static IBus bus(int width, int bits) {
        ISignal[] signals = new ISignal[width];
        for (int i = 0; i < width; i++) {
            signals[i] = ((bits >> i) & 1) == 1 ? ConstantSignal.ON : ConstantSignal.OFF;
        }
        return new Bus(signals);
    }
}
