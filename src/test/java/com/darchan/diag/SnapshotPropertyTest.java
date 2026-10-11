package com.darchan.diag;

import com.darchan.testutil.Snapshots;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Properties of snapshots: JSON round trips, parser robustness, self-diff, and diff sensitivity. */
class SnapshotPropertyTest {

    @Provide
    Arbitrary<Snapshot> snapshots() {
        return Snapshots.snapshots();
    }

    // ---- JSON -----------------------------------------------------------------------------------------------------

    @Property
    void writingThenParsingGivesTheSameSnapshot(@ForAll("snapshots") Snapshot s) {
        String json = SnapshotJson.toJson(s);
        Snapshot back = SnapshotJson.fromJson(json);
        assertEquals(s, back);
        assertEquals(s.fieldNames(), back.fieldNames(), "field order is kept");
        assertEquals(json, SnapshotJson.toJson(back), "writing is deterministic: write(parse(write(s))) == write(s)");
        assertTrue(json.endsWith("}\n"));
    }

    @Property(tries = 50)
    void savingThenLoadingAFileGivesTheSameSnapshot(@ForAll("snapshots") Snapshot s) throws IOException {
        // lone surrogates cannot survive UTF-8; every other string must
        String all = SnapshotJson.toJson(s);
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(all)) {
            return;
        }
        Path file = Files.createTempFile("snapshot", ".json");
        try {
            SnapshotJson.save(s, file);
            assertEquals(s, SnapshotJson.load(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Property
    void everyTruncationOfAValidFileIsRejectedCleanly(@ForAll("snapshots") Snapshot s,
                                                       @ForAll @IntRange(min = 0, max = 10_000) int cut) {
        String json = SnapshotJson.toJson(s);
        int at = cut % json.trim().length(); // strictly shorter than the closing brace
        assertThrows(IllegalArgumentException.class, () -> SnapshotJson.fromJson(json.substring(0, at)),
                "a truncated file must be an IllegalArgumentException, not a crash or a silent success");
    }

    @Property
    void aDamagedFileEitherParsesOrFailsWithIllegalArgumentException(@ForAll("snapshots") Snapshot s,
                                                                      @ForAll @IntRange(min = 0, max = 100_000) int at,
                                                                      @ForAll @IntRange(min = 0, max = 2) int kind,
                                                                      @ForAll char c) {
        String json = SnapshotJson.toJson(s);
        int i = at % json.length();
        String damaged = switch (kind) {
            case 0 -> json.substring(0, i) + json.substring(i + 1);
            case 1 -> json.substring(0, i) + c + json.substring(i);
            default -> json.substring(0, i) + c + json.substring(i + 1);
        };
        try {
            SnapshotJson.fromJson(damaged);
        } catch (IllegalArgumentException expected) {
            // fine: rejected with a message
        } catch (RuntimeException e) {
            fail("fromJson threw " + e.getClass().getName() + " instead of IllegalArgumentException for: " + damaged, e);
        }
    }

    @Property
    void arbitraryTextNeverCrashesTheParser(@ForAll String text) {
        try {
            SnapshotJson.fromJson(text);
        } catch (IllegalArgumentException expected) {
            // fine
        }
    }

    @Property
    void jsonStringsRoundTripForAnyText(@ForAll("anyText") String text) {
        Object parsed = Json.parse(Json.quote(text));
        assertEquals(text, parsed);
    }

    @Provide
    Arbitrary<String> anyText() {
        return Snapshots.anyStrings();
    }

    @Property
    void jsonIntegersRoundTrip(@ForAll long n) {
        assertEquals(n, Json.parse(Long.toString(n)));
    }

    // ---- diff -----------------------------------------------------------------------------------------------------

    @Property
    void aSnapshotDiffersFromItselfInNothing(@ForAll("snapshots") Snapshot s) {
        SnapshotDiff d = s.diff(s);
        assertTrue(d.isEmpty(), d::report);
        assertEquals(0, d.fieldDifferences().size());
        assertEquals(0, d.byteDifferenceCount());
        assertTrue(d.regionProblems().isEmpty());
        assertTrue(d.report().startsWith("snapshots are identical"), d::report);
        // a copy that went through JSON is just as equal; labels do not count as differences
        assertTrue(s.diff(SnapshotJson.fromJson(SnapshotJson.toJson(s)).withLabel("other")).isEmpty());
    }

    @Property
    void changingOneFieldIsReportedAsExactlyThatField(@ForAll("snapshots") Snapshot s, @ForAll int pick) {
        if (s.fields().isEmpty()) {
            return;
        }
        Field old = s.fields().get(Math.floorMod(pick, s.fields().size()));
        long changed = old.width() == 64 ? old.value() ^ 1 : (old.value() ^ 1) & ((1L << old.width()) - 1);
        Snapshot.Builder b = Snapshot.builder(s.machine()).label(s.label());
        for (Field f : s.fields()) {
            b.field(f.name().equals(old.name()) ? new Field(f.name(), f.width(), changed, f.format()) : f);
        }
        s.regions().forEach(b::region);
        Snapshot t = b.build();
        SnapshotDiff d = s.diff(t);
        assertEquals(1, d.fieldDifferences().size(), d::report);
        assertEquals(old.name(), d.fieldDifferences().get(0).name());
        assertEquals(old.value(), d.fieldDifferences().get(0).expected().orElseThrow().value());
        assertEquals(changed, d.fieldDifferences().get(0).actual().orElseThrow().value());
        assertFalse(d.isEmpty());
        assertTrue(s.diff(t, Set.of(old.name())).isEmpty(), "ignoring the changed field leaves nothing");
        // and in the other direction the roles swap
        assertEquals(old.value(), t.diff(s).fieldDifferences().get(0).actual().orElseThrow().value());
    }

    @Property
    void changingBytesIsReportedAndCountedBeyondTheListLimit(@ForAll("snapshots") Snapshot s) {
        for (MemoryRegion r : s.regions()) {
            if (r.length() == 0) {
                continue;
            }
            byte[] flipped = r.bytes();
            for (int i = 0; i < flipped.length; i++) {
                flipped[i] ^= (byte) 0xFF;
            }
            Snapshot t = s.withRegion(new MemoryRegion(r.name(), r.start(), flipped));
            SnapshotDiff d = s.diff(t);
            assertEquals(r.length(), d.byteDifferenceCount(), "every byte differs");
            assertEquals(Math.min(r.length(), SnapshotDiff.MAX_LISTED_BYTES), d.byteDifferences().size(),
                    "the list is capped, the count is not");
            assertEquals(r.start(), d.byteDifferences().get(0).address(), "listed in address order");
            if (r.length() > SnapshotDiff.MAX_LISTED_BYTES) {
                assertTrue(d.report().contains("... and " + (r.length() - SnapshotDiff.MAX_LISTED_BYTES) + " more"),
                        d::report);
            }
        }
    }

    @Property
    void toStringAndDumpsNeverThrow(@ForAll("snapshots") Snapshot s) {
        assertTrue(s.toString().startsWith(s.machine()));
        RegisterDump.format(s);
        for (MemoryRegion r : s.regions()) {
            HexDump.format(r);
        }
        s.fields().forEach(f -> assertFalse(f.formattedValue().isEmpty()));
    }

    // ---- hex dump --------------------------------------------------------------------------------------------------

    @Property(tries = 300)
    void hexDumpFormatsAnyRangeInFixedWidthLines(@ForAll @IntRange(min = 0, max = Integer.MAX_VALUE - 5000) int start,
                                                  @ForAll @IntRange(min = 0, max = 4000) int length,
                                                  @ForAll int seed) {
        MemoryView memory = a -> (a * 31 + seed) & 0xFF;
        String dump = HexDump.format(memory, start, length);
        if (length == 0) {
            assertEquals("", dump);
            return;
        }
        String[] lines = dump.split("\n", -1);
        assertEquals((length + 15) / 16, lines.length, "one line per 16 bytes, no trailing newline");
        int digits = (long) start + length <= 0x10000 ? 4 : 8;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int n = Math.min(16, length - 16 * i);
            // the hex part is padded on a short last line so the ASCII column starts at the same place; the ASCII
            // column itself only has the bytes that exist
            int asciiStart = digits + 1 + 16 * 3 + 1 + 3;
            assertEquals(asciiStart + n + 1, line.length(), "line width: " + line);
            assertEquals(String.format("%0" + digits + "X:", start + 16 * i), line.substring(0, digits + 1));
            assertEquals("  |", line.substring(asciiStart - 3, asciiStart));
            assertTrue(line.endsWith("|"));
            for (int k = 0; k < n; k++) {
                int b = memory.peek(start + 16 * i + k);
                assertEquals(b >= 0x20 && b < 0x7F ? (char) b : '.', line.charAt(asciiStart + k), "ASCII column");
                String hex = line.substring(digits + 1 + 3 * k + (k >= 8 ? 1 : 0), digits + 1 + 3 * k + (k >= 8 ? 1 : 0) + 3);
                assertEquals(String.format(" %02X", b), hex, "hex column, byte " + k);
            }
        }
    }

    @Property
    void hexDumpRejectsNegativeArguments(@ForAll @IntRange(min = -1000, max = -1) int negative) {
        MemoryView memory = a -> 0;
        assertThrows(IllegalArgumentException.class, () -> HexDump.format(memory, negative, 4));
        assertThrows(IllegalArgumentException.class, () -> HexDump.format(memory, 4, negative));
    }
}
