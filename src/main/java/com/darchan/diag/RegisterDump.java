package com.darchan.diag;

import java.util.StringJoiner;

/** Renders the fields of a {@link Snapshot} as one line: {@code PC=0100 SP=F000 A=3E F=46 ... CYCLES=4924}. */
public final class RegisterDump {

    private RegisterDump() {
    }

    public static String format(Snapshot snapshot) {
        StringJoiner j = new StringJoiner(" ");
        for (Field f : snapshot.fields()) {
            j.add(f.toString());
        }
        return j.toString();
    }
}
