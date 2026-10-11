package com.darchan.physical.signal;

public interface ISignal {

	/**
	 * @return {@code true} if the signal is driven high ({@link LogicValue#ONE}); Z and X read as {@code false}
	 */
	public boolean isOn();

	/**
	 * @return the four-valued level of this signal. Two-valued signals need not override this.
	 */
	public default LogicValue getValue() {
		return LogicValue.of(isOn());
	}

	public void addSignalChangeListener(ISignalChangeListener listener);

	public void removeSignalChangeListener(ISignalChangeListener listener);
	
}
