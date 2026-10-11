package com.darchan.diag.trace;

import java.util.Objects;

/**
 * One register column in a trace line.
 *
 * @param name     e.g. {@code "A"}, {@code "SP"}
 * @param width    bits; the trace shows {@code ceil(width / 4)} hex digits
 * @param flagBits {@code null} for an ordinary register; for a flag register, one character per bit from the most
 *                 significant bit down, naming the flag or {@code '.'} for an unnamed bit. The 8080 F register is
 *                 {@code "SZ.A.P.C"}. The trace then adds a token such as {@code SZAPC=-Z-P-} (letter = set,
 *                 {@code '-'} = clear).
 */
public record RegisterSpec(String name, int width, String flagBits) {

    public RegisterSpec {
        Objects.requireNonNull(name, "name");
        if (width < 1 || width > 32) {
            throw new IllegalArgumentException("width must be 1..32: " + width);
        }
        if (flagBits != null && flagBits.length() != width) {
            throw new IllegalArgumentException("flagBits must name all " + width + " bits: " + flagBits);
        }
    }

    public static RegisterSpec of(String name, int width) {
        return new RegisterSpec(name, width, null);
    }

    public static RegisterSpec flags(String name, String flagBits) {
        return new RegisterSpec(name, flagBits.length(), flagBits);
    }

    /** the named flags, most significant first, e.g. {@code "SZAPC"} */
    public String flagNames() {
        return flagBits == null ? "" : flagBits.replace(".", "");
    }
}
