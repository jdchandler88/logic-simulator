package com.darchan.behavioral.i8080;

import com.darchan.diag.MemoryView;
import com.darchan.diag.trace.InstructionDecoder;

/**
 * Intel 8080 disassembler using Intel mnemonics ({@code MOV A,M}, {@code LXI SP,0C000H}, {@code JNZ 0105H}).
 *
 * <p>Operands are upper-case hex with an {@code H} suffix and a leading {@code 0} when the first digit is a letter,
 * as an Intel assembler expects: 8-bit immediates have 2 digits ({@code MVI A,0FFH}), addresses and 16-bit
 * immediates 4 ({@code JMP 0100H}). The undocumented duplicate opcodes are shown with a {@code *} before the
 * mnemonic of the instruction they behave like: {@code *NOP} (08 10 18 20 28 30 38), {@code *JMP} (CB),
 * {@code *RET} (D9) and {@code *CALL} (DD ED FD).
 */
public final class Disassembler8080 implements InstructionDecoder {

    /** Shared, stateless instance (the {@link InstructionDecoder} used in 8080 traces). */
    public static final Disassembler8080 INSTANCE = new Disassembler8080();

    /** {@code #b} = 8-bit immediate (instruction is 2 bytes), {@code #w} = 16-bit (3 bytes). */
    private static final String[] TEMPLATES = {
            "NOP", "LXI B,#w", "STAX B", "INX B", "INR B", "DCR B", "MVI B,#b", "RLC", "*NOP", "DAD B", "LDAX B", "DCX B", "INR C", "DCR C", "MVI C,#b", "RRC", // 0_
            "*NOP", "LXI D,#w", "STAX D", "INX D", "INR D", "DCR D", "MVI D,#b", "RAL", "*NOP", "DAD D", "LDAX D", "DCX D", "INR E", "DCR E", "MVI E,#b", "RAR", // 1_
            "*NOP", "LXI H,#w", "SHLD #w", "INX H", "INR H", "DCR H", "MVI H,#b", "DAA", "*NOP", "DAD H", "LHLD #w", "DCX H", "INR L", "DCR L", "MVI L,#b", "CMA", // 2_
            "*NOP", "LXI SP,#w", "STA #w", "INX SP", "INR M", "DCR M", "MVI M,#b", "STC", "*NOP", "DAD SP", "LDA #w", "DCX SP", "INR A", "DCR A", "MVI A,#b", "CMC", // 3_
            "MOV B,B", "MOV B,C", "MOV B,D", "MOV B,E", "MOV B,H", "MOV B,L", "MOV B,M", "MOV B,A", "MOV C,B", "MOV C,C", "MOV C,D", "MOV C,E", "MOV C,H", "MOV C,L", "MOV C,M", "MOV C,A", // 4_
            "MOV D,B", "MOV D,C", "MOV D,D", "MOV D,E", "MOV D,H", "MOV D,L", "MOV D,M", "MOV D,A", "MOV E,B", "MOV E,C", "MOV E,D", "MOV E,E", "MOV E,H", "MOV E,L", "MOV E,M", "MOV E,A", // 5_
            "MOV H,B", "MOV H,C", "MOV H,D", "MOV H,E", "MOV H,H", "MOV H,L", "MOV H,M", "MOV H,A", "MOV L,B", "MOV L,C", "MOV L,D", "MOV L,E", "MOV L,H", "MOV L,L", "MOV L,M", "MOV L,A", // 6_
            "MOV M,B", "MOV M,C", "MOV M,D", "MOV M,E", "MOV M,H", "MOV M,L", "HLT", "MOV M,A", "MOV A,B", "MOV A,C", "MOV A,D", "MOV A,E", "MOV A,H", "MOV A,L", "MOV A,M", "MOV A,A", // 7_
            "ADD B", "ADD C", "ADD D", "ADD E", "ADD H", "ADD L", "ADD M", "ADD A", "ADC B", "ADC C", "ADC D", "ADC E", "ADC H", "ADC L", "ADC M", "ADC A", // 8_
            "SUB B", "SUB C", "SUB D", "SUB E", "SUB H", "SUB L", "SUB M", "SUB A", "SBB B", "SBB C", "SBB D", "SBB E", "SBB H", "SBB L", "SBB M", "SBB A", // 9_
            "ANA B", "ANA C", "ANA D", "ANA E", "ANA H", "ANA L", "ANA M", "ANA A", "XRA B", "XRA C", "XRA D", "XRA E", "XRA H", "XRA L", "XRA M", "XRA A", // A_
            "ORA B", "ORA C", "ORA D", "ORA E", "ORA H", "ORA L", "ORA M", "ORA A", "CMP B", "CMP C", "CMP D", "CMP E", "CMP H", "CMP L", "CMP M", "CMP A", // B_
            "RNZ", "POP B", "JNZ #w", "JMP #w", "CNZ #w", "PUSH B", "ADI #b", "RST 0", "RZ", "RET", "JZ #w", "*JMP #w", "CZ #w", "CALL #w", "ACI #b", "RST 1", // C_
            "RNC", "POP D", "JNC #w", "OUT #b", "CNC #w", "PUSH D", "SUI #b", "RST 2", "RC", "*RET", "JC #w", "IN #b", "CC #w", "*CALL #w", "SBI #b", "RST 3", // D_
            "RPO", "POP H", "JPO #w", "XTHL", "CPO #w", "PUSH H", "ANI #b", "RST 4", "RPE", "PCHL", "JPE #w", "XCHG", "CPE #w", "*CALL #w", "XRI #b", "RST 5", // E_
            "RP", "POP PSW", "JP #w", "DI", "CP #w", "PUSH PSW", "ORI #b", "RST 6", "RM", "SPHL", "JM #w", "EI", "CM #w", "*CALL #w", "CPI #b", "RST 7", // F_
    };

    private static final int[] LENGTHS = new int[256];

    static {
        for (int op = 0; op < 256; op++) {
            String t = TEMPLATES[op];
            LENGTHS[op] = t.endsWith("#w") ? 3 : t.endsWith("#b") ? 2 : 1;
        }
    }

    private Disassembler8080() {
    }

    /** @return instruction length in bytes (1..3) of {@code opcode} */
    public static int length(int opcode) {
        return LENGTHS[opcode & 0xFF];
    }

    /** @return the mnemonic and operand pattern, e.g. {@code "LXI SP,#w"} */
    public static String template(int opcode) {
        return TEMPLATES[opcode & 0xFF];
    }

    /**
     * @param opcode first byte
     * @param b1     second byte (ignored for 1-byte instructions)
     * @param b2     third byte (ignored unless the instruction is 3 bytes)
     */
    public static String disassemble(int opcode, int b1, int b2) {
        String t = TEMPLATES[opcode & 0xFF];
        return switch (LENGTHS[opcode & 0xFF]) {
            case 2 -> t.substring(0, t.length() - 2) + hexOperand(b1 & 0xFF, 2);
            case 3 -> t.substring(0, t.length() - 2) + hexOperand((b2 & 0xFF) << 8 | (b1 & 0xFF), 4);
            default -> t;
        };
    }

    /** Disassembles the instruction at {@code address}. */
    public static String disassemble(MemoryView memory, int address) {
        return disassemble(memory.peek(address & 0xFFFF), memory.peek((address + 1) & 0xFFFF),
                memory.peek((address + 2) & 0xFFFF));
    }

    @Override
    public String disassemble(int address, int[] bytes, int length) {
        if (length < 1) {
            return "??";
        }
        int needed = length(bytes[0]);
        if (length < needed) {
            return template(bytes[0]).replace("#w", "??").replace("#b", "??");
        }
        return disassemble(bytes[0], needed > 1 ? bytes[1] : 0, needed > 2 ? bytes[2] : 0);
    }

    /**
     * A listing of {@code count} instructions from {@code start}, one per line:
     * {@code 0100: 31 00 07  LXI SP,0700H} (bytes column padded to 8 characters).
     */
    public static String listing(MemoryView memory, int start, int count) {
        StringBuilder sb = new StringBuilder();
        int address = start & 0xFFFF;
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            int op = memory.peek(address);
            int len = length(op);
            sb.append(String.format("%04X:", address));
            StringBuilder bytes = new StringBuilder();
            for (int k = 0; k < len; k++) {
                bytes.append(String.format(" %02X", memory.peek((address + k) & 0xFFFF)));
            }
            sb.append(String.format("%-9s", bytes)).append("  ").append(disassemble(memory, address));
            address = (address + len) & 0xFFFF;
        }
        return sb.toString();
    }

    /** Intel-style hex operand: {@code 07H}, {@code 0FFH}, {@code 0100H}, {@code 0C000H}. */
    static String hexOperand(int value, int digits) {
        String hex = String.format("%0" + digits + "X", value);
        return (Character.isLetter(hex.charAt(0)) ? "0" : "") + hex + "H";
    }
}
