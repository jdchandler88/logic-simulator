package com.darchan.elements.impl.com.darchan.util;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.IRange;
import com.darchan.elements.iface.ISignal;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.SimpleRange;
import com.darchan.elements.impl.SimpleSignal;
import com.darchan.util.Util;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UtilTest {

    //sweep across bus in 8-bit intervals and check two 'random' slices
    @ParameterizedTest
    @CsvSource(value = {"0,7", "8,15", "16,23", "24,31", "0,23", "13, 29"})
    void sliceGetsCorrectSignals(int min, int max) {
        //create range obj with inputs
        IRange range = new SimpleRange(min, max);

        //create bus of width 64 with random values
        Random r = new Random();
        int busWidth = 32;
        ISignal[] sigs = new ISignal[busWidth];
        for (int i=0; i<busWidth; i++) {
            sigs[i] = new SimpleSignal(r.nextBoolean());
        }
        IBus busToSlice = new Bus(sigs);

        //sliceBus the bus
        IBus result = Util.sliceBus(range, busToSlice);

        //need to get values from sliceBus to test
        int rangeWidth = range.getMax() - range.getMin();
        assertEquals(collectValuesFromBus(range, busToSlice), collectValuesFromBus(new SimpleRange(0, rangeWidth), result));

        //sweep in 8-bit slices across the bus to see if we're getting the correct range from the sliceBus util
        int numBitsInSweep = 8;
        int numSweeps = busWidth/numBitsInSweep;
        for (int i=0; i<numSweeps; i++) {
            int start = i*numBitsInSweep;
            int end = start + numBitsInSweep - 1;
            IBus slice = Util.sliceBus(new SimpleRange(start, end), busToSlice);
        }
    }

    private static List<Boolean> collectValuesFromBus(IRange range, IBus bus) {
        List<Boolean> values = new ArrayList<>();
        for (int i=range.getMin(); i<range.getMax(); i++) {
            values.add(bus.getSignals().get(i).isOn());
        }
        return values;
    }

}
