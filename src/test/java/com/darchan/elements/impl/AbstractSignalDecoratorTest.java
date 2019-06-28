package com.darchan.elements.impl;

import com.darchan.elements.iface.ISignal;
import com.darchan.elements.iface.ISignalChangeListener;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

import static org.junit.jupiter.api.Assertions.*;

class AbstractSignalDecoratorTest {

    @Test
    void isOnShouldReturnTrueIsWrappedIsTrue() {
        ISignal mockedSignal = mock(ISignal.class);
        when(mockedSignal.isOn()).thenReturn(true);
        AbstractSignalDecorator cut = new AbstractSignalDecorator(mockedSignal) {};
        assertAll(
                () -> assertTrue(cut.isOn()),
                () -> verify(mockedSignal, times(1)).isOn()
        );
    }

    @Test
    void wrappedAddSignalChangeListenerShouldBeCalled() {
        ISignal mockedSignal = mock(ISignal.class);
        when(mockedSignal.isOn()).thenReturn(true);
        AbstractSignalDecorator cut = new AbstractSignalDecorator(mockedSignal) {};
        ISignalChangeListener listener = (oldValue, newValue) -> {};
        cut.addSignalChangeListener(listener);
        verify(mockedSignal, times(1)).addSignalChangeListener(listener);
    }

    @Test
    void wrappedRemoveSignalChangeListenerShouldBeCalled() {
        ISignal mockedSignal = mock(ISignal.class);
        when(mockedSignal.isOn()).thenReturn(true);
        AbstractSignalDecorator cut = new AbstractSignalDecorator(mockedSignal) {};
        ISignalChangeListener listener = (oldValue, newValue) -> {};
        cut.removeSignalChangeListener(listener);
        verify(mockedSignal, times(1)).removeSignalChangeListener(listener);
    }

}