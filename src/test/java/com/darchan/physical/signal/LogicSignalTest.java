package com.darchan.physical.signal;

import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.ILogicUnit;
import com.darchan.physical.signal.IRange;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.logic.LogicalFalseLogicUnit;
import com.darchan.physical.logic.LogicalTrueLogicUnit;
import com.darchan.physical.sim.Simulator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LogicSignalTest {

    private final Simulator sim = new Simulator();

    @Test
    void constructorShouldInitializeToTrueWithLogicUnitStateTrue() {
        ISignal sig1 = ConstantSignal.ON;
        ISignal sig2 = ConstantSignal.OFF;
        Bus bus = new Bus(sig1, sig2);
        LogicSignal cut = new LogicSignal(sim, bus, new LogicalTrueLogicUnit());
        assertTrue(cut.isOn());
    }

    @Test
    void constructorShouldInitializeToFalseWithLogicUnitStateFalse() {
        ISignal sig1 = ConstantSignal.ON;
        ISignal sig2 = ConstantSignal.OFF;
        Bus bus = new Bus(sig1, sig2);
        LogicSignal cut = new LogicSignal(sim, bus, new LogicalFalseLogicUnit());
        assertFalse(cut.isOn());
    }

    @Test
    void constructorShouldRegisterListenersForInputSignals() {
        ISignal sig1 = mock(ISignal.class);
        ISignal sig2 = mock(ISignal.class);
        Bus bus = new Bus(sig1, sig2);
        LogicSignal cut = new LogicSignal(sim, bus, new LogicalTrueLogicUnit());
        assertAll(
                () -> verify(sig1, times(1)).addSignalChangeListener(ArgumentMatchers.any()),
                () -> verify(sig2, times(1)).addSignalChangeListener(ArgumentMatchers.any())
        );
    }

    @Test
    void constructorShouldRejectInputBusOfWrongWidth() {
        Bus bus = new Bus(ConstantSignal.ON);
        assertThrows(com.darchan.physical.validation.UnexpectedBusWidthException.class,
                () -> new LogicSignal(sim, bus, new com.darchan.physical.logic.AndGate()));
    }

    @Test
    void shouldReflectSignalChangeWhenInputSignalChanges() {
        Clock clock = new Clock(sim, false);
        Bus bus = new Bus(clock);
        ILogicUnit logicUnit = new ILogicUnit() {
            @Override
            public boolean evaluate(IBus inputBus) {
                //this is the clock. could have referenced it here for clarity,
                //but this is how logic units should be written
                return inputBus.getSignals().get(0).isOn();
            }

            @Override
            public IRange getAllowedInputBusWidth() {
                return new SimpleRange(1, 1);
            }
        };
        LogicSignal cut = new LogicSignal(sim, bus, logicUnit);
        assertAll(
                () -> assertFalse(cut.isOn()),
                () -> {
                    //this tests the logic signal's listening capability. when clock
                    //changes, it will notify the logic signal and use the logic unit
                    //above to set the logic signal's internal state
                    clock.tick();   //make a signal change
                    assertTrue(cut.isOn());
                }
        );
    }

}