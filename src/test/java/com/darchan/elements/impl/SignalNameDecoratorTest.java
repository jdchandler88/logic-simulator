package com.darchan.elements.impl;

import com.darchan.elements.iface.ISignal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SignalNameDecoratorTest {

    @Test
    void nameShouldReturnStringAndSignalValue() {
        SignalNameDecorator cut = new SignalNameDecorator(ConstantSignal.ON, "Test");
        assertEquals("Test-true", cut.toString());
    }

}