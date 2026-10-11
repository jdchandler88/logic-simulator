package com.darchan.elements.iface;

/**
 * Four-valued logic level of a net.
 *
 * <ul>
 *     <li>{@link #ZERO}, {@link #ONE}: driven low / high.</li>
 *     <li>{@link #Z}: high impedance, i.e. nothing drives the net (a floating bus).</li>
 *     <li>{@link #X}: unknown, e.g. two drivers fighting (one drives 0, the other 1).</li>
 * </ul>
 *
 * <p>{@link #isOn()} is {@code true} only for {@link #ONE}: Z and X read as "not on". This keeps boolean code (all
 * {@link ILogicUnit} gates) simple, at the cost of not propagating X through gates: a gate with a Z or X input treats
 * it as 0. Code that cares about Z/X (bus resolution, tri-state buffers, tests) uses {@link ISignal#getValue()}.
 */
public enum LogicValue {

    ZERO("0"),
    ONE("1"),
    Z("Z"),
    X("X");

    private final String shortName;

    LogicValue(String shortName) {
        this.shortName = shortName;
    }

    public static LogicValue of(boolean on) {
        return on ? ONE : ZERO;
    }

    /**
     * @return {@code true} only for {@link #ONE}
     */
    public boolean isOn() {
        return this == ONE;
    }

    /**
     * @return {@code true} for a driven, known level ({@link #ZERO} or {@link #ONE})
     */
    public boolean isKnown() {
        return this == ZERO || this == ONE;
    }

    /**
     * Wired resolution of two drivers on one net: Z yields to the other driver, equal values agree, and anything else
     * (0 against 1, or any X) is X. Commutative and associative, with Z as identity.
     */
    public LogicValue resolve(LogicValue other) {
        if (this == Z) {
            return other;
        }
        if (other == Z || other == this) {
            return this;
        }
        return X;
    }

    @Override
    public String toString() {
        return shortName;
    }
}
