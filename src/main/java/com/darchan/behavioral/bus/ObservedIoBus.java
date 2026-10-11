package com.darchan.behavioral.bus;

import com.darchan.diag.run.AccessType;
import com.darchan.diag.run.BusAccessListener;

import java.util.Objects;

/** Decorates an {@link IoBus} and reports every {@code IN}/{@code OUT} to a {@link BusAccessListener}. */
public final class ObservedIoBus implements IoBus {

    private final IoBus inner;
    private final BusAccessListener listener;

    public ObservedIoBus(IoBus inner, BusAccessListener listener) {
        this.inner = Objects.requireNonNull(inner);
        this.listener = Objects.requireNonNull(listener);
    }

    public IoBus inner() {
        return inner;
    }

    @Override
    public int in(int port) {
        int v = inner.in(port);
        listener.onAccess(AccessType.IO_IN, port, v);
        return v;
    }

    @Override
    public void out(int port, int value) {
        inner.out(port, value);
        listener.onAccess(AccessType.IO_OUT, port, value);
    }
}
