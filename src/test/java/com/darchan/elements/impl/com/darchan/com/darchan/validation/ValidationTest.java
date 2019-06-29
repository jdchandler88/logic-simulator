package com.darchan.elements.impl.com.darchan.com.darchan.validation;

import com.darchan.com.darchan.validation.UnexpectedBusWidthException;
import com.darchan.com.darchan.validation.Validation;
import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ISignal;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.ConstantSignal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValidationTest {

    @Test
    void inputWidthShouldPassValidationIfNumberInputsWithinRange() {
        int numInputs = 5;
        int minRange = 1;
        int maxRange = 10;
        Validation.validateInputWidth(minRange, maxRange, createBusOfWidth(numInputs));
    }

    @Test
    void inputWidthshouldFailValidationIfNumberOfInputsBelowRange() {
        int numInputs = 0;
        int minRange = 1;
        int maxRange = 10;
        assertThrows(UnexpectedBusWidthException.class, () -> Validation.validateInputWidth(minRange, maxRange, createBusOfWidth(numInputs)));
    }

    @Test
    void inputWidthShouldFailValidationIfNumberInputsAboveRange() {
        int numInputs = 11;
        int minRange = 1;
        int maxRange = 10;
        assertThrows(UnexpectedBusWidthException.class, () -> Validation.validateInputWidth(minRange, maxRange, createBusOfWidth(numInputs)));
    }

    private static IBus createBusOfWidth(int length) {
        ISignal[] sigs = new ISignal[length];
        for (int i=0; i<length; i++) {
            sigs[i] = ConstantSignal.ON;
        }
        return new Bus(sigs);
    }

}
