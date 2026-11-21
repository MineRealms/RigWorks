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

/** MT19937-64 keystream generator used by the YSM XOR layer. */
public final class MersenneTwister64 {
    private static final int N = 312;
    private static final int M = 156;
    private static final long A = 0xB5026F5AA96619E9L;
    private static final long LOWER_MASK = 0x7FFFFFFFL;
    private static final long UPPER_MASK = 0xFFFFFFFF80000000L;
    private static final long MULTIPLIER = 6364136223846793005L;

    private final long[] state = new long[N];
    private int cursor = N;

    public MersenneTwister64(long seed) {
        state[0] = seed;
        for (int i = 1; i < N; i++) {
            long previous = state[i - 1];
            state[i] = MULTIPLIER * (previous ^ (previous >>> 62)) + i;
        }
    }

    public long nextLong() {
        if (cursor >= N) {
            twist();
        }
        long value = state[cursor++];
        value ^= (value >>> 29) & 0x5555555555555555L;
        value ^= (value << 17) & 0x71D67FFFEDA60000L;
        value ^= (value << 37) & 0xFFF7EEE000000000L;
        value ^= value >>> 43;
        return value;
    }

    private void twist() {
        for (int i = 0; i < N; i++) {
            long x = (state[i] & UPPER_MASK) | (state[(i + 1) % N] & LOWER_MASK);
            long shifted = x >>> 1;
            if ((x & 1L) != 0L) {
                shifted ^= A;
            }
            state[i] = state[(i + M) % N] ^ shifted;
        }
        cursor = 0;
    }
}
