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
}
