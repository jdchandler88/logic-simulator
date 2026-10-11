package com.darchan.behavioral.i8080;

import com.darchan.behavioral.bus.MemoryBus;
import com.darchan.behavioral.bus.ObservedIoBus;
import com.darchan.behavioral.bus.ObservedMemoryBus;
import com.darchan.diag.run.Debugger;

/** Wires a {@link Debugger} to a {@link Cpu8080}. */
public final class Debug8080 {

    private Debug8080() {
    }

    /**
     * Creates a debugger for {@code cpu} and wraps the CPU's memory and I/O buses in observing decorators that
     * report into {@link Debugger#busListener()}, so watchpoints work. Memory dumps read the undecorated bus via
     * {@link MemoryBus#peek}. Call once per CPU.
     */
    public static Debugger attach(Cpu8080 cpu) {
        MemoryBus raw = cpu.memory();
        Debugger debugger = new Debugger(cpu, raw::peek);
        cpu.setMemory(new ObservedMemoryBus(raw, debugger.busListener()));
        cpu.setIo(new ObservedIoBus(cpu.io(), debugger.busListener()));
        return debugger;
    }

    /** Like {@link #attach} but without bus decorators: breakpoints, tracing and dumps work, watchpoints do not. */
    public static Debugger attachWithoutWatchpoints(Cpu8080 cpu) {
        return new Debugger(cpu, cpu.memory()::peek);
    }
}
