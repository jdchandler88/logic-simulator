package com.darchan.elements.impl;

import com.darchan.elements.iface.ISignal;
import com.darchan.sim.Simulator;

/**
 * A net that is declared first and connected to its driver later. This is how feedback is wired: create the wire,
 * build the logic that reads it, then {@link #connect(ISignal)} the wire to the logic's output. The wire follows its
 * driver one delta cycle later (zero simulated time). Before it is connected it reads low.
 */
public class Wire extends AbstractSignal {

    private ISignal driver;

    public Wire(Simulator sim) {
        super(sim, false);
    }

    /**
     * Connects the driver of this wire.
     *
     * @throws IllegalStateException if the wire already has a driver
     */
    public void connect(ISignal driver) {
        if (this.driver != null) {
            throw new IllegalStateException("wire already has a driver");
        }
        this.driver = driver;
        driver.addSignalChangeListener((oldValue, newValue) -> this.setState(newValue));
        this.setState(driver.isOn());
    }

}
