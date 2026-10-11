package com.darchan.physical.validation;

import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.ConstantSignal;
import com.darchan.physical.signal.SimpleRange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValidationTest {

    @Test
    void inputWidthShouldPassValidationIfNumberInputsWithinRange() {
        int numInputs = 5;
        int minRange = 1;
        int maxRange = 10;
        Validation.validateInputWidth(new SimpleRange(minRange, maxRange), createBusOfWidth(numInputs));
    }

    @Test
    void inputWidthshouldFailValidationIfNumberOfInputsBelowRange() {
        int numInputs = 0;
        int minRange = 1;
        int maxRange = 10;
        assertThrows(UnexpectedBusWidthException.class, () -> Validation.validateInputWidth(new SimpleRange(minRange, maxRange), createBusOfWidth(numInputs)));
    }

    @Test
    void inputWidthShouldFailValidationIfNumberInputsAboveRange() {
        int numInputs = 11;
        int minRange = 1;
        int maxRange = 10;
        assertThrows(UnexpectedBusWidthException.class, () -> Validation.validateInputWidth(new SimpleRange(minRange, maxRange), createBusOfWidth(numInputs)));
    }

    private static IBus createBusOfWidth(int length) {
        ISignal[] sigs = new ISignal[length];
        for (int i=0; i<length; i++) {
            sigs[i] = ConstantSignal.ON;
        }
        return new Bus(sigs);
    }

}
