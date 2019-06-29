package com.darchan.com.darchan.validation;

public class UnexpectedBusWidthException extends RuntimeException {

    private final int minimumWidth;

    private final int maximumWidth;

    private final int actualWidth;

    public UnexpectedBusWidthException(int minimumWidth, int maximumWidth, int actualWidth) {
        super("Expected bus width of [" + minimumWidth + ", " + maximumWidth + "] but was " + actualWidth + ".");
        this.minimumWidth = minimumWidth;
        this.maximumWidth = maximumWidth;
        this.actualWidth = actualWidth;
    }

    public int getMinimumWidth() {
        return minimumWidth;
    }

    public int getMaximumWidth() {
        return maximumWidth;
    }

    public int getActualWidth() {
        return actualWidth;
    }

}
