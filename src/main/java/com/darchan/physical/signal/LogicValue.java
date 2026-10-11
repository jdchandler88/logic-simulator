package com.darchan.physical.signal;

/**
 * Four-valued logic level of a net.
 *
 * <ul>
 *     <li>{@link #ZERO}, {@link #ONE}: driven low / high.</li>
 *     <li>{@link #Z}: high impedance, i.e. nothing drives the net (a floating bus).</li>
 *     <li>{@link #X}: unknown, e.g. two drivers fighting (one drives 0, the other 1).</li>
 * </ul>
 *
 * <p>Gates evaluate four-valued ({@link ILogicUnit#evaluateValue}) with the usual pessimistic rules, which
 * {@link #and}, {@link #or}, {@link #xor} and {@link #not} implement for two operands:
 * <ul>
 *     <li>a Z input behaves like X (a floating CMOS input is not a defined level);</li>
 *     <li>AND: any 0 gives 0, all 1 gives 1, otherwise X. OR: any 1 gives 1, all 0 gives 0, otherwise X. NAND and
 *     NOR are their inversions;</li>
 *     <li>NOT, XOR: any Z or X input gives X.</li>
 * </ul>
 * So a controlling value (0 into AND, 1 into OR) masks an unknown input, and anything else unknown propagates.
 *
 * <p>{@link #isOn()} is {@code true} only for {@link #ONE}: Z and X read as "not on". Boolean code that only ever
 * sees 0 and 1 can keep using it; code that cares about Z/X uses {@link ISignal#getValue()}.
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

    /** four-valued AND (see the class comment) */
    public LogicValue and(LogicValue other) {
        if (this == ZERO || other == ZERO) {
            return ZERO;
        }
        return this == ONE && other == ONE ? ONE : X;
    }

    /** four-valued OR (see the class comment) */
    public LogicValue or(LogicValue other) {
        if (this == ONE || other == ONE) {
            return ONE;
        }
        return this == ZERO && other == ZERO ? ZERO : X;
    }

    /** four-valued XOR: X if either operand is Z or X */
    public LogicValue xor(LogicValue other) {
        if (!isKnown() || !other.isKnown()) {
            return X;
        }
        return this == other ? ZERO : ONE;
    }

    /** four-valued NOT: 0 and 1 swap, Z and X give X */
    public LogicValue not() {
        return this == ZERO ? ONE : this == ONE ? ZERO : X;
    }

    @Override
    public String toString() {
        return shortName;
    }
}
