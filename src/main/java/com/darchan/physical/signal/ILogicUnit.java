package com.darchan.physical.signal;

import com.darchan.physical.validation.UnexpectedBusWidthException;
import com.darchan.physical.validation.Validation;

/**
 * A combinational function of a bus. Implementations are pure: the result depends only on the current values of
 * the input signals.
 *
 * <p>The input width is checked once, when the unit is wired to a bus (see {@link #validateInputBus(IBus)}, called
 * by {@code LogicSignal}'s constructor), not on every {@link #evaluate(IBus)}. Evaluating a bus that was never
 * validated is undefined.
 */
public interface ILogicUnit {

    boolean evaluate(IBus inputBus);

    /**
     * @return the range of input widths this unit accepts (never null)
     */
    IRange getAllowedInputBusWidth();

    /**
     * @throws UnexpectedBusWidthException if {@code inputBus} is not within {@link #getAllowedInputBusWidth()}
     */
    default void validateInputBus(IBus inputBus) {
        Validation.validateInputWidth(getAllowedInputBusWidth(), inputBus);
    }

}
