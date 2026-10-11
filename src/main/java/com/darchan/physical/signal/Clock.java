package com.darchan.physical.signal;

import com.darchan.physical.signal.IClock;
import com.darchan.physical.sim.Simulator;

/**
 * Clock signal driven by the testbench. Each {@link #tick()} toggles the clock and settles the simulator, so after
 * a tick every flip-flop on this clock has sampled and all combinational logic is stable.
 */
public class Clock extends AbstractSignal implements IClock {

	public Clock(Simulator sim, boolean initialState) {
		super(sim, initialState);
	}

	@Override
	public void tick() {
		this.setState(!isOn());
		this.sim.settle();
	}

}
