package com.darchan.elements.impl.com.darchan;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSignalTest {

    @ParameterizedTest
    @CsvSource(value = {"true", "false"})
    void shouldHaveInitialState(boolean initialValue) {
        TestSignal ts = new TestSignal(initialValue);
        assertEquals(initialValue, ts.isOn());
    }

    @ParameterizedTest
    @CsvSource(value = {"true", "false"})
    void shouldHaveSetState(boolean targetState) {
        TestSignal ts = new TestSignal(false);
        ts.setValue(targetState);
        assertEquals(targetState, ts.isOn());
    }

}
