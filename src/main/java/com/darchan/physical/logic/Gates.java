package com.darchan.physical.logic;

import com.darchan.physical.signal.Bus;
import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.LogicSignal;
import com.darchan.physical.logic.AndGate;
import com.darchan.physical.logic.InverterLogicUnit;
import com.darchan.physical.logic.NandGate;
import com.darchan.physical.logic.NorGate;
import com.darchan.physical.logic.OrGate;
import com.darchan.physical.logic.XorGate;
import com.darchan.physical.sim.Simulator;

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
