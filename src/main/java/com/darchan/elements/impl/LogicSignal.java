package com.darchan.elements.impl;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ILogicUnit;
import com.darchan.elements.iface.ISignal;
import com.darchan.elements.iface.ISignalChangeListener;
import com.darchan.sim.Simulator;

/**
 * A net driven by a combinational {@link ILogicUnit}: whenever any input changes, the unit is re-evaluated and the
 * result is scheduled onto this signal, either in the next delta cycle (zero delay, the default) or after a
 * propagation delay in simulated time units (transport delay).
 *
 * <p>The input width is validated against the unit at construction
 * ({@link com.darchan.validation.UnexpectedBusWidthException}). The initial value is the unit evaluated on the
 * inputs' values at construction time.
 */
public class LogicSignal extends AbstractSignal {

    private final IBus inputBus;

    private final ILogicUnit logicUnit;

    private final long delay;

    /**
     * delta cycle in which the unit was last evaluated; several inputs changing in the same delta need only one
     * evaluation since they all read the same values
     */
    private long lastEvaluatedDelta = -1;

    private final ISignalChangeListener inputListener = this::listenForSignalChange;

    public LogicSignal(Simulator sim, IBus inputBus, ILogicUnit logicUnit) {
        this(sim, inputBus, logicUnit, 0);
    }

    /**
     * @param delay propagation delay in simulated time units; 0 means the output changes in the next delta cycle
     */
    public LogicSignal(Simulator sim, IBus inputBus, ILogicUnit logicUnit, long delay) {
        super(sim, validateAndEvaluate(inputBus, logicUnit));
        if (delay < 0) {
            throw new IllegalArgumentException("delay must not be negative but was " + delay);
        }
        this.inputBus = inputBus;
        this.logicUnit = logicUnit;
        this.delay = delay;
        for (ISignal inputSignal : this.inputBus.getSignals()) {
            inputSignal.addSignalChangeListener(this.inputListener);
        }
    }

    private static boolean validateAndEvaluate(IBus inputBus, ILogicUnit logicUnit) {
        logicUnit.validateInputBus(inputBus);
        return logicUnit.evaluate(inputBus);
    }

    private void listenForSignalChange(boolean oldValue, boolean newValue) {
        long currentDelta = this.sim.deltaCycle();
        if (currentDelta == this.lastEvaluatedDelta) {
            return;
        }
        this.lastEvaluatedDelta = currentDelta;
        boolean value = this.logicUnit.evaluate(this.inputBus);
        if (this.delay == 0) {
            this.setState(value);
        } else {
            this.sim.scheduleAfter(this.delay, () -> this.setState(value));
        }
    }

}
