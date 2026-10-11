package com.darchan.physical.validation;

import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.IRange;

public final class Validation {

    private Validation(){}

    public static void validateInputWidth(IRange range, IBus bus)  throws UnexpectedBusWidthException {
        int actual = bus.getSignals().size();
        int min = range.getMin();
        int max = range.getMax();
        if (actual < min || actual > max) {
            throw new UnexpectedBusWidthException(min, max, actual);
        }
    }

}
