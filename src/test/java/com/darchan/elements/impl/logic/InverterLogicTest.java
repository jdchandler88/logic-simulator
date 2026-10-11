package com.darchan.elements.impl.logic;

import com.darchan.validation.UnexpectedBusWidthException;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.ConstantSignal;
import com.darchan.elements.impl.LogicSignal;
import com.darchan.sim.Simulator;
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

    //width is validated once, when the gate is wired up, not on every evaluate
    @Test
    void shouldThrowExceptionIfBusWidthIsLessThanOne() {
        Bus smallBus = new Bus();
        assertAll(
                () -> assertThrows(UnexpectedBusWidthException.class, () -> cut.validateInputBus(smallBus)),
                () -> assertThrows(UnexpectedBusWidthException.class, () -> new LogicSignal(new Simulator(), smallBus, cut))
        );
    }

    @Test
    void shouldThrowExceptionIfBusWidthIsGreaterThanOne() {
        Bus largeBus = new Bus(ConstantSignal.ON, ConstantSignal.ON);
        assertAll(
                () -> assertThrows(UnexpectedBusWidthException.class, () -> cut.validateInputBus(largeBus)),
                () -> assertThrows(UnexpectedBusWidthException.class, () -> new LogicSignal(new Simulator(), largeBus, cut))
        );
    }

}
