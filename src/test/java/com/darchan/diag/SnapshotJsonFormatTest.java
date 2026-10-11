package com.darchan.diag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Edge shapes of the snapshot file: empty parts, hex line wrapping, and what the loader insists on. */
class SnapshotJsonFormatTest {

    private static final String HEADER = "{\n  \"format\": \"darchan-snapshot\",\n  \"version\": 1,\n";

    @Test
    void anEmptySnapshotWritesEmptyArrays() {
        assertEquals(HEADER + "  \"machine\": \"m\",\n  \"label\": \"\",\n  \"fields\": [],\n  \"memory\": []\n}\n",
                SnapshotJson.toJson(Snapshot.builder("m").build()));
    }

    @Test
    void anEmptyRegionHasAnEmptyHexArray() {
        String json = SnapshotJson.toJson(Snapshot.builder("m").region(new MemoryRegion("e", 4, new byte[0])).build());
        assertEquals(HEADER + "  \"machine\": \"m\",\n  \"label\": \"\",\n  \"fields\": [],\n  \"memory\": [\n"
                + "    {\"name\": \"e\", \"start\": 4, \"length\": 0, \"hex\": []}\n  ]\n}\n", json);
    }

    @Test
    void hexIsWrappedAt32BytesPerString() {
        for (int length : new int[]{1, 31, 32, 33, 64, 65}) {
            byte[] data = new byte[length];
            for (int i = 0; i < length; i++) {
                data[i] = (byte) (i + 1);
            }
            String json = SnapshotJson.toJson(Snapshot.builder("m").region(new MemoryRegion("r", 0, data)).build());
            long strings = json.lines().filter(l -> l.matches("\\s+\"[0-9A-F]+\",?")).count();
            assertEquals((length + 31) / 32, strings, "hex strings for " + length + " bytes");
            for (String line : json.lines().filter(l -> l.matches("\\s+\"[0-9A-F]+\",?")).toList()) {
                String hex = line.trim().replace("\"", "").replace(",", "");
                assertTrue(hex.length() <= 64 && hex.length() % 2 == 0, line);
            }
            assertEquals(Snapshot.builder("m").region(new MemoryRegion("r", 0, data)).build(),
                    SnapshotJson.fromJson(json));
        }
        assertEquals(32, SnapshotJson.BYTES_PER_HEX_LINE);
        // exactly two strings for 33 bytes: 32 then 1
        byte[] data = new byte[33];
        String json = SnapshotJson.toJson(Snapshot.builder("m").region(new MemoryRegion("r", 0, data)).build());
        assertTrue(json.contains("\"" + "00".repeat(32) + "\",\n      \"00\"\n"), json);
    }

    @Test
    void severalFieldsAndRegionsAreCommaSeparated() {
        String json = SnapshotJson.toJson(Snapshot.builder("m").hex("A", 8, 1).hex("B", 8, 2)
                .region(new MemoryRegion("r1", 0, new byte[]{1})).region(new MemoryRegion("r2", 1, new byte[]{2}))
                .build());
        assertTrue(json.contains("\"value\": \"01\"},\n    {\"name\": \"B\""), json);
        assertTrue(json.contains("\n    ]},\n    {\"name\": \"r2\""), json);
        assertEquals(2, SnapshotJson.fromJson(json).fields().size());
        assertEquals(2, SnapshotJson.fromJson(json).regions().size());
    }

    // ---- loader ------------------------------------------------------------------------------------------------------

    private static String doc(String format, String version, String rest) {
        return "{\"format\":" + format + ",\"version\":" + version + "," + rest + "}";
    }

    private static final String BODY = "\"machine\":\"m\",\"label\":\"x\",\"fields\":[]";

    private static IllegalArgumentException rejected(String json) {
        return assertThrows(IllegalArgumentException.class, () -> SnapshotJson.fromJson(json), json);
    }

    @Test
    void theFormatAndVersionMustMatch() {
        assertTrue(rejected(doc("\"other\"", "1", BODY)).getMessage().contains("not a snapshot: format is other"));
        assertTrue(rejected("{\"version\":1," + BODY + "}").getMessage().contains("format is null"));
        assertTrue(rejected(doc("\"darchan-snapshot\"", "2", BODY)).getMessage().contains("unsupported snapshot version 2"));
        assertTrue(rejected(doc("\"darchan-snapshot\"", "0", BODY)).getMessage().contains("unsupported snapshot version 0"));
        assertTrue(rejected(doc("\"darchan-snapshot\"", "\"1\"", BODY)).getMessage().contains("version must be an integer"));
        assertEquals("x", SnapshotJson.fromJson(doc("\"darchan-snapshot\"", "1", BODY)).label());
    }

    @Test
    void theMemorySectionIsOptional() {
        String base = doc("\"darchan-snapshot\"", "1", BODY);
        assertTrue(SnapshotJson.fromJson(base).regions().isEmpty());
        assertTrue(SnapshotJson.fromJson(doc("\"darchan-snapshot\"", "1", BODY + ",\"memory\":null")).regions().isEmpty());
        assertTrue(SnapshotJson.fromJson(doc("\"darchan-snapshot\"", "1", BODY + ",\"memory\":[]")).regions().isEmpty());
        assertTrue(rejected(doc("\"darchan-snapshot\"", "1", BODY + ",\"memory\":5")).getMessage()
                .contains("memory must be a JSON array"));
    }

    @Test
    void theRequiredPartsMustHaveTheRightTypes() {
        String ok = "\"darchan-snapshot\"";
        assertTrue(rejected(doc(ok, "1", "\"label\":\"\",\"fields\":[]")).getMessage().contains("machine must be a string"));
        assertTrue(rejected(doc(ok, "1", "\"machine\":\"m\",\"fields\":[]")).getMessage().contains("label must be a string"));
        assertTrue(rejected(doc(ok, "1", "\"machine\":\"m\",\"label\":\"\"")).getMessage().contains("fields must be a JSON array"));
        assertTrue(rejected(doc(ok, "1", "\"machine\":\"m\",\"label\":\"\",\"fields\":[1]")).getMessage()
                .contains("field must be a JSON object"));
        assertTrue(rejected("[]").getMessage().contains("root must be a JSON object"));
        assertTrue(rejected(doc(ok, "1", "\"machine\":\" \",\"label\":\"\",\"fields\":[]")).getMessage()
                .contains("machine must not be blank"));
        // fields: names, formats, value types
        String f = "\"machine\":\"m\",\"label\":\"\",\"fields\":[{\"name\":\"A\",\"width\":8,\"format\":\"%s\",\"value\":%s}]";
        assertEquals(0x2A, SnapshotJson.fromJson(doc(ok, "1", String.format(f, "HEX", "\"2a\""))).value("A"));
        assertEquals(42, SnapshotJson.fromJson(doc(ok, "1", String.format(f, "DEC", "42"))).value("A"));
        assertEquals(0, SnapshotJson.fromJson(doc(ok, "1", String.format(f.replace("\"width\":8", "\"width\":1"), "BOOL", "false"))).value("A"));
        assertEquals(1, SnapshotJson.fromJson(doc(ok, "1", String.format(f.replace("\"width\":8", "\"width\":1"), "BOOL", "true"))).value("A"));
        assertTrue(rejected(doc(ok, "1", String.format(f, "BOOL", "1"))).getMessage().contains("A.value must be a boolean"));
        assertTrue(rejected(doc(ok, "1", String.format(f, "DEC", "\"4\""))).getMessage().contains("A.value must be an integer"));
        assertTrue(rejected(doc(ok, "1", String.format(f, "HEX", "4"))).getMessage().contains("A.value must be a string"));
        assertThrows(IllegalArgumentException.class, () -> SnapshotJson.fromJson(doc(ok, "1", String.format(f, "OCT", "1"))));
        // the first of two fields with one name
        assertTrue(rejected(doc(ok, "1", "\"machine\":\"m\",\"label\":\"\",\"fields\":["
                + "{\"name\":\"A\",\"width\":8,\"format\":\"HEX\",\"value\":\"1\"},"
                + "{\"name\":\"A\",\"width\":8,\"format\":\"HEX\",\"value\":\"2\"}]")).getMessage().contains("duplicate field A"));
    }

    @Test
    void sixtyFourBitHexAndDecimalValuesAreExact() {
        Snapshot s = Snapshot.builder("m").hex("W", 64, 0x8000_0000_0000_0001L).hex("N", 64, -1L)
                .dec("D", Long.MAX_VALUE).dec("U", -2L).hex("B", 1, 1).build();
        Snapshot back = SnapshotJson.fromJson(SnapshotJson.toJson(s));
        assertEquals(s, back);
        assertEquals(0x8000_0000_0000_0001L, back.value("W"));
        assertEquals("8000000000000001", back.field("W").orElseThrow().formattedValue());
        assertEquals("FFFFFFFFFFFFFFFF", back.field("N").orElseThrow().formattedValue());
    }
}
