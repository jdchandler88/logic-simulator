package com.darchan.physical.sim;

/**
 * Thrown when a circuit does not become stable within the simulator's delta-cycle limit, typically because of a
 * zero-delay combinational loop that never settles (e.g. a ring of an odd number of inverters).
 */
public class OscillationException extends RuntimeException {

    private final int deltaCycles;

    private final long time;

    public OscillationException(int deltaCycles, long time, String detail) {
        super("Circuit did not settle within " + deltaCycles + " delta cycles (simulated time " + time + "): "
                + detail);
        this.deltaCycles = deltaCycles;
        this.time = time;
    }

    /**
     * @return the delta-cycle limit that was exceeded
     */
    public int getDeltaCycles() {
        return deltaCycles;
    }

    /**
     * @return simulated time at which the limit was exceeded
     */
    public long getTime() {
        return time;
    }
}
