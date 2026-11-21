/*
 * Originally derived from:
 * Source: https://github.com/YesSteveModel/YesSteveModel (Apache-2.0)
 *        and https://github.com/stevewongv/YesSteveModel (Apache-2.0)
 *
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy of the License at:
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Modified by MineRealms for RigWorks.
 * Changes: Cleanroom reimplementation / package adaptation / API changes for in-mod YSM decode.
 */
package cn.minerealms.rigworks.ysm.java.zstd;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static sun.misc.Unsafe.ARRAY_BYTE_BASE_OFFSET;

public final class ZstdUtil {

    private ZstdUtil() {}

    public static byte[] compress(byte[] input, int level) {
        return compress(input, 0, input.length, level);
    }

    public static byte[] compress(byte[] input, int inputOffset, int inputLength, int level) {
        if (level < 3 || level > 4) {
            level = 3;
        }
        CompressionParameters parameters = CompressionParameters.compute(level, -1);
        ZstdCompressor compressor = new ZstdCompressor();
        byte[] buffer = new byte[compressor.maxCompressedLength(inputLength)];
        long inputAddress = ARRAY_BYTE_BASE_OFFSET + inputOffset;
        long inputLimit = inputAddress + inputLength;
        long outputAddress = ARRAY_BYTE_BASE_OFFSET;
        long outputLimit = outputAddress + buffer.length;

        long output = outputAddress;
        output += ZstdFrameCompressor.writeMagic(buffer, output, outputLimit);
        output += ZstdFrameCompressor.writeFrameHeader(buffer, output, outputLimit, inputLength, parameters.getWindowSize());
        output += ZstdFrameCompressor.compressFrame(input, inputAddress, inputLimit, buffer, output, outputLimit, parameters);
//        output += ZstdFrameCompressor.writeChecksum(buffer, output, outputLimit, input, inputAddress, inputLimit);

        int compressedSize = (int) (output - outputAddress);
        if (compressedSize == buffer.length) {
            return buffer;
        }
        byte[] result = new byte[compressedSize];
        System.arraycopy(buffer, 0, result, 0, compressedSize);
        return result;
    }

    public static byte[] decompress(byte[] input) {
        return decompress(input, 0, input.length);
    }

    public static byte[] decompress(byte[] input, int offset, int length) {
        ZstdDecompressor decompressor = new ZstdDecompressor();
        long size = decompressor.getDecompressedSize(input, offset, length);
        if (size >= 0) {
            byte[] output = new byte[(int) size];
            decompressor.decompress(input, offset, length, output, 0, output.length);
            return output;
        }
        try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(input, offset, length))) {
            TrimmableByteArrayOutputStream baos = new TrimmableByteArrayOutputStream(Math.max(64 * 1024, length * 2));
            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf)) != -1) {
                baos.write(buf, 0, read);
            }
            return baos.toTrimmedArray();
        } catch (IOException e) {
            throw new MalformedInputException(0, e.getMessage());
        }
    }

    private static final class TrimmableByteArrayOutputStream extends ByteArrayOutputStream {
        TrimmableByteArrayOutputStream(int size) {
            super(size);
        }

        byte[] toTrimmedArray() {
            if (count == buf.length) {
                return buf;
            }
            return Arrays.copyOf(buf, count);
        }
    }
}
