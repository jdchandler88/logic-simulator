package com.darchan.elements.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ClockTest {

    @Test
    public void clockShouldHaveInitialState() {
        assertAll(
            () -> assertTrue(new Clock(true).isOn()),
            () -> assertFalse(new Clock(false).isOn())
        );
    }

    @Test
    public void clockShouldSwitchStateWhenTickCalled() {
        Clock clock = new Clock(false);
        assertAll(
                () -> {
                    clock.tick();
                    assertTrue(clock.isOn());
                },
                () -> {
                    clock.tick();
                    assertFalse(clock.isOn());
                }
        );
    }

}
