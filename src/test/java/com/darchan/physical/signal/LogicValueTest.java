package com.darchan.physical.signal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.darchan.physical.signal.LogicValue.*;
import static org.junit.jupiter.api.Assertions.*;

class LogicValueTest {

    @Test
    void onlyOneIsOn() {
        assertAll(
                () -> assertTrue(ONE.isOn()),
                () -> assertFalse(ZERO.isOn()),
                () -> assertFalse(Z.isOn()),
                () -> assertFalse(X.isOn())
        );
    }

    @Test
    void onlyZeroAndOneAreKnown() {
        assertAll(
                () -> assertTrue(ONE.isKnown()),
                () -> assertTrue(ZERO.isKnown()),
                () -> assertFalse(Z.isKnown()),
                () -> assertFalse(X.isKnown())
        );
    }

    @Test
    void ofBoolean() {
        assertAll(
                () -> assertSame(ONE, LogicValue.of(true)),
                () -> assertSame(ZERO, LogicValue.of(false))
        );
    }

    @ParameterizedTest(name = "{0} + {1} = {2}")
    @CsvSource({
            "Z, Z, Z", "Z, ZERO, ZERO", "Z, ONE, ONE", "Z, X, X",
            "ZERO, ZERO, ZERO", "ONE, ONE, ONE", "ZERO, ONE, X", "ONE, ZERO, X",
            "X, ZERO, X", "X, ONE, X", "X, X, X"
    })
    void resolutionOfTwoDrivers(LogicValue a, LogicValue b, LogicValue expected) {
        assertAll(
                () -> assertEquals(expected, a.resolve(b)),
                () -> assertEquals(expected, b.resolve(a))
        );
    }

    @Test
    void shortNames() {
        assertEquals("01ZX", "" + ZERO + ONE + Z + X);
    }
}
