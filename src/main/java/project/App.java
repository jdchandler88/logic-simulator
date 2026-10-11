package project;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.IClock;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.Clock;
import com.darchan.elements.impl.SignalNameDecorator;
import com.darchan.elements.impl.comp.Inverter;
import com.darchan.sim.Simulator;

/**
 * Small demo: a clock driving an inverter.
 */
public class App {

	public String getGreeting() {
        return "Hello world.";
    }

    public static void main(String[] args) {
    	Simulator sim = new Simulator();

    	//create clock source
    	IClock src = new Clock(sim, false);
    	IBus inputBus = new Bus(src);

    	//create inverter
    	Inverter inverter = new Inverter(sim, inputBus);

    	for (int i=0; i<10; i++) {
    		System.out.print(new SignalNameDecorator(src, "CLOCK"));
    		System.out.print(" ");
        	System.out.print(new SignalNameDecorator(inverter.getOutputBus().getSignals().get(0), "OUT"));
        	System.out.println();
        	//tick() toggles the clock and settles the circuit
        	src.tick();
    	}
    }

}
