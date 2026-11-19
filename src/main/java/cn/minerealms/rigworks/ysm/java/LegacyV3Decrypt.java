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
package cn.minerealms.rigworks.ysm.java;

import cn.minerealms.rigworks.ysm.java.zstd.ZstdUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Pure-Java implementation of the YSM legacy V3 file decoder. */
public final class LegacyV3Decrypt {
    private static final long SEED_KEY_DERIVATION = 0xD017CBBA7B5D3581L;
    private static final long SEED_FILE_VERIFICATION = 0x9E5599DB80C67C29L;
    private static final long SEED_RES_VERIFICATION = 0xA62B1A2C43842BC3L;

    private LegacyV3Decrypt() {
    }

    public static byte[] decrypt(byte[] file) throws IOException {
        if (file.length < 8 + 24 + 32 + 8) {
            throw new IOException("YSM file is too short");
        }

        int headerEnd = 0;
        while (headerEnd < file.length && file[headerEnd] != 0x00) {
            headerEnd++;
        }
        if (headerEnd >= file.length) {
            throw new IOException("YSM header terminator is missing");
        }

        int tail = file.length - 64;
        byte[] key = Arrays.copyOfRange(file, tail, tail + 32);
        byte[] iv = Arrays.copyOfRange(file, tail + 32, tail + 56);
        long storedHash = ByteBuffer.wrap(file, tail + 56, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        long computedHash = CityHash64.hashWithSeed(file, 0, file.length - 8, SEED_FILE_VERIFICATION);
        if (computedHash != storedHash) {
            throw new IOException("YSM file hash mismatch");
        }

        int cryptoOffset = headerEnd + 1;
        if (cryptoOffset + 4 > tail) {
            throw new IOException("YSM crypto field is truncated");
        }
        int crypto = ByteBuffer.wrap(file, cryptoOffset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (crypto != 3) {
            throw new IOException("Unsupported YSM crypto version: " + crypto);
        }
        cryptoOffset += 4;

        byte[] payload = modifiedChaChaDecrypt(file, cryptoOffset, tail - cryptoOffset, key, iv, SEED_RES_VERIFICATION);
        byte[] keyIv = new byte[56];
        System.arraycopy(key, 0, keyIv, 0, 32);
        System.arraycopy(iv, 0, keyIv, 32, 24);
        xorWithMt19937(payload, keyIv);

        if (payload.length < 2) {
            throw new IOException("YSM decrypted body is truncated");
        }
        int padding = ((payload[0] & 0xFF) | ((payload[1] & 0xFF) << 8)) & 0x3FF;
        int frameOffset = 2 + padding;
        if (frameOffset >= payload.length) {
            throw new IOException("YSM padding length is invalid");
        }
        YsmZstdWash.washInPlace(payload, frameOffset, payload.length - frameOffset);
        return ZstdUtil.decompress(payload, frameOffset, payload.length - frameOffset);
    }

    private static byte[] modifiedChaChaDecrypt(byte[] data, int dataOffset, int dataLength,
                                                byte[] key, byte[] iv, long seed) {
        byte[] keyIv = new byte[56];
        System.arraycopy(key, 0, keyIv, 0, 32);
        System.arraycopy(iv, 0, keyIv, 32, 24);
        long derivation = CityHash64.hashWithSeed(keyIv, seed);
        int nextChunk = (int) (((derivation & 0x3FL) | 0x40L) << 6);
        int rounds = (int) (10 * Long.remainderUnsigned(derivation, 3) + 10);
        XChaCha20 cipher = new XChaCha20(key, iv, rounds);

        byte[] result = new byte[dataLength];
        int pointer = 0;
        while (pointer < dataLength) {
            if (pointer + nextChunk > dataLength) {
                nextChunk = dataLength - pointer;
            }
            cipher.process(data, dataOffset + pointer, result, pointer, nextChunk);
            pointer += nextChunk;
            if (pointer < dataLength) {
                long chunkHash = CityHash64.hashWithSeed(result, pointer - nextChunk, nextChunk, seed);
                nextChunk = cipher.rekeyWithHash(chunkHash);
            }
        }
        return result;
    }

    private static void xorWithMt19937(byte[] data, byte[] keyIv) {
        long seed = CityHash64.hashWithSeed(keyIv, SEED_KEY_DERIVATION);
        MersenneTwister64 mt = new MersenneTwister64(seed);
        int index = 0;
        while (index < data.length) {
            long value = mt.nextLong();
            for (int byteIndex = 0; byteIndex < 8 && index < data.length; byteIndex++, index++) {
                data[index] ^= (byte) (value >>> (byteIndex * 8));
            }
        }
    }
}
