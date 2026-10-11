package com.darchan.diag;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Saves and loads {@link Snapshot}s as JSON. The layout is meant to be readable and to diff well line by line:
 * <pre>
 * {
 *   "format": "darchan-snapshot",
 *   "version": 1,
 *   "machine": "i8080",
 *   "label": "after boot",
 *   "fields": [
 *     {"name": "PC", "width": 16, "format": "HEX", "value": "0100"},
 *     {"name": "INTE", "width": 1, "format": "BOOL", "value": false},
 *     {"name": "CYCLES", "width": 64, "format": "DEC", "value": 4924}
 *   ],
 *   "memory": [
 *     {"name": "ram", "start": 0, "length": 65536, "hex": [
 *       "76000000000000000000000000000000...",   (32 bytes per string)
 *       ...
 *     ]}
 *   ]
 * }
 * </pre>
 * HEX values are strings (exact for all 64 bits), DEC values are JSON integers and BOOL values are booleans.
 */
public final class SnapshotJson {

    public static final String FORMAT = "darchan-snapshot";
    public static final int VERSION = 1;
    /** bytes per hex string in a memory region */
    public static final int BYTES_PER_HEX_LINE = 32;

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private SnapshotJson() {
    }

    public static String toJson(Snapshot s) {
        StringBuilder sb = new StringBuilder(256 + s.regions().stream().mapToInt(MemoryRegion::length).sum() * 2);
        sb.append("{\n");
        sb.append("  \"format\": ").append(Json.quote(FORMAT)).append(",\n");
        sb.append("  \"version\": ").append(VERSION).append(",\n");
        sb.append("  \"machine\": ").append(Json.quote(s.machine())).append(",\n");
        sb.append("  \"label\": ").append(Json.quote(s.label())).append(",\n");
        sb.append("  \"fields\": [");
        List<Field> fields = s.fields();
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            sb.append(i == 0 ? "\n" : ",\n");
            sb.append("    {\"name\": ").append(Json.quote(f.name()))
                    .append(", \"width\": ").append(f.width())
                    .append(", \"format\": \"").append(f.format()).append('"')
                    .append(", \"value\": ").append(switch (f.format()) {
                        case HEX -> Json.quote(f.formattedValue());
                        case DEC -> Long.toString(f.value());
                        case BOOL -> Boolean.toString(f.asBoolean());
                    }).append('}');
        }
        sb.append(fields.isEmpty() ? "],\n" : "\n  ],\n");
        sb.append("  \"memory\": [");
        List<MemoryRegion> regions = s.regions();
        for (int i = 0; i < regions.size(); i++) {
            MemoryRegion r = regions.get(i);
            byte[] bytes = r.bytes();
            sb.append(i == 0 ? "\n" : ",\n");
            sb.append("    {\"name\": ").append(Json.quote(r.name()))
                    .append(", \"start\": ").append(r.start())
                    .append(", \"length\": ").append(r.length())
                    .append(", \"hex\": [");
            for (int off = 0; off < bytes.length; off += BYTES_PER_HEX_LINE) {
                sb.append(off == 0 ? "\n" : ",\n");
                sb.append("      \"").append(HEX.formatHex(bytes, off, Math.min(bytes.length, off + BYTES_PER_HEX_LINE))).append('"');
            }
            sb.append(bytes.length == 0 ? "]}" : "\n    ]}");
        }
        sb.append(regions.isEmpty() ? "]\n" : "\n  ]\n");
        return sb.append("}\n").toString();
    }

    public static Snapshot fromJson(String json) {
        Map<String, Object> root = asObject(Json.parse(json), "root");
        if (!FORMAT.equals(root.get("format"))) {
            throw new IllegalArgumentException("not a snapshot: format is " + root.get("format"));
        }
        long version = asLong(root.get("version"), "version");
        if (version != VERSION) {
            throw new IllegalArgumentException("unsupported snapshot version " + version);
        }
        Snapshot.Builder b = Snapshot.builder(asString(root.get("machine"), "machine"))
                .label(asString(root.get("label"), "label"));
        for (Object o : asList(root.get("fields"), "fields")) {
            Map<String, Object> f = asObject(o, "field");
            String name = asString(f.get("name"), "field name");
            int width = (int) asLong(f.get("width"), name + ".width");
            Field.Format format = Field.Format.valueOf(asString(f.get("format"), name + ".format"));
            Object v = f.get("value");
            long value = switch (format) {
                case HEX -> Long.parseUnsignedLong(asString(v, name + ".value"), 16);
                case DEC -> asLong(v, name + ".value");
                case BOOL -> {
                    if (!(v instanceof Boolean bool)) {
                        throw new IllegalArgumentException(name + ".value must be a boolean");
                    }
                    yield bool ? 1 : 0;
                }
            };
            b.field(new Field(name, width, value, format));
        }
        Object memory = root.get("memory");
        if (memory != null) {
            for (Object o : asList(memory, "memory")) {
                Map<String, Object> r = asObject(o, "region");
                String name = asString(r.get("name"), "region name");
                int start = (int) asLong(r.get("start"), name + ".start");
                int length = (int) asLong(r.get("length"), name + ".length");
                StringBuilder hex = new StringBuilder(length * 2);
                for (Object line : asList(r.get("hex"), name + ".hex")) {
                    hex.append(asString(line, name + ".hex"));
                }
                byte[] bytes = HEX.parseHex(hex);
                if (bytes.length != length) {
                    throw new IllegalArgumentException("region " + name + " has " + bytes.length + " bytes, expected " + length);
                }
                b.region(new MemoryRegion(name, start, bytes));
            }
        }
        return b.build();
    }

    public static void save(Snapshot snapshot, Path file) {
        try {
            Files.writeString(file, toJson(snapshot), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write snapshot " + file, e);
        }
    }

    public static Snapshot load(Path file) {
        try {
            return fromJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read snapshot " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object o, String what) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException(what + " must be a JSON object");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o, String what) {
        if (o instanceof List<?> l) {
            return (List<Object>) l;
        }
        throw new IllegalArgumentException(what + " must be a JSON array");
    }

    private static String asString(Object o, String what) {
        if (o instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException(what + " must be a string");
    }

    private static long asLong(Object o, String what) {
        if (o instanceof Long l) {
            return l;
        }
        throw new IllegalArgumentException(what + " must be an integer");
    }
}
