package com.darchan.elements.impl.comp;

import com.darchan.elements.iface.*;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.SimpleRange;
import com.darchan.elements.impl.SimpleSignal;

import java.util.Collections;
import java.util.stream.IntStream;

/**
 * Class that is a multiplexer for 8-bit buses. The input consists of an 8-bit select signal and 8 8-bit buses. The
 * output consists of one 8-bit bus that is the selected 8-bit input
 */
public class BusSelector8By8Bit implements IComponent {

    /**
     * enum representing data selection
     */
    private enum DATA_SELECTOR {

        DATA_0(new SimpleRange(0, 7)),
        DATA_1(new SimpleRange(8, 15)),
        DATA_2(new SimpleRange(16, 23)),
        DATA_3(new SimpleRange(24, 31)),
        DATA_4(new SimpleRange(32, 39)),
        DATA_5(new SimpleRange(40, 47)),
        DATA_6(new SimpleRange(48, 55)),
        DATA_7(new SimpleRange(56, 63)),
        FLOATING(new SimpleRange(-1, -1));

        /**
         * range represented by enum value
         */
        private IRange range;

        /**
         * create enum with range
         * @param range range represented by enum value
         */
        DATA_SELECTOR(IRange range) {
            this.range = range;
        }

        /**
         * gets range
         * @return range
         */
        public IRange getRange() {
            return this.range;
        }

    }

    /**
     * allowed input bus width. 8 bits for selecting and 64 bits for input to the multiplexer
     */
    private static final IRange ALLOWED_INPUT_BUS_WIDTH = new SimpleRange(72, 72);

    /**
     * allowed output bus width. 8-bit selection
     */
    private static final IRange ALLOWED_OUTPUT_BUS_WIDTH = new SimpleRange(8, 8);

    /**
     * range on input bus that indicate the select bits
     */
    private static final IRange SELECT_BITS_RANGE = new SimpleRange(64, 71);

    /**
     * input bus
     */
    private final IBus inputBus;

    /**
     * output bus
     */
    private final IBus outputBus;

    /**
     * change listener for input signals
     */
    private final ISignalChangeListener signalChangeListener;

    /**
     * currently-selected 8-bit block
     */
    private DATA_SELECTOR currentDataSelection;

    /**
     * Creates selector using the specified input bus
     * @param inputBus input bus
     */
    public BusSelector8By8Bit(IBus inputBus) {
        this.inputBus = inputBus;
        //wire select bits to selection logic
        for (int i=SELECT_BITS_RANGE.getMin(); i<=SELECT_BITS_RANGE.getMax(); i++) {
            inputBus.getSignal(i).addSignalChangeListener(((oldValue, newValue) -> {
                selectOutput();
            }));
        }

        //create output bus
        ISignal[] outputSignals = new ISignal[8];
        for (int i=0; i<8; i++) {
            outputSignals[i] = new SimpleSignal(false);
        }
        this.outputBus = new Bus(outputSignals);

        //configure signal change listener
        this.signalChangeListener = (oldValue, newValue) -> this.updateOutputBus();

        //do first determination of selection
        this.currentDataSelection = getSelector(getSelectSignals());

        //connect input bus to outputbus
        this.selectOutput();
    }

    @Override
    public IBus getInputBus() {
        return inputBus;
    }

    @Override
    public IRange getAllowedInputBusWidth() {
        return null;
    }

    @Override
    public IBus getOutputBus() {
        return outputBus;
    }

    @Override
    public IRange getAllowedOutputBusWidth() {
        return null;
    }

    private void selectOutput() {
        //only one select bit should be on at a time. however, we will treat this as a short-circuit. the highest bit
        //that is active will indicate the selected register
        IBus selectSignals = inputBus.slice(SELECT_BITS_RANGE);

        DATA_SELECTOR selector = getSelector(selectSignals);

        assignInputToOutputBus(selector);
    }

    private void assignInputToOutputBus(DATA_SELECTOR selector) {
        //remove listeners from previously-selected input IF IT's NOT FLOATING! there are no connections when floating
        if (this.currentDataSelection != DATA_SELECTOR.FLOATING) {
            IBus previouslySelectedInput = inputBus.slice(this.currentDataSelection.getRange());
            for (int i=0; i<8; i++) {
                previouslySelectedInput.getSignal(i).removeSignalChangeListener(this.signalChangeListener);
            }
        }

        //store the range as selected
        this.currentDataSelection = selector;

        //add change listeners and assign input to output. if the current selection is FLOATING, then don't make any connections
        if (this.currentDataSelection != DATA_SELECTOR.FLOATING) {
            IBus toAssign = inputBus.slice(this.currentDataSelection.getRange());
            for (int i=0; i<8; i++) {
                //add signal change listener to current data
                toAssign.getSignal(i).addSignalChangeListener(this.signalChangeListener);
            }
        }

        //make the assignments
        updateOutputBus();

    }

    private void updateOutputBus() {
        if (this.currentDataSelection == DATA_SELECTOR.FLOATING) {
            //in reality, floating is a specific value. but, we're just going to assign to false for now
            IntStream.range(0, 8)
                    .mapToObj(i -> this.outputBus.getSignal(i))
                    .map(sig -> (SimpleSignal)sig)
                    .forEach(sig -> sig.setOn(false));
        } else {
            IBus toAssign = inputBus.slice(this.currentDataSelection.getRange());
            for (int i=0; i<8; i++) {
                ((SimpleSignal)this.outputBus.getSignal(i)).setOn(toAssign.getSignal(i).isOn());
            }
        }
    }

    private static DATA_SELECTOR getSelector(IBus selectSignal) {
        return IntStream.range(0, selectSignal.getSignals().size())
                .boxed()
                .sorted(Collections.reverseOrder())
                .filter(i -> selectSignal.getSignal(i).isOn())
                .map(i -> DATA_SELECTOR.values()[i])
                .findFirst()
                .orElse(DATA_SELECTOR.FLOATING);
    }

    private IBus getSelectSignals() {
        return this.getInputBus().slice(SELECT_BITS_RANGE);
    }

}
