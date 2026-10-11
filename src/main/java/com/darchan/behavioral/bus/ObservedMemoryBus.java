package com.darchan.behavioral.bus;

import com.darchan.diag.run.AccessType;
import com.darchan.diag.run.BusAccessListener;

import java.util.Objects;

/**
 * Decorates a {@link MemoryBus} and reports every fetch, read and write to a {@link BusAccessListener} (typically
 * {@code Debugger.busListener()} for watchpoints). Peeks are passed through silently. Install it only while
 * debugging; the undecorated bus costs nothing extra.
 */
public final class ObservedMemoryBus implements MemoryBus {

    private final MemoryBus inner;
    private final BusAccessListener listener;

    public ObservedMemoryBus(MemoryBus inner, BusAccessListener listener) {
        this.inner = Objects.requireNonNull(inner);
        this.listener = Objects.requireNonNull(listener);
    }

    public MemoryBus inner() {
        return inner;
    }

    @Override
    public int read(int address) {
        int v = inner.read(address);
        listener.onAccess(AccessType.READ, address, v);
        return v;
    }

    @Override
    public void write(int address, int value) {
        inner.write(address, value);
        listener.onAccess(AccessType.WRITE, address, value);
    }

    @Override
    public int fetch(int address) {
        int v = inner.fetch(address);
        listener.onAccess(AccessType.FETCH, address, v);
        return v;
    }

    @Override
    public int peek(int address) {
        return inner.peek(address);
    }
}
