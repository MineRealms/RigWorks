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

import cn.minerealms.rigworks.ysm.java.YsmJavaDecoder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YsmDecoderTest {
    private static final String ASSET = "/ysm/Flandre_Scarlet.ysm";

    @Test
    void detectsLegacyV3Header() throws IOException {
        byte[] file = asset();
        assertTrue(YsmDecoder.isYsm(file));
        int format = YsmDecoder.detectFormat(file);
        assertTrue(format >= 5 && format <= 15, "unexpected format " + format);
    }

    @Test
    void decodesLegacyV3WithDefaultBackend() throws IOException {
        byte[] decoded = YsmDecoder.decode(asset());
        assertTrue(decoded.length > 10000, "decoded payload is too short: " + decoded.length);
        int format = readLe32(decoded);
        assertTrue(format >= 5 && format <= 15, "unexpected decoded format int " + format);
        assertTrue("native".equals(YsmDecoder.backend()) || "java".equals(YsmDecoder.backend()));
    }

    @Test
    void pureJavaFallbackMatchesNativeOutput() throws IOException {
        byte[] file = asset();
        byte[] javaDecoded = YsmJavaDecoder.decode(file);
        byte[] defaultDecoded = YsmDecoder.decode(file);
        assertTrue(javaDecoded.length > 10000);
        assertArrayEquals(defaultDecoded, javaDecoded);
        assertEquals(readLe32(javaDecoded), readLe32(defaultDecoded));
    }

    private static byte[] asset() throws IOException {
        try (InputStream in = YsmDecoderTest.class.getResourceAsStream(ASSET)) {
            if (in == null) {
                throw new IOException("Missing test asset " + ASSET);
            }
            return in.readAllBytes();
        }
    }

    private static int readLe32(byte[] bytes) {
        return (bytes[0] & 0xFF)
                | ((bytes[1] & 0xFF) << 8)
                | ((bytes[2] & 0xFF) << 16)
                | ((bytes[3] & 0xFF) << 24);
    }
}
