package com.darchan.diag.run;

/**
 * Hook called by observing bus decorators on every access. Models only pay for it when a decorator is installed,
 * so the CPU fast path stays clean without a debugger.
 */
@FunctionalInterface
public interface BusAccessListener {

    /**
     * @param type    what kind of access
     * @param address memory address or I/O port
     * @param value   the byte read or written
     */
    void onAccess(AccessType type, int address, int value);
}
