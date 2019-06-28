package com.darchan.elements.impl;

import com.darchan.elements.iface.ISignal;
import com.darchan.elements.impl.logic.LogicalFalseLogicUnit;
import com.darchan.elements.impl.logic.LogicalTrueLogicUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LogicSignalTest {

    @Test
    void constructorShouldInitializeToTrueWithLogicUnitStateTrue() {
        ISignal sig1 = ConstantSignal.ON;
        ISignal sig2 = ConstantSignal.OFF;
        Bus bus = new Bus(sig1, sig2);
        LogicSignal cut = new LogicSignal(bus, new LogicalTrueLogicUnit());
        assertTrue(cut.isOn());
    }

    @Test
    void constructorShouldInitializeToFalseWithLogicUnitStateFalse() {
        ISignal sig1 = ConstantSignal.ON;
        ISignal sig2 = ConstantSignal.OFF;
        Bus bus = new Bus(sig1, sig2);
        LogicSignal cut = new LogicSignal(bus, new LogicalFalseLogicUnit());
        assertFalse(cut.isOn());
    }

    @Test
    void constructorShouldRegisterListenersForInputSignals() {
        ISignal sig1 = mock(ISignal.class);
        ISignal sig2 = mock(ISignal.class);
        Bus bus = new Bus(sig1, sig2);
        LogicSignal cut = new LogicSignal(bus, new LogicalTrueLogicUnit());
        assertAll(
                () -> verify(sig1, times(1)).addSignalChangeListener(ArgumentMatchers.any()),
                () -> verify(sig2, times(1)).addSignalChangeListener(ArgumentMatchers.any())
        );
    }

}