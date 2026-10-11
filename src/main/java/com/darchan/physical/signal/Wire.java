package com.darchan.physical.signal;

import com.darchan.physical.signal.IBus;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.ISignalChangeListener;
import com.darchan.physical.signal.LogicValue;
import com.darchan.physical.sim.Simulator;
import com.darchan.physical.validation.Validation;

import java.util.ArrayList;
import java.util.List;

/**
 * A resolved net with any number of drivers, connected after construction.
 *
 * <ul>
 *     <li><b>Feedback:</b> create the wire, build the logic that reads it, then {@link #connect(ISignal)} the wire to
 *     that logic's output.</li>
 *     <li><b>Shared buses:</b> connect several drivers (typically {@link com.darchan.physical.comp.TriStateBuffer}
 *     outputs). The wire's value is the {@link LogicValue#resolve(LogicValue) resolution} of all drivers: Z when
 *     nothing drives it, the driven value when exactly one driver (or several agreeing ones) drive it, and X when
 *     drivers conflict.</li>
 * </ul>
 * The wire follows its drivers one delta cycle later (zero simulated time). With no driver it is {@link LogicValue#Z}
 * (which reads as {@code isOn() == false}).
 */
public class Wire extends AbstractSignal {

    private final List<ISignal> drivers = new ArrayList<>();

    private long lastResolvedDelta = -1;

    private final ISignalChangeListener driverListener = new ISignalChangeListener() {
        @Override
        public void signalChanged(boolean oldValue, boolean newValue) {
            //not used: valueChanged is overridden so Z/X changes are seen too
        }

        @Override
        public void valueChanged(LogicValue oldValue, LogicValue newValue) {
            long currentDelta = sim.deltaCycle();
            if (currentDelta != lastResolvedDelta) {
                lastResolvedDelta = currentDelta;
                scheduleValue(resolve());
            }
        }
    };

    public Wire(Simulator sim) {
        super(sim, LogicValue.Z);
    }

    /**
     * Creates a bus of {@code width} unconnected wires.
     */
    public static IBus createBus(Simulator sim, int width) {
        ISignal[] wires = new ISignal[width];
        for (int i = 0; i < width; i++) {
            wires[i] = new Wire(sim);
        }
        return new Bus(wires);
    }

    /**
     * Connects {@code drivers.getSignal(i)} as a driver of wire {@code wires.getSignal(i)} for every bit.
     *
     * @param wires bus made of {@link Wire}s
     * @throws com.darchan.physical.validation.UnexpectedBusWidthException if the buses differ in width
     */
    public static void connect(IBus wires, IBus drivers) {
        int width = wires.getSignals().size();
        Validation.validateInputWidth(new SimpleRange(width, width), drivers);
        for (int i = 0; i < width; i++) {
            ((Wire) wires.getSignal(i)).connect(drivers.getSignal(i));
        }
    }

    /**
     * Adds a driver to this wire.
     */
    public void connect(ISignal driver) {
        this.drivers.add(driver);
        driver.addSignalChangeListener(this.driverListener);
        this.scheduleValue(resolve());
    }

    private LogicValue resolve() {
        LogicValue resolved = LogicValue.Z;
        for (int i = 0; i < this.drivers.size(); i++) {
            resolved = resolved.resolve(this.drivers.get(i).getValue());
        }
        return resolved;
    }

}
