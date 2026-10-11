package com.darchan.behavioral.i8080;

import com.darchan.behavioral.bus.Ram;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class Disassembler8080Test {

    @ParameterizedTest(name = "{0} {1} {2} -> {3}")
    @CsvSource(delimiter = '|', value = {
            "00 | 00 | 00 | NOP",
            "31 | 00 | 07 | LXI SP,0700H",
            "21 | 00 | C0 | LXI H,0C000H",
            "3E | FF | 00 | MVI A,0FFH",
            "3E | 07 | 00 | MVI A,07H",
            "36 | A0 | 00 | MVI M,0A0H",
            "7E | 00 | 00 | MOV A,M",
            "70 | 00 | 00 | MOV M,B",
            "76 | 00 | 00 | HLT",
            "C3 | 0D | 01 | JMP 010DH",
            "C2 | 34 | 12 | JNZ 1234H",
            "EC | FF | FF | CPE 0FFFFH",
            "F8 | 00 | 00 | RM",
            "F5 | 00 | 00 | PUSH PSW",
            "C1 | 00 | 00 | POP B",
            "D3 | 01 | 00 | OUT 01H",
            "DB | FE | 00 | IN 0FEH",
            "FE | 3A | 00 | CPI 3AH",
            "22 | 00 | 50 | SHLD 5000H",
            "2A | 00 | 50 | LHLD 5000H",
            "32 | 00 | 50 | STA 5000H",
            "3A | 00 | 50 | LDA 5000H",
            "0A | 00 | 00 | LDAX B",
            "12 | 00 | 00 | STAX D",
            "39 | 00 | 00 | DAD SP",
            "3B | 00 | 00 | DCX SP",
            "27 | 00 | 00 | DAA",
            "E3 | 00 | 00 | XTHL",
            "EB | 00 | 00 | XCHG",
            "E9 | 00 | 00 | PCHL",
            "F9 | 00 | 00 | SPHL",
            "FB | 00 | 00 | EI",
            "F3 | 00 | 00 | DI",
            "C7 | 00 | 00 | RST 0",
            "FF | 00 | 00 | RST 7",
            "9E | 00 | 00 | SBB M",
            "A7 | 00 | 00 | ANA A",
            "08 | 00 | 00 | *NOP",
            "38 | 00 | 00 | *NOP",
            "CB | 00 | 01 | *JMP 0100H",
            "D9 | 00 | 00 | *RET",
            "DD | 05 | 00 | *CALL 0005H",
            "ED | 05 | 00 | *CALL 0005H",
            "FD | 05 | 00 | *CALL 0005H",
    })
    void mnemonics(String op, String b1, String b2, String expected) {
        assertEquals(expected, Disassembler8080.disassemble(Integer.parseInt(op, 16), Integer.parseInt(b1, 16),
                Integer.parseInt(b2, 16)));
    }

    @Test
    void lengths() {
        int[] count = new int[4];
        for (int op = 0; op < 256; op++) {
            count[Disassembler8080.length(op)]++;
        }
        // 2-byte: 8 MVI + 8 immediate ALU + IN + OUT; 3-byte: 4 LXI + SHLD LHLD STA LDA + 8 Jcc + JMP + *JMP + 8 Ccc + CALL + 3 *CALL
        assertEquals(18, count[2]);
        assertEquals(30, count[3]);
        assertEquals(256 - 18 - 30, count[1]);
    }

    @Test
    void everyOpcodeHasAMnemonicAndOnlyTheDocumentedOnesAreUnique() {
        Set<String> seen = new HashSet<>();
        int undocumented = 0;
        for (int op = 0; op < 256; op++) {
            String t = Disassembler8080.template(op);
            assertFalse(t.isBlank());
            if (t.startsWith("*")) {
                undocumented++;
                assertTrue(seen.contains(t.substring(1)) || t.equals("*NOP") || t.startsWith("*CALL") || t.startsWith("*RET") || t.startsWith("*JMP"));
            } else {
                assertTrue(seen.add(t), "duplicate " + t);
            }
        }
        assertEquals(12, undocumented);
    }

    @Test
    void decoderInterfaceAndTruncatedInput() {
        assertEquals("LXI SP,0700H", Disassembler8080.INSTANCE.disassemble(0x100, new int[]{0x31, 0x00, 0x07}, 3));
        assertEquals("LXI SP,??", Disassembler8080.INSTANCE.disassemble(0x100, new int[]{0x31, 0x00}, 2));
        assertEquals("??", Disassembler8080.INSTANCE.disassemble(0x100, new int[0], 0));
    }

    @Test
    void listing() {
        Ram ram = new Ram();
        ram.load(0x100, new byte[]{0x31, 0x00, 0x07, (byte) 0xCD, 0x05, 0x00, 0x76});
        assertEquals("""
                0100: 31 00 07  LXI SP,0700H
                0103: CD 05 00  CALL 0005H
                0106: 76        HLT""", Disassembler8080.listing(ram::peek, 0x100, 3));
        assertEquals("CALL 0005H", Disassembler8080.disassemble(ram::peek, 0x103));
    }
}
