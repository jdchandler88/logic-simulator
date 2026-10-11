package com.darchan.physical.signal;

import java.util.Arrays;

import com.darchan.physical.signal.ISignal;
import com.darchan.physical.signal.ISignalChangeListener;
import com.darchan.physical.signal.LogicValue;
import com.darchan.physical.sim.Schedulable;
import com.darchan.physical.sim.Simulator;

/**
 * Base class for signals (nets) whose value is driven during simulation. Writes made with {@link #setState(boolean)}
 * or {@link #scheduleValue(LogicValue)} are scheduled on the owning {@link Simulator} and only become visible, and
 * notify listeners, in the next delta cycle. Listeners are notified on every change of the {@link LogicValue},
 * including changes between 0, Z and X.
 */
public abstract class AbstractSignal implements ISignal, Schedulable {

	private static final ISignalChangeListener[] NO_LISTENERS = new ISignalChangeListener[0];

	protected final Simulator sim;

	/**
	 * Copy-on-write: listeners may be added or removed while an event is being delivered. Each event is
	 * delivered to the listeners registered when delivery started.
	 */
	private ISignalChangeListener[] listeners = NO_LISTENERS;

	/**
	 * current (committed) value
	 */
	private LogicValue value;

	/**
	 * value most recently written; committed in the next delta cycle
	 */
	private LogicValue pendingValue;

	/**
	 * value before the last committed event
	 */
	private LogicValue previousValue;

	private boolean commitScheduled;

	protected AbstractSignal(Simulator sim, boolean initialState) {
		this(sim, LogicValue.of(initialState));
	}

	protected AbstractSignal(Simulator sim, LogicValue initialValue) {
		if (sim == null) {
			throw new IllegalArgumentException("simulator must not be null");
		}
		if (initialValue == null) {
			throw new IllegalArgumentException("initial value must not be null");
		}
		this.sim = sim;
		this.value = initialValue;
		this.pendingValue = initialValue;
		this.previousValue = initialValue;
	}

	/**
	 * Schedules a new value for this signal. It takes effect in the next delta cycle; if written several times
	 * before then, the last write wins.
	 */
	protected final void setState(boolean newState) {
		scheduleValue(LogicValue.of(newState));
	}

	/**
	 * Schedules a new four-valued level for this signal; see {@link #setState(boolean)}.
	 */
	protected final void scheduleValue(LogicValue newValue) {
		if (newValue == null) {
			throw new IllegalArgumentException("value must not be null");
		}
		this.pendingValue = newValue;
		if (!this.commitScheduled) {
			this.commitScheduled = true;
			this.sim.scheduleCommit(this);
		}
	}

	@Override
	public final boolean commit() {
		this.commitScheduled = false;
		if (this.pendingValue == this.value) {
			return false;
		}
		this.previousValue = this.value;
		this.value = this.pendingValue;
		return true;
	}

	@Override
	public final void fireEvent() {
		ISignalChangeListener[] snapshot = this.listeners;
		for (ISignalChangeListener listener : snapshot) {
			listener.valueChanged(this.previousValue, this.value);
		}
	}

	@Override
	public boolean isOn() {
		return this.value.isOn();
	}

	@Override
	public LogicValue getValue() {
		return this.value;
	}

	public Simulator getSimulator() {
		return this.sim;
	}

	@Override
	public void addSignalChangeListener(ISignalChangeListener listener) {
		for (ISignalChangeListener existing : this.listeners) {
			if (existing.equals(listener)) {
				return;
			}
		}
		ISignalChangeListener[] grown = Arrays.copyOf(this.listeners, this.listeners.length + 1);
		grown[grown.length - 1] = listener;
		this.listeners = grown;
	}

	@Override
	public void removeSignalChangeListener(ISignalChangeListener listener) {
		for (int i = 0; i < this.listeners.length; i++) {
			if (this.listeners[i].equals(listener)) {
				ISignalChangeListener[] shrunk = new ISignalChangeListener[this.listeners.length - 1];
				System.arraycopy(this.listeners, 0, shrunk, 0, i);
				System.arraycopy(this.listeners, i + 1, shrunk, i, this.listeners.length - i - 1);
				this.listeners = shrunk;
				return;
			}
		}
	}

}
