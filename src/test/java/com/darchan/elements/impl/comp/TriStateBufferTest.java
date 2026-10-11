package com.darchan.elements.impl.comp;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.iface.ISignal;
import com.darchan.elements.iface.LogicValue;
import com.darchan.elements.impl.Bus;
import com.darchan.elements.impl.Clock;
import com.darchan.elements.impl.SimpleSignal;
import com.darchan.elements.impl.Wire;
import com.darchan.sim.Simulator;
import com.darchan.testutil.Buses;
import com.darchan.validation.UnexpectedBusWidthException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.stream.Collectors;

import static com.darchan.elements.iface.LogicValue.*;
import static org.junit.jupiter.api.Assertions.*;

class TriStateBufferTest {

    private final Simulator sim = new Simulator();

    private static List<LogicValue> values(IBus bus) {
        return bus.getSignals().stream().map(ISignal::getValue).collect(Collectors.toList());
    }

    @ParameterizedTest(name = "data={0} enable={1} -> {2}")
    @CsvSource({
            "ZERO, ONE, ZERO", "ONE, ONE, ONE", "Z, ONE, X", "X, ONE, X",
            "ZERO, ZERO, Z", "ONE, ZERO, Z", "X, ZERO, Z",
            "ONE, Z, X", "ONE, X, X"
    })
    void truthTable(LogicValue data, LogicValue enable, LogicValue expected) {
        SimpleSignal d = new SimpleSignal(sim, data);
        SimpleSignal en = new SimpleSignal(sim, enable);
        TriStateBuffer buffer = new TriStateBuffer(sim, new Bus(d), en);
        sim.settle();
        assertEquals(expected, buffer.getOutputBus().getSignal(0).getValue());
    }

    @Test
    void followsDataAndEnable() {
        IBus data = Buses.simpleBus(sim, 8, 0x5A);
        SimpleSignal en = new SimpleSignal(sim, false);
        TriStateBuffer buffer = new TriStateBuffer(sim, data, en);
        sim.settle();
        assertEquals(List.of(Z, Z, Z, Z, Z, Z, Z, Z), values(buffer.getOutputBus()));

        en.setOn(true);
        sim.settle();
        assertEquals(0x5A, Buses.toInt(buffer.getOutputBus()));

        Buses.drive(data, 0xC3);
        sim.settle();
        assertEquals(0xC3, Buses.toInt(buffer.getOutputBus()));

        en.setOn(false);
        sim.settle();
        assertEquals(List.of(Z, Z, Z, Z, Z, Z, Z, Z), values(buffer.getOutputBus()));
    }

    @Test
    void emptyDataBusIsRejected() {
        assertThrows(UnexpectedBusWidthException.class,
                () -> new TriStateBuffer(sim, new Bus(), new SimpleSignal(sim, true)));
    }

    /**
     * Several buffers share one 8-bit bus through resolved wires.
     */
    @Test
    void sharedBusResolvesDrivers() {
        IBus shared = Wire.createBus(sim, 8);
        IBus dataA = Buses.simpleBus(sim, 8, 0xF0);
        IBus dataB = Buses.simpleBus(sim, 8, 0x3C);
        SimpleSignal enA = new SimpleSignal(sim, false);
        SimpleSignal enB = new SimpleSignal(sim, false);
        Wire.connect(shared, new TriStateBuffer(sim, dataA, enA).getOutputBus());
        Wire.connect(shared, new TriStateBuffer(sim, dataB, enB).getOutputBus());
        sim.settle();

        //no driver enabled: the bus floats
        assertEquals(List.of(Z, Z, Z, Z, Z, Z, Z, Z), values(shared));

        //one driver enabled: its value
        enA.setOn(true);
        sim.settle();
        assertEquals(0xF0, Buses.toInt(shared));
        assertTrue(values(shared).stream().allMatch(LogicValue::isKnown));

        //hand the bus over to B
        enA.setOn(false);
        enB.setOn(true);
        sim.settle();
        assertEquals(0x3C, Buses.toInt(shared));

        //both enabled: X where they disagree, the common value where they agree
        enA.setOn(true);
        sim.settle();
        // A = 1111_0000, B = 0011_1100 (listed bit 0 first)
        assertEquals(List.of(ZERO, ZERO, X, X, ONE, ONE, X, X), values(shared));
    }

    @Test
    void registerLoadsFromSharedBus() {
        IBus shared = Wire.createBus(sim, 8);
        IBus dataA = Buses.simpleBus(sim, 8, 0x12);
        IBus dataB = Buses.simpleBus(sim, 8, 0x34);
        SimpleSignal enA = new SimpleSignal(sim, true);
        SimpleSignal enB = new SimpleSignal(sim, false);
        Wire.connect(shared, new TriStateBuffer(sim, dataA, enA).getOutputBus());
        Wire.connect(shared, new TriStateBuffer(sim, dataB, enB).getOutputBus());
        Clock clk = new Clock(sim, false);
        Register reg = new Register(sim, clk, shared, false);
        sim.settle();

        clk.tick();
        assertEquals(0x12, Buses.toInt(reg.getOutputBus()));
        clk.tick();

        enA.setOn(false);
        enB.setOn(true);
        sim.settle();
        clk.tick();
        assertEquals(0x34, Buses.toInt(reg.getOutputBus()));
    }
}
