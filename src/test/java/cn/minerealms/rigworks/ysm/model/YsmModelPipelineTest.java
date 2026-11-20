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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

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

    @Test
    void glbUsesOneMergedSkinnedMesh() throws Exception {
        YsmModel model = YsmBinaryDecoder.decode(payload());
        byte[] glb = YsmGlbWriter.toGlb(model);
        JsonObject root = JsonParser.parseString(jsonChunk(glb)).getAsJsonObject();

        JsonArray meshes = root.getAsJsonArray("meshes");
        assertEquals(1, meshes.size(), "expected exactly one merged mesh");
        JsonArray primitives = meshes.get(0).getAsJsonObject().getAsJsonArray("primitives");
        assertEquals(1, primitives.size(), "expected exactly one primitive");
        assertEquals(1, root.getAsJsonArray("skins").size(), "expected one skin");

        JsonObject primitive = primitives.get(0).getAsJsonObject();
        JsonObject attributes = primitive.getAsJsonObject("attributes");
        JsonArray accessors = root.getAsJsonArray("accessors");
        JsonObject position = accessors.get(attributes.get("POSITION").getAsInt()).getAsJsonObject();
        JsonObject joints = accessors.get(attributes.get("JOINTS_0").getAsInt()).getAsJsonObject();
        assertEquals("VEC4", joints.get("type").getAsString());
        assertEquals(position.get("count").getAsInt(), joints.get("count").getAsInt());
        JsonObject weights = accessors.get(attributes.get("WEIGHTS_0").getAsInt()).getAsJsonObject();
        assertEquals("VEC4", weights.get("type").getAsString());
        assertEquals(position.get("count").getAsInt(), weights.get("count").getAsInt());
        int indexType = accessors.get(primitive.get("indices").getAsInt()).getAsJsonObject()
                .get("componentType").getAsInt();
        assertTrue(indexType == 5123 || indexType == 5125, "expected ushort or uint indices");

        JsonArray nodes = root.getAsJsonArray("nodes");
        assertEquals(model.bones().size() + 1, nodes.size());
        for (int i = 0; i < model.bones().size(); i++) {
            assertFalse(nodes.get(i).getAsJsonObject().has("children"), "flat bone nodes must be roots");
        }
        JsonObject meshNode = nodes.get(model.bones().size()).getAsJsonObject();
        assertEquals(0, meshNode.get("mesh").getAsInt());
        assertEquals(0, meshNode.get("skin").getAsInt());
        assertEquals(nodes.size(), root.getAsJsonArray("scenes").get(0).getAsJsonObject()
                .getAsJsonArray("nodes").size(), "all nodes must be scene roots");
        // position/scale keyframes are intentionally dropped (they explode the flat rig),
        // so only animations with at least one rotation channel are emitted.
        assertTrue(root.getAsJsonArray("animations").size() <= model.animations().size(),
                "emitted animations must not exceed source animations");

        JsonArray jointNodes = root.getAsJsonArray("skins").get(0).getAsJsonObject().getAsJsonArray("joints");
        byte[] bin = Arrays.copyOfRange(glb, 28 + littleEndian(glb, 12), glb.length);
        float[] inverseBind = accessor(root, bin, root.getAsJsonArray("skins").get(0)
                .getAsJsonObject().get("inverseBindMatrices").getAsInt());
        for (int i = 0; i < jointNodes.size(); i++) {
            JsonObject bone = nodes.get(jointNodes.get(i).getAsInt()).getAsJsonObject();
            float[] q = floats(bone.getAsJsonArray("rotation"));
            float[] t = floats(bone.getAsJsonArray("translation"));
            float[] g = new float[16];
            float x = q[0], y = q[1], z = q[2], w = q[3];
            g[0] = 1 - 2 * (y * y + z * z);
            g[1] = 2 * (x * y + w * z);
            g[2] = 2 * (x * z - w * y);
            g[4] = 2 * (x * y - w * z);
            g[5] = 1 - 2 * (x * x + z * z);
            g[6] = 2 * (y * z + w * x);
            g[8] = 2 * (x * z + w * y);
            g[9] = 2 * (y * z - w * x);
            g[10] = 1 - 2 * (x * x + y * y);
            g[12] = t[0]; g[13] = t[1]; g[14] = t[2]; g[15] = 1;
            for (int c = 0; c < 4; c++) {
                for (int r = 0; r < 4; r++) {
                    float sum = 0f;
                    for (int k = 0; k < 4; k++) sum += g[k * 4 + r] * inverseBind[i * 16 + c * 4 + k];
                    assertEquals(c == r ? 1f : 0f, sum, 1e-4f, "node bind matrix must cancel IBM");
                }
            }
        }
    }

    private static float[] accessor(JsonObject root, byte[] bin, int index) {
        JsonObject acc = root.getAsJsonArray("accessors").get(index).getAsJsonObject();
        JsonObject view = root.getAsJsonArray("bufferViews").get(acc.get("bufferView").getAsInt()).getAsJsonObject();
        String type = acc.get("type").getAsString();
        int components = type.equals("SCALAR") ? 1 : type.equals("VEC2") ? 2
                : type.equals("VEC3") ? 3 : type.equals("VEC4") ? 4 : 16;
        int offset = view.get("byteOffset").getAsInt();
        float[] values = new float[acc.get("count").getAsInt() * components];
        ByteBuffer.wrap(bin, offset, values.length * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values);
        return values;
    }

    private static float[] floats(JsonArray values) {
        float[] out = new float[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.get(i).getAsFloat();
        return out;
    }

    private static String jsonChunk(byte[] glb) {
        int jsonLength = littleEndian(glb, 12);
        return new String(glb, 20, jsonLength, StandardCharsets.UTF_8).trim();
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
        w.vec3(0f, 0f, 0.5f);
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
