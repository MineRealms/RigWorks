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
package cn.minerealms.rigworks.ysm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entry point for reading YesSteveModel files.
 *
 * <p>The native {@code ysmprocessor} backend is preferred. If it cannot be loaded,
 * decoding transparently falls back to the bundled pure-Java implementation.</p>
 */
public final class YsmDecoder {
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] LEGACY_MAGIC = {'Y', 'S', 'G', 'P'};
    private static final byte[] MODERN_MAGIC = {'Y', 'S', 'G', 'P', '2'};
    private static final Pattern FORMAT = Pattern.compile("<format>\\s*(\\d+)");

    private YsmDecoder() {
    }

    /** Checks BOM+YSGP magic (legacy) and BOM+YSGP2 (modern). */
    public static boolean isYsm(byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length < 8) {
            return false;
        }
        for (int i = 0; i < BOM.length; i++) {
            if (fileBytes[i] != BOM[i]) {
                return false;
            }
        }
        if (startsWith(fileBytes, 3, MODERN_MAGIC)) {
            return true;
        }
        return startsWith(fileBytes, 3, LEGACY_MAGIC);
    }

    /** Returns format version from header metadata ({@code <format> N}), or -1. */
    public static int detectFormat(byte[] fileBytes) {
        if (!isYsm(fileBytes)) {
            return -1;
        }
        int end = 0;
        while (end < fileBytes.length && fileBytes[end] != 0x00) {
            end++;
        }
        String header = new String(fileBytes, 0, end, StandardCharsets.ISO_8859_1);
        Matcher matcher = FORMAT.matcher(header);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    /**
     * Full decode: legacy V3 decrypt (XChaCha20+MT19937+zstd-wash+zstd) or
     * modern container decompress. Returns raw decompressed payload bytes.
     * Uses the native backend if available, otherwise pure Java.
     */
    public static byte[] decode(byte[] fileBytes) throws IOException {
        if (fileBytes == null) {
            throw new IOException("YSM file bytes are null");
        }
        if (nativeAvailable()) {
            try {
                return YsmNative.decodeLegacyV3(fileBytes);
            } catch (IOException | RuntimeException | LinkageError nativeFailure) {
                return decodeJava(fileBytes);
            }
        }
        return decodeJava(fileBytes);
    }

    /** true when native backend loaded. */
    public static boolean nativeAvailable() {
        try {
            return YsmNative.AVAILABLE;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** "native" | "java" */
    public static String backend() {
        return nativeAvailable() ? "native" : "java";
    }

    private static byte[] decodeJava(byte[] fileBytes) throws IOException {
        return cn.minerealms.rigworks.ysm.java.YsmJavaDecoder.decode(fileBytes);
    }

    private static boolean startsWith(byte[] data, int offset, byte[] needle) {
        if (offset + needle.length > data.length) {
            return false;
        }
        for (int i = 0; i < needle.length; i++) {
            if (data[offset + i] != needle[i]) {
                return false;
            }
        }
        return true;
    }
}
