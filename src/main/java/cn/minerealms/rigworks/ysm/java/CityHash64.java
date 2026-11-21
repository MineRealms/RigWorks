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

/** Cleanroom 64-bit CityHash variant used by the YSM file and key derivations. */
public final class CityHash64 {
    private static final long K0 = 0xE4986A230E5AAA17L;
    private static final long K1 = 0x91AF10802CAB25A5L;
    private static final long K2 = 0xAF29CE778879D9C7L;
    private static final long K_MUL = 0xDE0F6EE09BDBAB91L;

    private CityHash64() {
    }

    public static long hash(byte[] data) {
        return hash(data, 0, data.length);
    }

    public static long hash(byte[] data, int base, int len) {
        if (len <= 32) {
            return len <= 16 ? shortHash(data, base, len) : mediumHash(data, base, len);
        }
        if (len <= 64) {
            return longHash(data, base, len);
        }

        long x = fetch64(data, base + len - 40);
        long y = fetch64(data, base + len - 16) + fetch64(data, base + len - 56);
        long z = mixPair(fetch64(data, base + len - 48) + len, fetch64(data, base + len - 24));

        long[] v = weakHash(data, base + len - 64, len, z);
        long[] w = weakHash(data, base + len - 32, y + K1, x);
        x = x * K1 + fetch64(data, base);

        int remaining = (len - 1) & ~63;
        int pos = base;
        do {
            x = Long.rotateRight(x + y + v[0] + fetch64(data, pos + 8), 37) * K1;
            y = Long.rotateRight(y + v[1] + fetch64(data, pos + 48), 42) * K1;
            x ^= w[1];
            y += v[0] + fetch64(data, pos + 40);
            z = Long.rotateRight(z + w[0], 33) * K1;
            v = weakHash(data, pos, v[1] * K1, x + w[0]);
            w = weakHash(data, pos + 32, z + w[1], y + fetch64(data, pos + 16));
            long swap = x;
            x = z;
            z = swap;
            pos += 64;
            remaining -= 64;
        } while (remaining != 0);

        return mixPair(mixPair(v[0], w[0]) + scramble(y) * K1 + z, mixPair(v[1], w[1]) + x);
    }

    public static long hashWithSeed(byte[] data, long seed) {
        return hashWithSeed(data, 0, data.length, seed);
    }

    public static long hashWithSeed(byte[] data, int base, int len, long seed) {
        return mixPair(hash(data, base, len) - K2, seed);
    }

    private static long shortHash(byte[] data, int base, int len) {
        if (len >= 8) {
            long mul = K2 + len * 2;
            long a = fetch64(data, base) + K2;
            long b = fetch64(data, base + len - 8);
            long c = Long.rotateRight(b, 37) * mul + a;
            long d = (Long.rotateRight(a, 25) + b) * mul;
            return mixPairWithMul(c, d, mul);
        }
        if (len >= 4) {
            long mul = K2 + len * 2;
            long a = fetch32(data, base) & 0xFFFFFFFFL;
            long b = fetch32(data, base + len - 4) & 0xFFFFFFFFL;
            return mixPairWithMul(len + (a << 3), b, mul);
        }
        if (len > 0) {
            long a = data[base] & 0xFFL;
            long b = data[base + (len >>> 1)] & 0xFFL;
            long c = data[base + len - 1] & 0xFFL;
            return scramble((a + (b << 8)) * K2 ^ (len + (c << 2)) * K0) * K2;
        }
        return K2;
    }

    private static long mediumHash(byte[] data, int base, int len) {
        long mul = K2 + len * 2;
        long a = fetch64(data, base) * K1;
        long b = fetch64(data, base + 8);
        long c = fetch64(data, base + len - 8) * mul;
        long d = fetch64(data, base + len - 16) * K2;
        return mixPairWithMul(Long.rotateRight(a + b, 43) + Long.rotateRight(c, 30) + d,
                a + Long.rotateRight(b + K2, 18) + c, mul);
    }

    private static long longHash(byte[] data, int base, int len) {
        long mul = K2 + len * 2;
        long a = fetch64(data, base) * K2;
        long b = fetch64(data, base + 8);
        long c = fetch64(data, base + len - 24);
        long d = fetch64(data, base + len - 32);
        long e = fetch64(data, base + 16) * K2;
        long f = fetch64(data, base + 24) * 9;
        long g = fetch64(data, base + len - 8);
        long h = fetch64(data, base + len - 16) * mul;
        long u = Long.rotateRight(a + g, 43) + (Long.rotateRight(b, 30) + c) * 9;
        long v = ((a + g) ^ d) + f + 1;
        long w = Long.reverseBytes((u + v) * mul) + h;
        long x = Long.rotateRight(e + f, 42) + c;
        long y = (Long.reverseBytes((v + w) * mul) + g) * mul;
        long z = e + f + c;
        a = Long.reverseBytes((x + z) * mul + y) + b;
        b = scramble((z + a) * mul + d + h) * mul;
        return b + x;
    }

    private static long[] weakHash(byte[] data, int pos, long a, long b) {
        long w0 = fetch64(data, pos);
        long x0 = fetch64(data, pos + 8);
        long y0 = fetch64(data, pos + 16);
        long z0 = fetch64(data, pos + 24);
        a += w0;
        b = Long.rotateRight(b + a + z0, 21);
        long c = a;
        a += x0 + y0;
        b += Long.rotateRight(a, 44);
        return new long[]{a + z0, b + c};
    }

    private static long mixPair(long u, long v) {
        long t = (u ^ v) * K_MUL;
        long r = (scramble(t) ^ u) * K_MUL;
        return K_MUL * scramble(r);
    }

    private static long mixPairWithMul(long u, long v, long mul) {
        long a = (u ^ v) * mul;
        a ^= a >>> 47;
        long b = (v ^ a) * mul;
        b ^= b >>> 47;
        return b * mul;
    }

    private static long scramble(long value) {
        return value ^ (value >>> 47);
    }

    private static long fetch64(byte[] data, int pos) {
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result |= (data[pos + i] & 0xFFL) << (i * 8);
        }
        return result;
    }

    private static int fetch32(byte[] data, int pos) {
        int result = 0;
        for (int i = 0; i < 4; i++) {
            result |= (data[pos + i] & 0xFF) << (i * 8);
        }
        return result;
    }
}
