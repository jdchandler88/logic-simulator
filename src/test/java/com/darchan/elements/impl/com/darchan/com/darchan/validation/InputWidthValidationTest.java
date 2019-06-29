package com.darchan.elements.impl.com.darchan.com.darchan.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputWidthValidationTest {

    @Test
    void shouldPassValidationIfNumberInputsWithinRange() {
        int numInputs = 5;
        int minRange = 1;
        int maxRange = 10;
        assertTrue(false);
    }

    @Test
    void shouldFailValidationIfNumberOfInputsBelowRange() {
        int numInputs = 0;
        int minRange = 1;
        int maxRange = 10;
        assertFalse(true);
    }

    @Test
    void shouldFailValidationIfNumberInputsAboveRange() {
        int numInputs = 11;
        int minRange = 1;
        int maxRange = 10;
        assertFalse(true);
    }

}
