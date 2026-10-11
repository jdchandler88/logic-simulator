package com.darchan.elements.iface;

/**
 * Notified when a signal's value changes (in the evaluate phase of a delta cycle).
 */
@FunctionalInterface
public interface ISignalChangeListener {

	/**
	 * Boolean view of a change, with Z and X read as {@code false}. Note that a change between 0, Z and X arrives
	 * as {@code (false, false)}.
	 */
	public void signalChanged(boolean oldValue, boolean newValue);

	/**
	 * Called by signals on every change of their {@link LogicValue}. The default forwards to
	 * {@link #signalChanged(boolean, boolean)}; override it to see Z and X.
	 */
	public default void valueChanged(LogicValue oldValue, LogicValue newValue) {
		signalChanged(oldValue.isOn(), newValue.isOn());
	}
	
}
