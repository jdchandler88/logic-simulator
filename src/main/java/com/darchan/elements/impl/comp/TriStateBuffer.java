package com.darchan.elements.impl.comp;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.IComponent;
import com.darchan.elements.iface.IRange;
import com.darchan.elements.iface.ISignal;
import com.darchan.elements.iface.ISignalChangeListener;
import com.darchan.elements.iface.LogicValue;
import com.darchan.elements.impl.AbstractSignal;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.SimpleRange;
import com.darchan.sim.Simulator;
import com.darchan.validation.Validation;

/**
 * N-bit tri-state buffer: while {@code enable} is high each output bit follows its data bit, while it is low the
 * outputs are high impedance ({@link LogicValue#Z}). Connect the outputs of several buffers to the same
 * {@link com.darchan.elements.impl.Wire}s to build a shared bus.
 *
 * <table>
 *     <caption>Output per bit</caption>
 *     <tr><th>enable</th><th>data 0/1</th><th>data Z/X</th></tr>
 *     <tr><td>1</td><td>data</td><td>X</td></tr>
 *     <tr><td>0</td><td>Z</td><td>Z</td></tr>
 *     <tr><td>Z/X</td><td>X</td><td>X</td></tr>
 * </table>
 *
 * Input bus: the data bits (width 1 or more). Output bus: same width as the input.
 */
public class TriStateBuffer implements IComponent {

    private static final IRange ALLOWED_BUS_WIDTH = new SimpleRange(1, Integer.MAX_VALUE);

    private final IBus inputBus;

    private final ISignal enable;

    private final IBus outputBus;

    /**
     * @param sim simulator
     * @param inputBus data bits
     * @param enable output enable (active high)
     */
    public TriStateBuffer(Simulator sim, IBus inputBus, ISignal enable) {
        Validation.validateInputWidth(ALLOWED_BUS_WIDTH, inputBus);
        this.inputBus = inputBus;
        this.enable = enable;
        int width = inputBus.getSignals().size();
        ISignal[] outputs = new ISignal[width];
        for (int i = 0; i < width; i++) {
            outputs[i] = new Output(sim, inputBus.getSignal(i), enable);
        }
        this.outputBus = new Bus(outputs);
    }

    /**
     * Output level of one bit for the given data and enable levels.
     */
    static LogicValue drive(LogicValue data, LogicValue enable) {
        switch (enable) {
            case ONE:
                return data.isKnown() ? data : LogicValue.X;
            case ZERO:
                return LogicValue.Z;
            default:
                return LogicValue.X;
        }
    }

    public ISignal getEnable() {
        return this.enable;
    }

    @Override
    public IBus getInputBus() {
        return this.inputBus;
    }

    @Override
    public IRange getAllowedInputBusWidth() {
        return ALLOWED_BUS_WIDTH;
    }

    @Override
    public IBus getOutputBus() {
        return this.outputBus;
    }

    @Override
    public IRange getAllowedOutputBusWidth() {
        return ALLOWED_BUS_WIDTH;
    }

    /**
     * one output bit
     */
    private static final class Output extends AbstractSignal {

        private final ISignal data;

        private final ISignal enable;

        private long lastEvaluatedDelta = -1;

        Output(Simulator sim, ISignal data, ISignal enable) {
            super(sim, drive(data.getValue(), enable.getValue()));
            this.data = data;
            this.enable = enable;
            ISignalChangeListener listener = new ISignalChangeListener() {
                @Override
                public void signalChanged(boolean oldValue, boolean newValue) {
                    //not used: valueChanged is overridden so Z/X changes are seen too
                }

                @Override
                public void valueChanged(LogicValue oldValue, LogicValue newValue) {
                    update();
                }
            };
            data.addSignalChangeListener(listener);
            enable.addSignalChangeListener(listener);
        }

        private void update() {
            long currentDelta = this.sim.deltaCycle();
            if (currentDelta != this.lastEvaluatedDelta) {
                this.lastEvaluatedDelta = currentDelta;
                this.scheduleValue(drive(this.data.getValue(), this.enable.getValue()));
            }
        }
    }
}
