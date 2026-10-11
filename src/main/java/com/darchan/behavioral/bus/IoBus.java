package com.darchan.behavioral.bus;

/** The 8080's separate 256-port I/O space, used by {@code IN} and {@code OUT}. */
public interface IoBus {

    /** Nothing connected: reads return 0xFF (a floating, pulled-up data bus), writes are ignored. */
    IoBus UNCONNECTED = new IoBus() {
        @Override
        public int in(int port) {
            return 0xFF;
        }

        @Override
        public void out(int port, int value) {
        }
    };

    /** @return the byte the device at {@code port} (0..255) puts on the bus */
    int in(int port);

    void out(int port, int value);
}
