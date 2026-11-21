package cn.minerealms.rigworks.ysm.asset;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class YsmSourceFormatTest {
    @Test void acceptsARealLegacyV3Envelope() throws Exception {
        try (var input = getClass().getResourceAsStream("/ysm/Flandre_Scarlet.ysm")) {
            assertNotNull(input);
            assertDoesNotThrow(() -> YsmSourceFormat.requireLegacyV3(input.readAllBytes()));
        }
    }

    @Test void rejectsOldAndModernContainersBeforeNativeLoading() {
        byte[] old = new byte[80];
        System.arraycopy("YSGP".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, old, 0, 4);
        assertTrue(assertThrows(IOException.class, () -> RwymNative.convert(old)).getMessage().contains("V1/V2"));

        byte[] modern = new byte[80];
        modern[0] = (byte) 0xef; modern[1] = (byte) 0xbb; modern[2] = (byte) 0xbf;
        System.arraycopy("YSGP2".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, modern, 3, 5);
        assertTrue(assertThrows(IOException.class, () -> RwymNative.convert(modern)).getMessage().contains("MXC"));
    }

    @Test void distinguishesCryptoVersionFromDecryptedPayloadLayout() {
        byte[] source = new byte[96];
        source[0] = (byte) 0xef; source[1] = (byte) 0xbb; source[2] = (byte) 0xbf;
        System.arraycopy("YSGP".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, source, 3, 4);
        source[7] = 0;
        source[8] = 1;
        assertTrue(assertThrows(IOException.class, () -> YsmSourceFormat.requireLegacyV3(source))
                .getMessage().contains("crypto version 1"));
        source[8] = 3;
        assertDoesNotThrow(() -> YsmSourceFormat.requireLegacyV3(source));
    }
}
