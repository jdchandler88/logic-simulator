package com.darchan.physical.sim;

import com.darchan.physical.signal.LogicValue;

/**
 * A storage element of a gate-level circuit (a flip-flop, or a group of them such as a register) whose stored
 * levels can be read and forced from outside, so the circuit's state can be saved and restored.
 *
 * <p><b>Discovery:</b> storage components register themselves with their {@link Simulator} when they are constructed
 * ({@link Simulator#register(StateElement)}); {@link Simulator#stateElements()} lists them in construction order.
 * Nothing has to be listed by hand, so a snapshot of the circuit cannot silently leave one out.
 *
 * <p><b>Names</b> are hierarchical, dot-separated segments of letters, digits and {@code _} (like probe names),
 * unique within the simulator. A component that builds storage names it from its own name (a register file names its
 * registers {@code <file>.A}, {@code <file>.B} ...); components built without a name get one from
 * {@link Simulator#uniqueName(String)} ({@code ff0}, {@code reg1}, {@code counter0}, numbered in construction order),
 * so identically built circuits get identical names and a snapshot of one can be restored into the other.
 */
public interface StateElement {

    /** hierarchical name, unique within the simulator */
    String name();

    /** number of stored bits, 1..64 */
    int width();

    /** the stored level of bit {@code bit} (0 = least significant) */
    LogicValue get(int bit);

    /**
     * Overwrites the stored level of bit {@code bit}. Like any write the new level is scheduled: it is visible, and
     * the logic it drives re-evaluated, after the simulator runs ({@link Simulator#settle()}).
     */
    void force(int bit, LogicValue value);
}
