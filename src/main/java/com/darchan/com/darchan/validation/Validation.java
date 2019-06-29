package com.darchan.com.darchan.validation;

public final class Validation {

    private Validation(){}

    public static boolean validateInputWidth(int min, int max, int actual) {
        return actual >= min && actual <= max;
    }

}
