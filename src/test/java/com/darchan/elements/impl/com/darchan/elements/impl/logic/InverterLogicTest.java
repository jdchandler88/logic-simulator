package com.darchan.elements.impl.com.darchan.elements.impl.logic;

import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.ConstantSignal;
import com.darchan.elements.impl.logic.InverterLogicUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InverterLogicTest {
    @Test
    public void shouldReturnTrueIfInputIsFalse() {
        Bus bus = new Bus(ConstantSignal.OFF);
        assertTrue(new InverterLogicUnit().evaluate(bus));
    }

    @Test
    public void shouldReturnFalseIfInputIsTrue() {
        Bus bus = new Bus(ConstantSignal.ON);
        assertFalse(new InverterLogicUnit().evaluate(bus));
    }
}
