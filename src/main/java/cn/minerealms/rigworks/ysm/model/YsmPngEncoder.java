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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

public final class YsmPngEncoder {
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private YsmPngEncoder() {
    }

    public static boolean isPng(byte[] data) {
        if (data == null || data.length < SIGNATURE.length) return false;
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (data[i] != SIGNATURE[i]) return false;
        }
        return true;
    }

    public static byte[] toPng(byte[] data, int width, int height) throws IOException {
        if (isPng(data)) return data;
        if (width <= 0 || height <= 0) {
            throw new IOException("Invalid raw image dimensions: " + width + "x" + height);
        }
        int expected = width * height * 4;
        if (data == null || data.length < expected) {
            throw new IOException("Raw RGBA texture too short: " + (data == null ? 0 : data.length)
                    + " < " + expected);
        }

        ByteArrayOutputStream raw = new ByteArrayOutputStream(expected + height);
        for (int y = 0; y < height; y++) {
            raw.write(0);
            raw.write(data, y * width * 4, width * 4);
        }

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try (DeflaterOutputStream stream = new DeflaterOutputStream(compressed, deflater, 8192, true)) {
            stream.write(raw.toByteArray());
        } finally {
            deflater.end();
        }

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(SIGNATURE);
        writeChunk(png, "IHDR", ihdr(width, height));
        writeChunk(png, "IDAT", compressed.toByteArray());
        writeChunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static byte[] ihdr(int width, int height) {
        byte[] chunk = new byte[13];
        putInt(chunk, 0, width);
        putInt(chunk, 4, height);
        chunk[8] = 8;
        chunk[9] = 6;
        chunk[10] = 0;
        chunk[11] = 0;
        chunk[12] = 0;
        return chunk;
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] payload) {
        putInt(out, payload.length);
        for (int i = 0; i < 4; i++) out.write(type.charAt(i) & 0xFF);
        out.write(payload, 0, payload.length);
        CRC32 crc = new CRC32();
        crc.update(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, 4);
        crc.update(payload, 0, payload.length);
        putInt(out, (int) crc.getValue());
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static void putInt(ByteArrayOutputStream target, int value) {
        target.write(value >>> 24);
        target.write(value >>> 16);
        target.write(value >>> 8);
        target.write(value);
    }
}
