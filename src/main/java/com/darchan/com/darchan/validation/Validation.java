package com.darchan.com.darchan.validation;

import com.darchan.elements.iface.IBus;

public final class Validation {

    private Validation(){}

    public static void validateInputWidth(int min, int max, IBus bus)  throws UnexpectedBusWidthException {
        int actual = bus.getSignals().size();
        if (actual < min || actual > max) {
            throw new UnexpectedBusWidthException(min, max, actual);
        }
    }

}
