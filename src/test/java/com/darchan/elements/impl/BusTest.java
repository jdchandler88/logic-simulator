package com.darchan.elements.impl;

import com.darchan.elements.iface.ISignal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BusTest {

    @ParameterizedTest
    @MethodSource("getSignals")
    public void busShouldHaveAppropriateNumberOfSignals(ISignal[] signals, int expectedNumber) {
        Bus bus = new Bus(signals);
        assertEquals(expectedNumber, bus.getSignals().size());
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
