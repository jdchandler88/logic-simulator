package com.darchan.elements.impl;

import java.util.Arrays;

import com.darchan.elements.iface.ISignal;
import com.darchan.elements.iface.ISignalChangeListener;
import com.darchan.sim.Schedulable;
import com.darchan.sim.Simulator;

/**
 * Base class for signals (nets) whose value is driven during simulation. Writes made with {@link #setState(boolean)}
 * are scheduled on the owning {@link Simulator} and only become visible, and notify listeners, in the next delta
 * cycle.
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
	protected boolean state;

	/**
	 * value most recently written; committed in the next delta cycle
	 */
	private boolean pendingState;

	/**
	 * value before the last committed event
	 */
	private boolean previousState;

	private boolean commitScheduled;

	protected AbstractSignal(Simulator sim, boolean initialState) {
		if (sim == null) {
			throw new IllegalArgumentException("simulator must not be null");
		}
		this.sim = sim;
		this.state = initialState;
		this.pendingState = initialState;
	}

	/**
	 * Schedules a new value for this signal. It takes effect in the next delta cycle; if written several times
	 * before then, the last write wins.
	 */
	protected final void setState(boolean newState) {
		this.pendingState = newState;
		if (!this.commitScheduled) {
			this.commitScheduled = true;
			this.sim.scheduleCommit(this);
		}
	}

	@Override
	public final boolean commit() {
		this.commitScheduled = false;
		if (this.pendingState == this.state) {
			return false;
		}
		this.previousState = this.state;
		this.state = this.pendingState;
		return true;
	}

	@Override
	public final void fireEvent() {
		ISignalChangeListener[] snapshot = this.listeners;
		for (ISignalChangeListener listener : snapshot) {
			listener.signalChanged(this.previousState, this.state);
		}
	}

	@Override
	public boolean isOn() {
		return this.state;
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
