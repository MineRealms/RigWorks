/*
 * Originally derived from:
 * Source: https://github.com/YesSteveModel/YesSteveModel (Apache-2.0)
 *        and https://github.com/stevewongv/YesSteveModel (Apache-2.0)
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Modified by MineRealms for RigWorks.
 * Changes: cleanroom reimplementation / package adaptation for YSM decoding in RigWorks.
 */
package cn.minerealms.rigworks.ysm.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class YsmByteReader {
    private final byte[] data;
    private int offset;

    public YsmByteReader(byte[] data) {
        this.data = data;
    }

    public int offset() {
        return offset;
    }

    public void setOffset(int offset) throws IOException {
        check(offset < 0 || offset > data.length, "offset out of range: " + offset);
        this.offset = offset;
    }

    public int remaining() {
        return data.length - offset;
    }

    public byte readByte() throws IOException {
        require(1);
        return data[offset++];
    }

    public int readUnsignedByte() throws IOException {
        return readByte() & 0xFF;
    }

    public void skip(int count) throws IOException {
        check(count < 0 || count > remaining(), "skip out of range: " + count);
        offset += count;
    }

    public long readDword() throws IOException {
        require(4);
        long value = (long) (data[offset] & 0xFF)
                | ((long) (data[offset + 1] & 0xFF) << 8)
                | ((long) (data[offset + 2] & 0xFF) << 16)
                | ((long) (data[offset + 3] & 0xFF) << 24);
        offset += 4;
        return value;
    }

    public float readFloat() throws IOException {
        return Float.intBitsToFloat((int) readDword());
    }

    public int readVarInt() throws IOException {
        int value = 0;
        int position = 0;
        while (true) {
            int current = readUnsignedByte();
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) return value;
            position += 7;
            check(position >= 35, "VarInt too big at offset " + offset);
        }
    }

    public long readVarLong() throws IOException {
        long value = 0L;
        int position = 0;
        while (true) {
            int current = readUnsignedByte();
            value |= (long) (current & 0x7F) << position;
            if ((current & 0x80) == 0) return value;
            position += 7;
            check(position >= 70, "VarLong too big at offset " + offset);
        }
    }

    public byte[] readByteArray() throws IOException {
        int length = readVarInt();
        check(length < 0 || length > remaining(), "byte array length out of range: " + length);
        byte[] result = new byte[length];
        System.arraycopy(data, offset, result, 0, length);
        offset += length;
        return result;
    }

    public String readString() throws IOException {
        byte[] bytes = readByteArray();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public Vec3 readVec3() throws IOException {
        return new Vec3(readFloat(), readFloat(), readFloat());
    }

    public float readTypedValue() throws IOException {
        int datatype = readUnsignedByte();
        if (datatype == 1) return readFloat();
        if (datatype == 2) {
            String raw = readString();
            try {
                return Float.parseFloat(raw);
            } catch (NumberFormatException ignored) {
                return 0f;
            }
        }
        throw new IOException("Unknown keyframe datatype " + datatype + " at offset " + (offset - 1));
    }

    private void require(int count) throws IOException {
        check(count > remaining(), "unexpected end of payload at offset " + offset);
    }

    private static void check(boolean condition, String message) throws IOException {
        if (condition) throw new IOException(message);
    }
}
