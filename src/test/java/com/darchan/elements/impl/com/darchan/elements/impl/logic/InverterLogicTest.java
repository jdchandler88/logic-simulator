package com.darchan.elements.impl.com.darchan.elements.impl.logic;

import com.darchan.com.darchan.validation.UnexpectedBusWidthException;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.ConstantSignal;
import com.darchan.elements.impl.logic.InverterLogicUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InverterLogicTest {

    private InverterLogicUnit cut;

    @BeforeEach
    void init() {
        cut = new InverterLogicUnit();
    }

    @Test
    void shouldReturnTrueIfInputIsFalse() {
        Bus bus = new Bus(ConstantSignal.OFF);
        assertTrue(cut.evaluate(bus));
    }

    @Test
    void shouldReturnFalseIfInputIsTrue() {
        Bus bus = new Bus(ConstantSignal.ON);
        assertFalse(cut.evaluate(bus));
    }

    @Test
    void shouldThrowExceptionIfBusWidthIsLessThanOne() {
        Bus smallBus = new Bus();
        assertThrows(UnexpectedBusWidthException.class, () -> cut.evaluate(smallBus) );
    }

    @Test
    void shouldThrowExceptionIfBusWidthIsGreaterThanOne() {
        Bus largeBus = new Bus(ConstantSignal.ON, ConstantSignal.ON);
        assertThrows(UnexpectedBusWidthException.class, () -> cut.evaluate(largeBus) );
    }
}
