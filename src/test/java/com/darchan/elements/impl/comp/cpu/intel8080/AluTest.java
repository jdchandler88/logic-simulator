package com.darchan.elements.impl.comp.cpu.intel8080;

import com.darchan.elements.iface.IBus;
import com.darchan.elements.impl.comp.cpu.intel8080.Alu.Op;
import com.darchan.sim.Simulator;
import com.darchan.testutil.Buses;
import com.darchan.validation.UnexpectedBusWidthException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static com.darchan.elements.impl.comp.cpu.intel8080.Alu8080Reference.*;
import static org.junit.jupiter.api.Assertions.*;

class AluTest {

    private final Simulator sim = new Simulator();

    private final IBus in = Buses.simpleBus(sim, 20, 0);

    private final IBus a = in.slice(Alu.OPERAND_A);

    private final IBus b = in.slice(Alu.OPERAND_B);

    private final IBus carryIn = in.slice(Alu.CARRY_IN);

    private final IBus op = in.slice(Alu.OPERATION);

    private final Alu alu = new Alu(sim, in);

    /** @return {@code (flags << 8) | result} from the gate-level ALU */
    private int run(Op operation, int x, int y, boolean cy) {
        Buses.drive(op, operation.code());
        Buses.drive(a, x);
        Buses.drive(b, y);
        Buses.drive(carryIn, cy ? 1 : 0);
        sim.settle();
        return Buses.toInt(alu.getOutputBus());
    }

    @Test
    void opcodesFollowThe8080Encoding() {
        //bits 5:3 of the 8080 "10 ooo sss" ALU instructions: ADD ADC SUB SBB ANA XRA ORA CMP
        assertArrayEquals(new Op[]{Op.ADD, Op.ADC, Op.SUB, Op.SBB, Op.AND, Op.XOR, Op.OR, Op.CMP}, Op.values());
        for (Op o : Op.values()) {
            assertEquals(o.ordinal(), o.code());
        }
    }

    @Test
    void busWidths() {
        assertAll(
                () -> assertEquals(20, alu.getInputBus().getSignals().size()),
                () -> assertEquals(16, alu.getOutputBus().getSignals().size()),
                () -> assertThrows(UnexpectedBusWidthException.class, () -> new Alu(sim, Buses.simpleBus(sim, 19, 0))),
                () -> assertThrows(UnexpectedBusWidthException.class, () -> new Alu(sim, Buses.simpleBus(sim, 21, 0)))
        );
    }

    /**
     * Hand-worked examples (the ADD, SUB, SBB and CMP cases follow Intel's 8080 programming manual), checked
     * against both the reference model and the gates.
     */
    @ParameterizedTest(name = "{0} {1} {2} cy={3} -> {4} {5}")
    @CsvSource({
            //op, A, operand, CY in, result, flags (S Z AC P CY)
            "ADD, 6C, 2E, false, 9A, 10110",
            "SUB, 3E, 3E, false, 00, 01110",
            "SBB, 04, 02, true,  01, 00100",
            "AND, FC, 0F, false, 0C, 00110",
            "CMP, 0A, 05, false, 05, 00110",
            "CMP, 02, 05, false, FD, 10001",
            "CMP, E5, 05, false, E0, 10100",
            "XOR, 5C, 78, false, 24, 00010",
            "OR,  33, 0F, false, 3F, 00010",
            "ADC, 3D, 42, false, 7F, 00000",
            "ADC, 3D, 42, true,  80, 10100",
    })
    void manualExamples(Op operation, String x, String y, boolean cy, String result, String szapc) {
        int flags = ALWAYS_ONE;
        int[] bits = {S, Z, AC, P, CY};
        for (int i = 0; i < 5; i++) {
            if (szapc.charAt(i) == '1') flags |= bits[i];
        }
        int expected = flags << 8 | Integer.parseInt(result, 16);
        int xv = Integer.parseInt(x, 16);
        int yv = Integer.parseInt(y, 16);
        assertAll(
                () -> assertEquals(hex(expected), hex(compute(operation, xv, yv, cy)), "reference"),
                () -> assertEquals(hex(expected), hex(run(operation, xv, yv, cy)), "gates")
        );
    }

    /**
     * All 256 x 256 operand pairs x both carry-in values, against the reference model.
     */
    @ParameterizedTest
    @EnumSource(Op.class)
    void exhaustiveAgainstReference(Op operation) {
        Buses.drive(op, operation.code());
        int failures = 0;
        StringBuilder first = new StringBuilder();
        for (int c = 0; c <= 1; c++) {
            Buses.drive(carryIn, c);
            for (int x = 0; x < 256; x++) {
                Buses.drive(a, x);
                for (int y = 0; y < 256; y++) {
                    Buses.drive(b, y);
                    sim.settle();
                    int actual = Buses.toInt(alu.getOutputBus());
                    int expected = compute(operation, x, y, c == 1);
                    if (actual != expected && failures++ < 5) {
                        first.append(String.format("%s %02X,%02X cy=%d: expected %s got %s; ",
                                operation, x, y, c, hex(expected), hex(actual)));
                    }
                }
            }
        }
        assertEquals(0, failures, first.toString());
    }

    private static String hex(int flagsAndResult) {
        int f = flagsAndResult >> 8;
        return String.format("%02X [S%d Z%d AC%d P%d CY%d F=%02X]", flagsAndResult & 0xFF,
                (f >> 7) & 1, (f >> 6) & 1, (f >> 4) & 1, (f >> 2) & 1, f & 1, f);
    }
}
