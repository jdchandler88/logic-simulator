package com.darchan.diag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotJsonTest {

    @Test
    void exactLayout() {
        Snapshot s = Snapshot.builder("i8080").label("a \"quoted\"\nlabel")
                .hex("PC", 16, 0x100).bool("INTE", false).dec("CYCLES", 4924)
                .region(new MemoryRegion("ram", 0x10, new byte[]{0x76, 0, (byte) 0xAB}))
                .build();
        assertEquals("""
                {
                  "format": "darchan-snapshot",
                  "version": 1,
                  "machine": "i8080",
                  "label": "a \\"quoted\\"\\nlabel",
                  "fields": [
                    {"name": "PC", "width": 16, "format": "HEX", "value": "0100"},
                    {"name": "INTE", "width": 1, "format": "BOOL", "value": false},
                    {"name": "CYCLES", "width": 64, "format": "DEC", "value": 4924}
                  ],
                  "memory": [
                    {"name": "ram", "start": 16, "length": 3, "hex": [
                      "7600AB"
                    ]}
                  ]
                }
                """, SnapshotJson.toJson(s));
    }

    @Test
    void roundTripWithFullMemory(@TempDir Path dir) {
        byte[] ram = new byte[0x10000];
        new Random(42).nextBytes(ram);
        Snapshot s = Snapshot.builder("i8080").label("full")
                .hex("PC", 16, 0xFFFF).hex("W", 64, -1L).hex("A", 8, 0x80).bool("HALT", true)
                .dec("CYCLES", 23_803_381_171L)
                .region(new MemoryRegion("ram", 0, ram))
                .region(new MemoryRegion("empty", 5, new byte[0]))
                .build();
        Path file = dir.resolve("snap.json");
        SnapshotJson.save(s, file);
        Snapshot back = SnapshotJson.load(file);
        assertEquals(s, back);
        assertTrue(s.diff(back).isEmpty());
    }

    @Test
    void emptySnapshot() {
        Snapshot s = Snapshot.builder("nothing").build();
        assertEquals(s, SnapshotJson.fromJson(SnapshotJson.toJson(s)));
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> SnapshotJson.fromJson("{\"format\": \"other\"}"));
        assertThrows(IllegalArgumentException.class, () -> SnapshotJson.fromJson("{"));
        assertThrows(IllegalArgumentException.class, () -> SnapshotJson.fromJson("[1, 2.5]"));
        String ok = SnapshotJson.toJson(Snapshot.builder("m").hex("A", 8, 1).build());
        assertThrows(IllegalArgumentException.class, () -> SnapshotJson.fromJson(ok.replace("\"01\"", "\"1FF\"")));
        assertThrows(IllegalArgumentException.class, () -> SnapshotJson.fromJson(ok + "x"));
    }

    @Test
    void parserHandlesWhitespaceAndEscapes() {
        Object o = Json.parse(" { \"a\" : [ 1 , -2 , true , null , \"\\u0041\\t\" ] } ");
        assertEquals(java.util.Map.of("a", java.util.Arrays.asList(1L, -2L, true, null, "A\t")), o);
    }
    private static String withRegion(String start, String length, String hex) {
        return "{\"format\":\"darchan-snapshot\",\"version\":1,\"machine\":\"m\",\"label\":\"\",\"fields\":[],"
                + "\"memory\":[{\"name\":\"ram\",\"start\":" + start + ",\"length\":" + length + ",\"hex\":[" + hex
                + "]}]}";
    }

    private static String withField(String width, String value) {
        return "{\"format\":\"darchan-snapshot\",\"version\":1,\"machine\":\"m\",\"label\":\"\",\"fields\":["
                + "{\"name\":\"A\",\"width\":" + width + ",\"format\":\"HEX\",\"value\":" + value + "}]}";
    }

    private static void rejected(String json, String messagePart) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SnapshotJson.fromJson(json));
        assertTrue(e.getMessage().contains(messagePart), e.getMessage());
    }

    @Test
    void outOfRangeNumbersAreRejectedWithAClearMessage() {
        rejected(withRegion("0", "-1", ""), "ram.length must be in 0..");
        rejected(withRegion("-5", "0", ""), "ram.start must be in 0..");
        rejected(withRegion("4294967296", "0", ""), "ram.start must be in 0..");
        rejected(withRegion("0", "4294967297", "\"00\""), "ram.length must be in 0..");
        rejected(withRegion("2147483647", "2", "\"0000\""), "ram.length must be in 0..");
        rejected(withRegion("0", "2", "\"00\""), "expected 4 for 2 bytes");
        rejected(withRegion("0", "1", "\"0G\""), "bad hex data");
        rejected(withField("-8", "\"01\""), "A.width must be in 1..64");
        rejected(withField("4294967304", "\"01\""), "A.width must be in 1..64");
        rejected(withField("8", "\"+1\""), "A.value must be 1 to 16 hex digits");
        rejected(withField("8", "\"-1\""), "A.value must be 1 to 16 hex digits");
        assertEquals(1, SnapshotJson.fromJson(withRegion("0", "1", "\"7F\"")).region("ram").orElseThrow().length());
    }

    @Test
    void unicodeEscapesNeedExactlyFourHexDigits() {
        assertEquals("A", Json.parse("\"\\u0041\""));
        assertEquals("\u00e9", Json.parse("\"\\u00E9\""));
        for (String bad : new String[]{"\\u-041", "\\u+041", "\\u 041", "\\u004", "\\u00G1", "\\u\uFF10\uFF10\uFF14\uFF11"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Json.parse("\"" + bad + "\""), bad);
            assertTrue(e.getMessage().contains("\\u"), e.getMessage());
        }
    }
}
