package com.darchan.elements.impl;

import com.darchan.elements.iface.ISignal;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class BusTest {

    @ParameterizedTest
    @MethodSource("getSignals")
    public void busShouldHaveAppropriateNumberOfSignals(ISignal[] signals, int expectedNumber) {
        Bus bus = new Bus(signals);
        assertEquals(expectedNumber, bus.getSignals().size());
    }

    @TestFactory
    public Iterable<DynamicTest> busShouldHaveCorrectSignals() {
        int numSignals = 50;
        Random random = new Random();
        List<Boolean> values = new ArrayList<>();
        ISignal[] signals = new ISignal[numSignals];
        for (int i=0; i<50; i++) {
            boolean value = random.nextBoolean();
            values.add(value);
            signals[i] = new ConstantSignal(value);
        }

        Bus bus = new Bus(signals);

        List<DynamicTest> tests = new ArrayList<>();
        for (int i=0; i<numSignals; i++) {
            final int idx = i;
            tests.add(DynamicTest.dynamicTest("signalTest" + idx, () -> assertEquals(values.get(idx), bus.getSignals().get(idx).isOn())));
        }
        return tests;
    }

    public static Stream getSignals() {
        Stream.Builder<Arguments> streamBuilder = Stream.builder();
        for (int i=1; i<10; i++) {
            streamBuilder.add(Arguments.of(createSignalArray(i), i));
        }
        return streamBuilder.build();
    }

    private static ISignal[] createSignalArray(int number) {
        ISignal[] arr = new ISignal[number];
        for (int i=0; i<number; i++) {
            arr[i] = ConstantSignal.ON;
        }
        return arr;
    }

}
