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

import cn.minerealms.rigworks.ysm.YsmPayloadDecoder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class YsmModelPipelineTest {
    private static final String DEFAULT_PAYLOAD =
            "H:\\MinecraftMods\\RigWorks-ysm-native\\build\\ysm-decoded.bin";

    @Test
    void decodedPayloadBecomesModelAndValidGlb() throws Exception {
        byte[] payload = payload();
        YsmPayloadDecoder supplier = ignored -> payload;

        YsmModel model = YsmBinaryDecoder.decode(supplier.decode(new byte[0]));

        assertNotNull(model);
        assertEquals(9, model.format());
        assertFalse(model.bones().isEmpty(), "expected bones");
        int cubes = model.bones().stream().mapToInt(bone -> bone.cubes().size()).sum();
        assertTrue(cubes > 0, "expected cubes");
        assertFalse(model.textures().isEmpty(), "expected textures");

        byte[] glb = YsmGlbWriter.toGlb(model);
        assertTrue(glb.length >= 20, "GLB too short");
        int magic = littleEndian(glb, 0);
        int version = littleEndian(glb, 4);
        int declaredLength = littleEndian(glb, 8);
        assertEquals(0x46546C67, magic, "GLB magic");
        assertEquals(2, version, "GLB version");
        assertEquals(glb.length, declaredLength, "GLB declared length");
        int jsonLength = littleEndian(glb, 12);
        assertEquals(0x4E4F534A, littleEndian(glb, 16), "JSON chunk magic");
        assertTrue(jsonLength > 0 && 20 + jsonLength <= glb.length);
        assertEquals(0x004E4942, littleEndian(glb, 24 + jsonLength), "BIN chunk magic");
        assertTrue(YsmPngEncoder.isPng(model.textures().values().iterator().next().pngBytes()));
    }

    private static byte[] payload() throws Exception {
        String override = System.getProperty("rigworks.ysm.decodedPayload", DEFAULT_PAYLOAD);
        Path path = Path.of(override);
        if (Files.isRegularFile(path)) return Files.readAllBytes(path);
        return tinyPayload();
    }

    static byte[] tinyPayload() {
        Payload w = new Payload();
        w.dword(9);
        w.varint(0);
        w.varint(1);
        w.varint(1);
        w.varint(1);

        w.varint(1);
        w.str("");
        w.varint(1);
        w.varint(6);
        for (int face = 0; face < 6; face++) {
            w.vec3(0f, 0f, 1f);
            for (int v = 0; v < 4; v++) {
                w.vec3(v, face, 0f);
                w.float32((v % 2) * 0.1f);
                w.float32((face % 2) * 0.1f);
            }
        }
        w.varint(0);
        w.varint(0);
        w.varint(0);
        w.str("body");
        for (int i = 0; i < 5; i++) w.varint(0);
        w.vec3(0f, 0f, 0f);
        w.vec3(0f, 0f, 0f);
        w.str("geometry.test");
        w.float32(16f);
        w.float32(16f);
        w.float32(0f);
        w.float32(0f);
        w.varint(0);
        w.float32(0.7f);
        w.float32(0.7f);
        w.varint(0);
        w.varint(0);
        w.varint(0);
        w.varint(0);

        w.varint(1);
        w.varint(1);
        w.varint(1);
        w.varint(1);
        w.str("idle");
        w.float32(40f);
        w.varint(1);
        w.varint(1);
        w.str("body");
        w.varint(0);
        w.varint(0);
        w.varint(2);
        w.float32(0f);
        w.varint(0);
        w.typed(1f);
        w.typed(1f);
        w.typed(1f);
        w.varint(0);
        w.float32(20f);
        w.varint(0);
        w.typed(1.1f);
        w.typed(1.1f);
        w.typed(1.1f);
        w.varint(0);
        w.varint(0);

        w.varint(1);
        w.str("skin");
        byte[] rgba = new byte[2 * 2 * 4];
        java.util.Arrays.fill(rgba, (byte) 0xFF);
        w.bytes(rgba);
        w.varint(2);
        w.varint(2);
        w.varint(0);

        w.varint(0);
        w.varint(0);
        w.varint(0);
        w.varint(0);
        return w.toByteArray();
    }

    private static int littleEndian(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    public static void main(String[] args) throws Exception {
        new YsmModelPipelineTest().decodedPayloadBecomesModelAndValidGlb();
        System.out.println("YsmModelPipelineTest OK");
    }

    private static final class Payload {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void dword(int value) {
            for (int i = 0; i < 4; i++) out.write((value >>> (i * 8)) & 0xFF);
        }

        void varint(int value) {
            int v = value;
            while ((v & -128) != 0) {
                out.write((v & 127) | 128);
                v >>>= 7;
            }
            out.write(v);
        }

        void str(String value) {
            bytes(value.getBytes(StandardCharsets.UTF_8));
        }

        void bytes(byte[] value) {
            varint(value.length);
            out.write(value, 0, value.length);
        }

        void vec3(float x, float y, float z) {
            float32(x);
            float32(y);
            float32(z);
        }

        void float32(float value) {
            dword(Float.floatToRawIntBits(value));
        }

        void typed(float value) {
            out.write(1);
            float32(value);
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}
