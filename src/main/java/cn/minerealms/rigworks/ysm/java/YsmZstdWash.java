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

/** Converts YSM-obfuscated zstd block headers back into standard zstd frames. */
public final class YsmZstdWash {
    private static final int MAGIC = 0xFD2FB528;
    private static final int SIZE_XOR = 0xD4E9;

    private YsmZstdWash() {
    }

    public static void washInPlace(byte[] data, int base, int length) {
        if (data == null || length < 5) {
            throw new IllegalArgumentException("Invalid washed frame length");
        }
        int magic = (data[base] & 0xFF)
                | ((data[base + 1] & 0xFF) << 8)
                | ((data[base + 2] & 0xFF) << 16)
                | ((data[base + 3] & 0xFF) << 24);
        if (magic != MAGIC) {
            throw new IllegalArgumentException("Not a standard ZSTD magic number");
        }

        int fhd = data[base + 4] & 0xFF;
        data[base + 4] = (byte) (fhd & 0xFB);

        int offset = base + 4 + frameHeaderSize(fhd);
        int end = base + length;
        while (offset + 3 <= end) {
            int b0 = data[offset] & 0xFF;
            int b1 = data[offset + 1] & 0xFF;
            int b2 = data[offset + 2] & 0xFF;
            int lastBlock = (b0 >>> 7) & 1;
            int ysmType = (b0 >>> 5) & 3;
            int rawSize = ((b0 & 0x1F) << 16) | b1 | (b2 << 8);
            int size = rawSize ^ SIZE_XOR;
            int standardType = switch (ysmType) {
                case 0 -> 2;
                case 1 -> 1;
                case 2 -> 3;
                case 3 -> 0;
                default -> throw new IllegalStateException("Unknown YSM block type");
            };
            int header = lastBlock | (standardType << 1) | (size << 3);
            data[offset] = (byte) header;
            data[offset + 1] = (byte) (header >>> 8);
            data[offset + 2] = (byte) (header >>> 16);
            offset += 3 + (standardType == 1 ? 1 : size);
            if (lastBlock == 1) {
                break;
            }
        }
    }

    private static int frameHeaderSize(int fhd) {
        int dictBits = fhd & 3;
        int dictSize = switch (dictBits) {
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 4;
            default -> 0;
        };
        boolean singleSegment = ((fhd >>> 5) & 1) == 1;
        int fcsBits = (fhd >>> 6) & 3;
        int fcsSize = switch (fcsBits) {
            case 0 -> singleSegment ? 1 : 0;
            case 1 -> 2;
            case 2 -> 4;
            case 3 -> 8;
            default -> throw new IllegalStateException("Unknown FCS size");
        };
        return 1 + (singleSegment ? 0 : 1) + dictSize + fcsSize;
    }
}
