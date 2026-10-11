package com.darchan.elements.impl.comp.cpu.intel8080;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.impl.Clock;
import com.darchan.elements.impl.SimpleRange;
import com.darchan.elements.impl.Wire;
import com.darchan.elements.impl.comp.BusSelector8By8Bit;
import com.darchan.elements.impl.comp.cpu.intel8080.RegisterFile.Reg;
import com.darchan.sim.Simulator;
import com.darchan.testutil.Buses;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class RegisterFileTest {

    private final Simulator sim = new Simulator();

    private final Clock clk = new Clock(sim, false);

    /** data-in [7:0], write enables [15:8] */
    private final IBus inputBus = Buses.simpleBus(sim, 16, 0);

    private final IBus dataIn = inputBus.slice(new SimpleRange(0, 7));

    private final IBus writeEnables = inputBus.slice(new SimpleRange(8, 15));

    private final RegisterFile rf = new RegisterFile(sim, clk, inputBus);

    /** full clock cycle: rising then falling edge */
    private void cycle() {
        clk.tick();
        clk.tick();
    }

    private void write(int enables, int data) {
        Buses.drive(writeEnables, enables);
        Buses.drive(dataIn, data);
        sim.settle();
        cycle();
        Buses.drive(writeEnables, 0);
        sim.settle();
    }

    private int value(Reg reg) {
        return Buses.toInt(rf.getRegisterOutput(reg));
    }

    @Test
    void registersHaveInitialValue() {
        //all registers should be set to all 0
        for (Reg reg : Reg.values()) {
            assertEquals(0, value(reg), reg.name());
        }
        assertEquals(64, rf.getOutputBus().getSignals().size());
    }

    @ParameterizedTest
    @EnumSource(Reg.class)
    void eachRegisterLoadsOnlyWhenEnabled(Reg target) {
        Buses.drive(dataIn, 0xA5);
        Buses.drive(writeEnables, 1 << target.slot());
        sim.settle();
        //the enable alone (no clock edge) must not load
        assertEquals(0, value(target), "no clock edge yet");

        clk.tick();
        for (Reg reg : Reg.values()) {
            assertEquals(reg == target ? 0xA5 : 0, value(reg), reg.name());
        }
        clk.tick();

        //enable low: holds while data changes and the clock runs
        Buses.drive(writeEnables, 0);
        Buses.drive(dataIn, 0x5A);
        sim.settle();
        cycle();
        cycle();
        for (Reg reg : Reg.values()) {
            assertEquals(reg == target ? 0xA5 : 0, value(reg), reg.name());
        }
    }

    @Test
    void togglingAnEnableWithoutAClockEdgeDoesNotLoad() {
        //with the old gated-clock design, raising a latch line was itself a load
        Buses.drive(dataIn, 0xFF);
        sim.settle();
        for (int i = 0; i < 8; i++) {
            Buses.drive(writeEnables, 1 << i);
            sim.settle();
            Buses.drive(writeEnables, 0);
            sim.settle();
        }
        clk.tick();
        Buses.drive(writeEnables, 0xFF);
        sim.settle();
        Buses.drive(writeEnables, 0);
        sim.settle();
        clk.tick();
        for (Reg reg : Reg.values()) {
            assertEquals(0, value(reg), reg.name());
        }
    }

    @Test
    void severalEnabledRegistersLoadTheSameData() {
        write((1 << Reg.B.slot()) | (1 << Reg.H.slot()), 0x3C);
        for (Reg reg : Reg.values()) {
            assertEquals(reg == Reg.B || reg == Reg.H ? 0x3C : 0, value(reg), reg.name());
        }
    }

    @Test
    void outputLayoutPutsSlotIAtBits8iAndPairsHighRegisterHigh() {
        int[] values = new int[8];
        for (Reg reg : Reg.values()) {
            values[reg.slot()] = 0x11 * (reg.slot() + 1) ^ 0x80;
            write(1 << reg.slot(), values[reg.slot()]);
        }
        IBus out = rf.getOutputBus();
        for (Reg reg : Reg.values()) {
            int lo = 8 * reg.slot();
            assertEquals(values[reg.slot()], Buses.toInt(out.slice(new SimpleRange(lo, lo + 7))), reg.name());
        }
        assertAll(
                //documented slot order L, H, E, D, C, B, F, A
                () -> assertArrayEquals(new Reg[]{Reg.L, Reg.H, Reg.E, Reg.D, Reg.C, Reg.B, Reg.F, Reg.A},
                        Reg.values()),
                () -> assertEquals(value(Reg.H) << 8 | value(Reg.L),
                        Buses.toInt(out.slice(new SimpleRange(0, 15))), "HL"),
                () -> assertEquals(value(Reg.D) << 8 | value(Reg.E),
                        Buses.toInt(out.slice(new SimpleRange(16, 31))), "DE"),
                () -> assertEquals(value(Reg.B) << 8 | value(Reg.C),
                        Buses.toInt(out.slice(new SimpleRange(32, 47))), "BC"),
                () -> assertEquals(value(Reg.A) << 8 | value(Reg.F),
                        Buses.toInt(out.slice(new SimpleRange(48, 63))), "PSW")
        );
    }

    /**
     * MOV dst,src in one clock: the register file's output feeds a {@link BusSelector8By8Bit} whose output is the
     * register file's data-in (a combinational loop through the registers, broken by the clock edge).
     */
    @Test
    void registerToRegisterMoveThroughBusSelector() {
        Simulator sim = new Simulator();
        Clock clk = new Clock(sim, false);
        IBus dataIn = Wire.createBus(sim, 8);
        IBus enables = Buses.simpleBus(sim, 8, 0);
        IBus rfIn = new com.darchan.elements.impl.Bus(
                java.util.stream.Stream.concat(dataIn.getSignals().stream(), enables.getSignals().stream())
                        .toArray(com.darchan.elements.iface.ISignal[]::new));
        RegisterFile rf = new RegisterFile(sim, clk, rfIn);
        IBus select = Buses.simpleBus(sim, 8, 0);
        IBus muxIn = new com.darchan.elements.impl.Bus(
                java.util.stream.Stream.concat(rf.getOutputBus().getSignals().stream(), select.getSignals().stream())
                        .toArray(com.darchan.elements.iface.ISignal[]::new));
        BusSelector8By8Bit mux = new BusSelector8By8Bit(sim, muxIn);
        Wire.connect(dataIn, mux.getOutputBus());

        //preload via a separate driver on the data-in wires: C = 0x42, B = 0x17
        IBus loader = Buses.simpleBus(sim, 8, 0);
        IBus loaderEnable = Buses.simpleBus(sim, 1, 0);
        Wire.connect(dataIn, new com.darchan.elements.impl.comp.TriStateBuffer(sim, loader,
                loaderEnable.getSignal(0)).getOutputBus());
        sim.settle();
        for (int[] load : new int[][]{{Reg.C.slot(), 0x42}, {Reg.B.slot(), 0x17}}) {
            Buses.drive(loader, load[1]);
            Buses.drive(loaderEnable, 1);
            Buses.drive(enables, 1 << load[0]);
            sim.settle();
            clk.tick();
            clk.tick();
        }
        Buses.drive(loaderEnable, 0);
        Buses.drive(enables, 0);
        sim.settle();
        assertEquals(0x42, Buses.toInt(rf.getRegisterOutput(Reg.C)));
        assertEquals(0x17, Buses.toInt(rf.getRegisterOutput(Reg.B)));

        //MOV B,C
        Buses.drive(select, 1 << Reg.C.slot());
        Buses.drive(enables, 1 << Reg.B.slot());
        sim.settle();
        assertEquals(0x42, Buses.toInt(dataIn));
        clk.tick();
        assertEquals(0x42, Buses.toInt(rf.getRegisterOutput(Reg.B)));
        clk.tick();

        //MOV B,B and MOV A,B on the same edge: B is source and destination and keeps its pre-edge value
        Buses.drive(select, 1 << Reg.B.slot());
        Buses.drive(enables, (1 << Reg.A.slot()) | (1 << Reg.B.slot()));
        sim.settle();
        clk.tick();
        assertAll(
                () -> assertEquals(0x42, Buses.toInt(rf.getRegisterOutput(Reg.A))),
                () -> assertEquals(0x42, Buses.toInt(rf.getRegisterOutput(Reg.B))),
                () -> assertEquals(0x42, Buses.toInt(rf.getRegisterOutput(Reg.C)))
        );
    }
}
