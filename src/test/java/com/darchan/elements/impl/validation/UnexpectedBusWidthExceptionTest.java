package com.darchan.elements.impl.validation;

import com.darchan.com.darchan.validation.UnexpectedBusWidthException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnexpectedBusWidthExceptionTest {

    private UnexpectedBusWidthException cut = new UnexpectedBusWidthException(1, 2, 0);

    @Test
    void minimumShouldBeInput() {
        assertEquals(1, cut.getMinimumWidth());
    }

    @Test
    void maximumShouldBeInput() {
        assertEquals(2, cut.getMaximumWidth());
    }

    @Test
    void receivedShouldBeInput() {
        assertEquals(0, cut.getActualWidth());
    }

    @Test
    void messageShouldBeExpectedValue() {
        assertEquals("Expected bus width of [1, 2] but was 0.", cut.getMessage());
    }

}
