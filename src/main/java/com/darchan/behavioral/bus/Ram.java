package com.darchan.behavioral.bus;

/** 64 KiB of plain RAM covering the whole 16-bit address space. */
public final class Ram implements MemoryBus {

    public static final int SIZE = 0x10000;

    private final byte[] bytes = new byte[SIZE];

    @Override
    public int read(int address) {
        return bytes[address & 0xFFFF] & 0xFF;
    }

    @Override
    public void write(int address, int value) {
        bytes[address & 0xFFFF] = (byte) value;
    }

    @Override
    public int fetch(int address) {
        return bytes[address & 0xFFFF] & 0xFF;
    }

    @Override
    public int peek(int address) {
        return bytes[address & 0xFFFF] & 0xFF;
    }

    /** Copies {@code data} to {@code address} onwards. */
    public void load(int address, byte[] data) {
        if (address < 0 || address + data.length > SIZE) {
            throw new IllegalArgumentException(data.length + " bytes do not fit at " + Integer.toHexString(address));
        }
        System.arraycopy(data, 0, bytes, address, data.length);
    }

    /** Copies {@code length} bytes starting at {@code address}. */
    public byte[] copy(int address, int length) {
        byte[] out = new byte[length];
        System.arraycopy(bytes, address, out, 0, length);
        return out;
    }
}
