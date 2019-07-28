package com.darchan.elements.impl.com.darchan.elements.impl.comp;

import com.darchan.elements.impl.Clock;
import com.darchan.elements.impl.com.darchan.TestSignal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.fail;

class FlipFlopTest {


    @ParameterizedTest
    @CsvSource(value={"true", "false"})
    void outputShouldBeSuppliedInitialValue(boolean initialValue) {
        fail("not yet implemented");
    }

    @ParameterizedTest
    //initialValue, newValue
    @CsvSource(value={"false, false", "false, true", "true, false", "true, true"})
    void outputShouldChangeWhenClockChanges(boolean initialValue, boolean newValue) {
        //input signals for flipflop
        Clock clk = new Clock(false);
        TestSignal inputSignal = new TestSignal(newValue);

        //create clock with input signals and initial value

        //tick clock to trigger change

        //assert that clock has the new value
        fail("not yet implemented");
    }

}
