package com.darchan.elements.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConstantSignalTest {

    @Test
    public void trueInputShouldResultInTrueOutput() {
        assertTrue(new ConstantSignal(true).isOn());
    }

    @Test
    public void falseInputShouldResultInFalseOutput() {
        assertFalse(new ConstantSignal(false).isOn());
    }

    @Test
    public void onShouldHaveOnOutput() {
        assertTrue(ConstantSignal.ON.isOn());
    }

    @Test
    public void offShouldHaveOffOutput() {
        assertFalse(ConstantSignal.OFF.isOn());
    }

}
