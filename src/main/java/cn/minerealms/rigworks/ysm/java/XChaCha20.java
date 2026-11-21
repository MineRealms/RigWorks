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

/** XChaCha20 with the YSM-specific per-chunk re-keying used for file payloads. */
public final class XChaCha20 {
    private static final int[] CONSTANT = {0x61707865, 0x3320646E, 0x79622D32, 0x6B206574};

    private final int[] state = new int[16];
    private final int[] working = new int[16];
    private final byte[] keystream = new byte[64];
    private int rounds;

    public XChaCha20(byte[] key, byte[] nonce, int initialRounds) {
        if (key.length != 32 || nonce.length != 24) {
            throw new IllegalArgumentException("XChaCha20 requires a 32-byte key and 24-byte nonce");
        }
        this.rounds = initialRounds;
        int[] keyWords = int32(key);
        int[] nonceWords = int32(nonce);
        int[] x = new int[16];
        System.arraycopy(CONSTANT, 0, x, 0, 4);
        System.arraycopy(keyWords, 0, x, 4, 8);
        x[12] = nonceWords[0];
        x[13] = nonceWords[1];
        x[14] = nonceWords[2];
        x[15] = nonceWords[3];
        shuffle(x);
        int[] subkey = new int[]{x[0], x[1], x[2], x[3], x[12], x[13], x[14], x[15]};
        System.arraycopy(CONSTANT, 0, state, 0, 4);
        System.arraycopy(subkey, 0, state, 4, 8);
        state[12] = 0;
        state[13] = 0;
        state[14] = nonceWords[4];
        state[15] = nonceWords[5];
    }

    public void process(byte[] input, int inputOffset, byte[] output, int outputOffset, int length) {
        int index = 0;
        while (index < length) {
            generateBlock();
            int count = Math.min(64, length - index);
            for (int i = 0; i < count; i++) {
                output[outputOffset + index + i] = (byte) (input[inputOffset + index + i] ^ keystream[i]);
            }
            state[12]++;
            if (state[12] == 0) {
                state[13]++;
            }
            index += count;
        }
    }

    public int rekeyWithHash(long hash) {
        rounds = 10 * (int) Long.remainderUnsigned(hash, 3) + 10;
        int low = (int) hash;
        int high = (int) (hash >>> 32);
        for (int i = 4; i < 16; i++) {
            state[i] ^= (i & 1) == 0 ? low : high;
        }
        return (int) (((hash & 0x3FL) | 0x40L) << 6);
    }

    private void generateBlock() {
        System.arraycopy(state, 0, working, 0, 16);
        shuffle(working);
        for (int i = 0; i < 16; i++) {
            working[i] += state[i];
            int value = working[i];
            int position = i << 2;
            keystream[position] = (byte) value;
            keystream[position + 1] = (byte) (value >>> 8);
            keystream[position + 2] = (byte) (value >>> 16);
            keystream[position + 3] = (byte) (value >>> 24);
        }
    }

    private void shuffle(int[] x) {
        for (int round = 0; round < rounds / 2; round++) {
            quarter(x, 0, 4, 8, 12);
            quarter(x, 1, 5, 9, 13);
            quarter(x, 2, 6, 10, 14);
            quarter(x, 3, 7, 11, 15);
            quarter(x, 0, 5, 10, 15);
            quarter(x, 1, 6, 11, 12);
            quarter(x, 2, 7, 8, 13);
            quarter(x, 3, 4, 9, 14);
        }
    }

    private static void quarter(int[] x, int a, int b, int c, int d) {
        x[a] += x[b];
        x[d] = Integer.rotateLeft(x[d] ^ x[a], 16);
        x[c] += x[d];
        x[b] = Integer.rotateLeft(x[b] ^ x[c], 12);
        x[a] += x[b];
        x[d] = Integer.rotateLeft(x[d] ^ x[a], 8);
        x[c] += x[d];
        x[b] = Integer.rotateLeft(x[b] ^ x[c], 7);
    }

    private static int[] int32(byte[] bytes) {
        int[] words = new int[bytes.length / 4];
        for (int i = 0; i < words.length; i++) {
            int offset = i * 4;
            words[i] = (bytes[offset] & 0xFF)
                    | ((bytes[offset + 1] & 0xFF) << 8)
                    | ((bytes[offset + 2] & 0xFF) << 16)
                    | ((bytes[offset + 3] & 0xFF) << 24);
        }
        return words;
    }
}
