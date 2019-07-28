package com.darchan.elements.impl.com.darchan;

import com.darchan.elements.impl.SimpleSignal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimpleSignalTest {

    @ParameterizedTest
    @CsvSource(value = {"true", "false"})
    void shouldHaveInitialState(boolean initialValue) {
        SimpleSignal ts = new SimpleSignal(initialValue);
        assertEquals(initialValue, ts.isOn());
    }

    @ParameterizedTest
    @CsvSource(value = {"true", "false"})
    void shouldHaveSetState(boolean targetState) {
        SimpleSignal ts = new SimpleSignal(false);
        ts.setOn(targetState);
        assertEquals(targetState, ts.isOn());
    }

}
