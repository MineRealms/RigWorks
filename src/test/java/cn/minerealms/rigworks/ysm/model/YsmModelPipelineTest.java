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
import cn.minerealms.rigworks.ysm.java.YsmJavaDecoder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
    void glbUsesPerCubeRigidMeshesAndHierarchicalBones() throws Exception {
        YsmModel model = YsmBinaryDecoder.decode(payload());
        byte[] glb = YsmGlbWriter.toGlb(model);
        JsonObject root = JsonParser.parseString(jsonChunk(glb)).getAsJsonObject();

        int boneCount = model.bones().size();
        int cubeCount = model.bones().stream().mapToInt(bone -> bone.cubes().size()).sum();
        JsonArray meshes = root.getAsJsonArray("meshes");
        assertEquals(cubeCount, meshes.size(), "one glTF mesh per cube");
        assertEquals(0, root.getAsJsonArray("skins").size(), "skins must be removed");
        assertEquals(0, root.getAsJsonArray("animations").size(), "animations stay disabled");

        JsonArray nodes = root.getAsJsonArray("nodes");
        assertEquals(boneCount + cubeCount, nodes.size());
        JsonArray accessors = root.getAsJsonArray("accessors");
        int expectedVertices = model.bones().stream()
                .flatMap(bone -> bone.cubes().stream())
                .mapToInt(cube -> cube.faces().size() * 4).sum();
        int totalVertices = 0;
        for (int m = 0; m < meshes.size(); m++) {
            JsonObject mesh = meshes.get(m).getAsJsonObject();
            JsonArray primitives = mesh.getAsJsonArray("primitives");
            assertEquals(1, primitives.size(), "one primitive per cube mesh");
            JsonObject attributes = primitives.get(0).getAsJsonObject().getAsJsonObject("attributes");
            assertEquals(3, attributes.size(), "rigid cubes only carry POSITION/NORMAL/TEXCOORD_0");
            assertFalse(attributes.has("JOINTS_0"));
            assertFalse(attributes.has("WEIGHTS_0"));
            totalVertices += accessors.get(attributes.get("POSITION").getAsInt()).getAsJsonObject()
                    .get("count").getAsInt();
        }
        assertEquals(expectedVertices, totalVertices);

        java.util.Map<String, Integer> indexByName = new java.util.HashMap<>();
        for (int i = 0; i < boneCount; i++) indexByName.put(model.bones().get(i).name(), i);
        java.util.Set<Integer> cubeChildren = new java.util.HashSet<>();
        for (int i = 0; i < boneCount; i++) {
            JsonObject node = nodes.get(i).getAsJsonObject();
            assertFalse(node.has("mesh"), "bone node must not carry a mesh");
            assertFalse(node.has("skin"), "bone nodes must not be skinned");
            assertTrue(node.has("translation") && node.has("rotation"));
            JsonArray children = node.getAsJsonArray("children");
            if (children != null) {
                for (int c = 0; c < children.size(); c++) cubeChildren.add(children.get(c).getAsInt());
            }
            String parent = model.bones().get(i).parent();
            if (parent != null && !parent.isEmpty() && indexByName.containsKey(parent)) {
                JsonArray parentChildren = nodes.get(indexByName.get(parent)).getAsJsonObject().getAsJsonArray("children");
                assertNotNull(parentChildren, "parent bone must link its children");
                boolean linked = false;
                for (int c = 0; c < parentChildren.size(); c++) linked |= parentChildren.get(c).getAsInt() == i;
                assertTrue(linked, "bone " + model.bones().get(i).name() + " must be a child of " + parent);
            }
        }
        for (int i = boneCount; i < nodes.size(); i++) {
            JsonObject node = nodes.get(i).getAsJsonObject();
            assertTrue(node.has("mesh"), "cube node must carry a mesh");
            assertFalse(node.has("children"), "cube nodes must be leaves");
            assertFalse(node.has("skin"), "cube nodes must not be skinned");
            assertTrue(cubeChildren.contains(i), "cube node must be parented to a bone node");
        }

        int expectedRoots = 0;
        for (YsmBone bone : model.bones()) {
            String parent = bone.parent();
            if (parent == null || parent.isEmpty() || !indexByName.containsKey(parent)) expectedRoots++;
        }
        JsonArray roots = root.getAsJsonArray("scenes").get(0).getAsJsonObject().getAsJsonArray("nodes");
        assertEquals(expectedRoots, roots.size(), "scene roots must be root bones only");
        for (int i = 0; i < roots.size(); i++) {
            assertTrue(roots.get(i).getAsInt() < boneCount, "scene root must be a bone node");
        }
    }

    @Test
    void glbUsesRawBakedFaceVerticesAndReconstructsWorldPositions() throws Exception {
        byte[] file;
        try (InputStream in = getClass().getResourceAsStream("/ysm/Flandre_Scarlet.ysm")) {
            assertNotNull(in, "missing Flandre test asset");
            file = in.readAllBytes();
        }
        YsmModel model = YsmBinaryDecoder.decode(YsmJavaDecoder.decode(file));

        YsmFace face = model.bones().stream()
                .flatMap(bone -> bone.cubes().stream())
                .flatMap(cube -> cube.faces().values().stream())
                .findFirst().orElseThrow();
        assertEquals(12, face.positions().length, "each face must carry four raw vertices");
        assertEquals(3, face.normal().length, "each face must carry a face normal");

        byte[] glb = YsmGlbWriter.toGlb(model);
        JsonObject root = JsonParser.parseString(jsonChunk(glb)).getAsJsonObject();
        JsonArray meshes = root.getAsJsonArray("meshes");
        assertEquals(733, meshes.size());
        assertEquals(0, root.getAsJsonArray("skins").size());
        JsonArray accessors = root.getAsJsonArray("accessors");
        int totalVertices = 0;
        for (int m = 0; m < meshes.size(); m++) {
            JsonObject primitive = meshes.get(m).getAsJsonObject()
                    .getAsJsonArray("primitives").get(0).getAsJsonObject();
            totalVertices += accessors.get(primitive.getAsJsonObject("attributes")
                    .get("POSITION").getAsInt()).getAsJsonObject().get("count").getAsInt();
        }
        assertEquals(17592, totalVertices, "733 cubes x 6 faces x 4 vertices");

        byte[] bin = Arrays.copyOfRange(glb, 28 + littleEndian(glb, 12), glb.length);
        float[] world = worldBounds(root, bin, true);
        assertTrue(world[0] > -20f && world[0] < -10f, "world minX out of range: " + world[0]);
        assertTrue(world[1] > 10f && world[1] < 20f, "world maxX out of range: " + world[1]);
        assertTrue(world[2] >= -0.5f && world[2] < 1f, "world minY out of range: " + world[2]);
        assertTrue(world[3] > 35f && world[3] < 45f, "world maxY out of range: " + world[3]);
        assertTrue(world[4] > -25f && world[4] < -18f, "world minZ out of range: " + world[4]);
        assertTrue(world[5] > 15f && world[5] < 22f, "world maxZ out of range: " + world[5]);

        float[] baked = worldBounds(root, bin, false);
        assertEquals(-11.23f, baked[0], 0.2f, "cube translations + bone chain minX");
        assertEquals(11.23f, baked[1], 0.2f, "cube translations + bone chain maxX");
        assertEquals(0f, baked[2], 0.2f, "cube translations + bone chain minY");
        assertEquals(39.04f, baked[3], 0.2f, "cube translations + bone chain maxY");
        assertEquals(-21.65f, baked[4], 0.2f, "cube translations + bone chain minZ");
        assertEquals(17.17f, baked[5], 0.2f, "cube translations + bone chain maxZ");
    }

    private static float[] worldBounds(JsonObject root, byte[] bin, boolean applyRotation) {
        JsonArray nodes = root.getAsJsonArray("nodes");
        JsonArray roots = root.getAsJsonArray("scenes").get(0).getAsJsonObject().getAsJsonArray("nodes");
        int[] parentOf = new int[nodes.size()];
        Arrays.fill(parentOf, -1);
        for (int i = 0; i < nodes.size(); i++) {
            JsonArray children = nodes.get(i).getAsJsonObject().getAsJsonArray("children");
            if (children != null) {
                for (int c = 0; c < children.size(); c++) parentOf[children.get(c).getAsInt()] = i;
            }
        }
        float[][] worldR = new float[nodes.size()][9];
        float[][] worldT = new float[nodes.size()][3];
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        for (int i = 0; i < roots.size(); i++) queue.add(roots.get(i).getAsInt());
        while (!queue.isEmpty()) {
            int i = queue.remove();
            JsonObject node = nodes.get(i).getAsJsonObject();
            float[] t = node.has("translation") ? floats(node.getAsJsonArray("translation"))
                    : new float[]{0f, 0f, 0f};
            float[] q = applyRotation && node.has("rotation")
                    ? floats(node.getAsJsonArray("rotation")) : new float[]{0f, 0f, 0f, 1f};
            float[] r = rotationMatrix(q);
            int parent = parentOf[i];
            if (parent < 0) {
                worldR[i] = r;
                worldT[i] = t;
            } else {
                worldR[i] = multiply3(worldR[parent], r);
                worldT[i] = transform3(worldR[parent], t, worldT[parent]);
            }
            JsonArray children = node.getAsJsonArray("children");
            if (children != null) {
                for (int c = 0; c < children.size(); c++) queue.add(children.get(c).getAsInt());
            }
        }

        float[] lo = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        float[] hi = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        JsonArray accessors = root.getAsJsonArray("accessors");
        for (int i = 0; i < nodes.size(); i++) {
            JsonObject node = nodes.get(i).getAsJsonObject();
            if (!node.has("mesh")) continue;
            JsonObject primitive = root.getAsJsonArray("meshes").get(node.get("mesh").getAsInt())
                    .getAsJsonObject().getAsJsonArray("primitives").get(0).getAsJsonObject();
            float[] positions = accessor(root, bin, primitive.getAsJsonObject("attributes")
                    .get("POSITION").getAsInt());
            for (int v = 0; v < positions.length; v += 3) {
                for (int row = 0; row < 3; row++) {
                    float value = worldR[i][row * 3] * positions[v]
                            + worldR[i][row * 3 + 1] * positions[v + 1]
                            + worldR[i][row * 3 + 2] * positions[v + 2] + worldT[i][row];
                    lo[row] = Math.min(lo[row], value);
                    hi[row] = Math.max(hi[row], value);
                }
            }
        }
        return new float[]{lo[0], hi[0], lo[1], hi[1], lo[2], hi[2]};
    }

    private static float[] rotationMatrix(float[] q) {
        float x = q[0], y = q[1], z = q[2], w = q[3];
        return new float[]{
                1f - 2f * (y * y + z * z), 2f * (x * y - w * z), 2f * (x * z + w * y),
                2f * (x * y + w * z), 1f - 2f * (x * x + z * z), 2f * (y * z - w * x),
                2f * (x * z - w * y), 2f * (y * z + w * x), 1f - 2f * (x * x + y * y)
        };
    }

    private static float[] multiply3(float[] a, float[] b) {
        float[] out = new float[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                for (int k = 0; k < 3; k++) out[row * 3 + col] += a[row * 3 + k] * b[k * 3 + col];
            }
        }
        return out;
    }

    private static float[] transform3(float[] r, float[] t, float[] parentT) {
        float[] out = new float[3];
        for (int row = 0; row < 3; row++) {
            for (int k = 0; k < 3; k++) out[row] += r[row * 3 + k] * t[k];
            out[row] += parentT[row];
        }
        return out;
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
