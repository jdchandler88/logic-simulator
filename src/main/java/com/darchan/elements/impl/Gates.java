package com.darchan.elements.impl;

import com.darchan.elements.iface.ISignal;
import com.darchan.elements.impl.logic.AndGate;
import com.darchan.elements.impl.logic.InverterLogicUnit;
import com.darchan.elements.impl.logic.NandGate;
import com.darchan.elements.impl.logic.NorGate;
import com.darchan.elements.impl.logic.OrGate;
import com.darchan.elements.impl.logic.XorGate;
import com.darchan.sim.Simulator;

/**
 * Shorthand for wiring zero-delay gates: each method returns a new {@link LogicSignal} driven by the gate.
 */
public final class Gates {

    private Gates() {}

    public static ISignal not(Simulator sim, ISignal in) {
        return new LogicSignal(sim, new Bus(in), new InverterLogicUnit());
    }

    public static ISignal and(Simulator sim, ISignal... in) {
        return new LogicSignal(sim, new Bus(in), new AndGate());
    }

    public static ISignal or(Simulator sim, ISignal... in) {
        return new LogicSignal(sim, new Bus(in), new OrGate());
    }

    public static ISignal xor(Simulator sim, ISignal... in) {
        return new LogicSignal(sim, new Bus(in), new XorGate());
    }

    public static ISignal nand(Simulator sim, ISignal... in) {
        return new LogicSignal(sim, new Bus(in), new NandGate());
    }

    public static ISignal nor(Simulator sim, ISignal... in) {
        return new LogicSignal(sim, new Bus(in), new NorGate());
    }
}
