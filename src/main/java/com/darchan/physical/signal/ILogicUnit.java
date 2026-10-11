package com.darchan.physical.signal;

import com.darchan.physical.validation.UnexpectedBusWidthException;
import com.darchan.physical.validation.Validation;

import java.util.List;

/**
 * A combinational function of a bus. Implementations are pure: the result depends only on the current values of
 * the input signals.
 *
 * <p>The simulator evaluates units four-valued with {@link #evaluateValue(IBus)} (see {@link LogicValue} for the
 * pessimistic rules the built-in gates follow). A unit that only defines the two-valued {@link #evaluate(IBus)} gets
 * the most pessimistic default: X whenever any input is Z or X.
 *
 * <p>The input width is checked once, when the unit is wired to a bus (see {@link #validateInputBus(IBus)}, called
 * by {@code LogicSignal}'s constructor), not on every {@link #evaluate(IBus)}. Evaluating a bus that was never
 * validated is undefined.
 */
public interface ILogicUnit {

    /**
     * The two-valued view: the result for inputs that are all 0 or 1. For the built-in gates it is
     * {@code evaluateValue(inputBus).isOn()} for any inputs (an X result reads as {@code false}).
     */
    boolean evaluate(IBus inputBus);

    /**
     * The four-valued result. The default is X if any input is Z or X, and otherwise {@link #evaluate(IBus)}; gates
     * override it with their own rules (a controlling input masks unknown ones).
     */
    default LogicValue evaluateValue(IBus inputBus) {
        List<ISignal> signals = inputBus.getSignals();
        for (int i = 0; i < signals.size(); i++) {
            if (!signals.get(i).getValue().isKnown()) {
                return LogicValue.X;
            }
        }
        return LogicValue.of(evaluate(inputBus));
    }

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
