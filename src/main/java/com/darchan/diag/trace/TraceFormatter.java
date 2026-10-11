package com.darchan.diag.trace;

import java.util.List;

/**
 * The fixed one-line-per-instruction trace format, designed so two traces can be compared with plain
 * {@code diff}. Each line describes the state <em>before</em> the instruction executes:
 *
 * <pre>
 * 000000000000 000000000000 0100: 31 00 07  LXI SP,0700H   A=00 F=02 B=00 C=00 D=00 E=00 H=00 L=00 SP=0000 SZAPC=-----
 * </pre>
 *
 * Columns, separated by single spaces unless stated:
 * <ol>
 *   <li>cycles executed before this instruction, decimal, zero-padded to 12 digits;</li>
 *   <li>instructions executed before this one, decimal, zero-padded to 12 digits;</li>
 *   <li>PC in upper-case hex ({@code ceil(pcWidth/4)} digits) followed by {@code ':'};</li>
 *   <li>the instruction bytes, two hex digits each, separated by one space and right-padded with spaces to
 *       {@code 3 * maxInstructionLength - 1} characters; then two spaces;</li>
 *   <li>the disassembly, prefixed with {@code "INT "} when the instruction was injected by an interrupt
 *       acknowledge, right-padded to {@code disassemblyWidth}; then one space;</li>
 *   <li>each register as {@code NAME=HEX} ({@code ceil(width/4)} digits), in layout order;</li>
 *   <li>for each flag register, {@code <flag names>=<flags>} where each flag shows its letter when set and
 *       {@code '-'} when clear (8080: {@code SZAPC=-Z-P-}).</li>
 * </ol>
 * Lines never have trailing spaces. Cycle/instruction numbers wider than 12 digits simply widen the column.
 */
public final class TraceFormatter {

    public static final int COUNTER_DIGITS = 12;
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private TraceFormatter() {
    }

    public static String format(TraceEvent e) {
        StringBuilder sb = new StringBuilder(128);
        append(sb, e);
        return sb.toString();
    }

    public static void append(StringBuilder sb, TraceEvent e) {
        append(sb, e.layout(), e.cycle(), e.instruction(), e.pc(), e.isInterrupt(), e.packedBytes(), e.length(),
                e.disassembly(), e.rawRegisters(), 0);
    }

    /** Formats a line from raw values (used by {@link TraceRingBuffer}, which stores primitives). */
    static void append(StringBuilder sb, TraceLayout layout, long cycle, long instruction, int pc, boolean interrupt,
                       int packedBytes, int length, String disassembly, int[] registers, int regOffset) {
        int lineStart = sb.length();
        decimal(sb, cycle);
        sb.append(' ');
        decimal(sb, instruction);
        sb.append(' ');
        hex(sb, pc, (layout.pcWidth() + 3) / 4);
        sb.append(": ");
        int bytesColumn = 3 * layout.maxInstructionLength() - 1;
        int startLen = sb.length();
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            hex(sb, packedBytes >>> (8 * i) & 0xFF, 2);
        }
        pad(sb, startLen + bytesColumn);
        sb.append("  ");
        startLen = sb.length();
        if (interrupt) {
            sb.append("INT ");
        }
        sb.append(disassembly);
        pad(sb, startLen + layout.disassemblyWidth());
        List<RegisterSpec> specs = layout.registers();
        for (int i = 0; i < specs.size(); i++) {
            RegisterSpec r = specs.get(i);
            sb.append(' ').append(r.name()).append('=');
            hex(sb, registers[regOffset + i], (r.width() + 3) / 4);
        }
        for (int i = 0; i < specs.size(); i++) {
            RegisterSpec r = specs.get(i);
            if (r.flagBits() == null) {
                continue;
            }
            sb.append(' ').append(r.flagNames()).append('=');
            int value = registers[regOffset + i];
            String bits = r.flagBits();
            for (int b = 0; b < bits.length(); b++) {
                char name = bits.charAt(b);
                if (name != '.') {
                    boolean set = (value >>> (bits.length() - 1 - b) & 1) != 0;
                    sb.append(set ? name : '-');
                }
            }
        }
        while (sb.length() > lineStart && sb.charAt(sb.length() - 1) == ' ') {
            sb.setLength(sb.length() - 1);
        }
    }

    /** A {@code #}-prefixed header naming the columns, handy as the first line of a trace file. */
    public static String header(TraceLayout layout) {
        StringBuilder sb = new StringBuilder("# ").append(layout.cpu())
                .append(" trace: cycle instruction pc: bytes  disassembly");
        for (RegisterSpec r : layout.registers()) {
            sb.append(' ').append(r.name());
        }
        return sb.toString();
    }

    private static void decimal(StringBuilder sb, long value) {
        String s = Long.toString(value);
        for (int i = s.length(); i < COUNTER_DIGITS; i++) {
            sb.append('0');
        }
        sb.append(s);
    }

    private static void hex(StringBuilder sb, int value, int digits) {
        for (int d = digits - 1; d >= 0; d--) {
            sb.append(HEX[(value >>> (4 * d)) & 0xF]);
        }
    }

    private static void pad(StringBuilder sb, int toLength) {
        while (sb.length() < toLength) {
            sb.append(' ');
        }
    }
}
