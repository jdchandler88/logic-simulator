package com.darchan.diag;

/**
 * Classic hex dump, 16 bytes per line with an ASCII column:
 * <pre>
 * 0100: 31 00 07 C3 0D 01 48 65  6C 6C 6F 21 24 00 00 00  |1.....Hello!$...|
 * </pre>
 * Lines start at the requested start address (no alignment); the address has 4 hex digits when the range ends at
 * or below 0x10000 and 8 otherwise. Bytes 0x20..0x7E are shown as ASCII, anything else as {@code '.'}. A short
 * last line is padded with spaces so the ASCII column stays aligned. Lines are separated by {@code '\n'} with no
 * trailing newline.
 */
public final class HexDump {

    public static final int BYTES_PER_LINE = 16;

    private HexDump() {
    }

    public static String format(MemoryView memory, int start, int length) {
        if (length < 0 || start < 0) {
            throw new IllegalArgumentException("start and length must be >= 0");
        }
        int digits = (long) start + length <= 0x10000 ? 4 : 8;
        StringBuilder sb = new StringBuilder(length / BYTES_PER_LINE * 80 + 80);
        for (int line = 0; line < length; line += BYTES_PER_LINE) {
            if (line > 0) {
                sb.append('\n');
            }
            int lineStart = start + line;
            int n = Math.min(BYTES_PER_LINE, length - line);
            sb.append(Field.hex(lineStart, digits)).append(':');
            StringBuilder ascii = new StringBuilder(BYTES_PER_LINE);
            for (int i = 0; i < BYTES_PER_LINE; i++) {
                if (i == 8) {
                    sb.append(' ');
                }
                if (i < n) {
                    int b = memory.peek(lineStart + i) & 0xFF;
                    sb.append(' ').append(Field.hex(b, 2));
                    ascii.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
                } else {
                    sb.append("   ");
                }
            }
            sb.append("  |").append(ascii).append('|');
        }
        return sb.toString();
    }

    public static String format(MemoryRegion region) {
        return format(region.view(), region.start(), region.length());
    }
}
