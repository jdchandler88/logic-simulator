package com.darchan.elements.impl;

import com.darchan.elements.iface.IRange;

/**
 * Class used in communicating
 */
public class SimpleRange implements IRange {

    /**
     * min number of signals on bus
     */
    private final int min;

    /**
     * max number of signals on bus
     */
    private final int max;

    /**
     * Creates range with supplied values
     * @param min minimum number of allowed signals on bus
     * @param max maximum number of allowed signals on bus
     */
    public SimpleRange(int min, int max) {
        this.min = min;
        this.max = max;
    }

    /**
     * Gets the minimum allowed number of signals on bus
     * @return min
     */
    @Override
    public int getMin() {
        return min;
    }

    /**
     * Gets maximum allowed number of signals on bus
     * @return max
     */
    @Override
    public int getMax() {
        return max;
    }

}
