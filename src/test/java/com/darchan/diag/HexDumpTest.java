package com.darchan.diag;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HexDumpTest {

    @Test
    void sixteenBytesPerLineWithAsciiColumn() {
        byte[] data = new byte[20];
        data[0] = 0x31;
        data[1] = 0x00;
        data[2] = 0x07;
        byte[] text = "Hello!$".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(text, 0, data, 6, text.length);
        data[16] = (byte) 0xFF;
        data[17] = 0x7E;
        MemoryRegion r = new MemoryRegion("ram", 0x100, data);
        assertEquals("""
                0100: 31 00 07 00 00 00 48 65  6C 6C 6F 21 24 00 00 00  |1.....Hello!$...|
                0110: FF 7E 00 00                                       |.~..|""", HexDump.format(r));
    }

    @Test
    void unalignedStartAndWideAddresses() {
        MemoryView mem = a -> a & 0xFF;
        assertEquals("FFFF: FF" + " ".repeat(46) + "  |.|", HexDump.format(mem, 0xFFFF, 1));
        assertEquals("00010000: 00" + " ".repeat(46) + "  |.|", HexDump.format(mem, 0x10000, 1));
        assertEquals("", HexDump.format(mem, 0, 0));
    }
}
