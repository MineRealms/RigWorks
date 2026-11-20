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
import cn.minerealms.assimploader.animation.PoseSnapshot;
import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.core.ModelImportMode;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
    void glbParentsCubeLocalVerticesUnderTheirBoneHierarchy() throws Exception {
        YsmModel model = YsmBinaryDecoder.decode(payload());
        byte[] glb = YsmGlbWriter.toGlb(model);
        JsonObject root = JsonParser.parseString(jsonChunk(glb)).getAsJsonObject();

        int boneCount = model.bones().size();
        int cubeCount = model.bones().stream().mapToInt(bone -> bone.cubes().size()).sum();
        JsonArray meshes = root.getAsJsonArray("meshes");
        assertEquals(cubeCount, meshes.size(), "one glTF mesh per cube");
        JsonArray accessors = root.getAsJsonArray("accessors");
        assertEquals(0, root.getAsJsonArray("skins").size(), "no skins are emitted");
        assertEquals(1, root.getAsJsonArray("animations").size(), "one identity bind-pose animation");

        int expectedVertices = model.bones().stream()
                .flatMap(bone -> bone.cubes().stream())
                .mapToInt(cube -> cube.faces().size() * 4).sum();
        int totalVertices = 0;
        for (int m = 0; m < meshes.size(); m++) {
            JsonObject mesh = meshes.get(m).getAsJsonObject();
            JsonArray primitives = mesh.getAsJsonArray("primitives");
            assertEquals(1, primitives.size(), "one primitive per cube mesh");
            JsonObject attributes = primitives.get(0).getAsJsonObject().getAsJsonObject("attributes");
            assertEquals(3, attributes.size(), "cubes carry POSITION/NORMAL/TEXCOORD_0 only");
            assertFalse(attributes.has("JOINTS_0"), "JOINTS_0 must be absent");
            assertFalse(attributes.has("WEIGHTS_0"), "WEIGHTS_0 must be absent");
            int positionCount = accessors.get(attributes.get("POSITION").getAsInt()).getAsJsonObject()
                    .get("count").getAsInt();
            totalVertices += positionCount;
        }
        assertEquals(expectedVertices, totalVertices);

        JsonArray nodes = root.getAsJsonArray("nodes");
        assertEquals(2 + cubeCount + boneCount, nodes.size());

        JsonArray roots = root.getAsJsonArray("scenes").get(0).getAsJsonObject().getAsJsonArray("nodes");
        assertEquals(1, roots.size(), "single scene root");
        assertEquals(0, roots.get(0).getAsInt(), "scene root is RootNode");

        JsonObject rootNode = nodes.get(0).getAsJsonObject();
        assertEquals("RootNode", rootNode.get("name").getAsString());
        assertIdentityTransform(rootNode);
        JsonArray rootNodeChildren = rootNode.getAsJsonArray("children");
        assertNotNull(rootNodeChildren);
        assertEquals(1, rootNodeChildren.size());
        assertEquals(1, rootNodeChildren.get(0).getAsInt(), "RootNode's only child is Root");

        JsonObject rootChild = nodes.get(1).getAsJsonObject();
        assertEquals("Root", rootChild.get("name").getAsString());
        assertIdentityTransform(rootChild);
        Set<Integer> rootChildren = new HashSet<>();
        JsonArray rootChildIndices = rootChild.getAsJsonArray("children");
        assertNotNull(rootChildIndices, "Root must parent the top-level bone nodes");
        for (int c = 0; c < rootChildIndices.size(); c++) {
            rootChildren.add(rootChildIndices.get(c).getAsInt());
        }
        Map<Integer, Integer> parents = parents(nodes);
        Map<String, Integer> byName = new HashMap<>();
        for (int i = 2; i < nodes.size(); i++) byName.put(nodes.get(i).getAsJsonObject().get("name").getAsString(), i);
        for (YsmBone bone : model.bones()) {
            int index = byName.get(bone.name());
            int expectedParent = byName.getOrDefault(bone.parent(), 1);
            assertEquals(expectedParent, parents.get(index), "bone parent must survive conversion");
        }

        int meshNodes = 0;
        int boneNodes = 0;
        for (int i = 2; i < nodes.size(); i++) {
            JsonObject node = nodes.get(i).getAsJsonObject();
            assertTrue(parents.containsKey(i), "every node must be attached exactly once");
            if (node.has("mesh")) {
                meshNodes++;
                assertFalse(node.has("skin"), "cube mesh nodes must not be skinned");
                assertFalse(node.has("children"), "mesh nodes must be leaves");
                assertFalse(node.has("matrix"), "mesh nodes must not use matrices");
                assertFalse(node.has("rotation"), "mesh node rotation stays identity");
                assertFalse(node.has("scale"), "mesh node scale stays identity");
                assertTrue(node.has("translation"), "cube node carries its origin relative to the bone pivot");
                assertEquals(3, node.getAsJsonArray("translation").size());
                String boneName = node.get("name").getAsString().replaceFirst("_cube_[0-9]+$", "");
                assertEquals(byName.get(boneName), parents.get(i));
            } else {
                boneNodes++;
                assertFalse(node.has("skin"), "bone metadata nodes are not skinned");
                assertTrue(node.has("translation"), "bone node carries its pivot translation");
                assertTrue(node.has("name"), "bone metadata nodes carry a name");
            }
        }
        assertEquals(cubeCount, meshNodes);
        assertEquals(boneCount, boneNodes);
    }

    @Test
    void glbWorldVerticesMatchYsmPivotRotationAlgorithm() throws Exception {
        byte[] file;
        try (InputStream in = getClass().getResourceAsStream("/ysm/Flandre_Scarlet.ysm")) {
            assertNotNull(in, "missing Flandre test asset");
            file = in.readAllBytes();
        }
        YsmModel model = YsmBinaryDecoder.decode(YsmJavaDecoder.decode(file));

        YsmCube firstCube = model.bones().stream()
                .flatMap(bone -> bone.cubes().stream())
                .findFirst().orElseThrow();
        YsmFace face = firstCube.faces().values().stream().findFirst().orElseThrow();
        assertEquals(12, face.positions().length, "each face must carry four raw vertices");
        assertEquals(3, face.normal().length, "each face must carry a face normal");
        assertEquals(4, face.u().length, "each face must carry four per-vertex u values");
        assertEquals(4, face.v().length, "each face must carry four per-vertex v values");

        byte[] glb = YsmGlbWriter.toGlb(model);
        JsonObject root = JsonParser.parseString(jsonChunk(glb)).getAsJsonObject();
        JsonArray meshes = root.getAsJsonArray("meshes");
        assertEquals(733, meshes.size());
        assertEquals(0, root.getAsJsonArray("skins").size(), "no skins are emitted");
        assertEquals(1, root.getAsJsonArray("animations").size());
        JsonArray accessors = root.getAsJsonArray("accessors");
        int totalVertices = 0;
        for (int m = 0; m < meshes.size(); m++) {
            JsonObject attributes = meshes.get(m).getAsJsonObject()
                    .getAsJsonArray("primitives").get(0).getAsJsonObject().getAsJsonObject("attributes");
            assertFalse(attributes.has("JOINTS_0"));
            assertFalse(attributes.has("WEIGHTS_0"));
            assertEquals(3, attributes.size());
            totalVertices += accessors.get(attributes.get("POSITION").getAsInt()).getAsJsonObject()
                    .get("count").getAsInt();
        }
        assertEquals(17592, totalVertices, "733 cubes x 6 faces x 4 vertices");

        byte[] bin = Arrays.copyOfRange(glb, 28 + littleEndian(glb, 12), glb.length);
        JsonObject firstPrimitive = meshes.get(0).getAsJsonObject()
                .getAsJsonArray("primitives").get(0).getAsJsonObject();
        float[] firstPositions = accessor(root, bin, firstPrimitive.getAsJsonObject("attributes")
                .get("POSITION").getAsInt());
        JsonObject firstMeshNode = null;
        for (JsonElement element : root.getAsJsonArray("nodes")) {
            JsonObject node = element.getAsJsonObject();
            if (node.has("mesh") && node.get("mesh").getAsInt() == 0) {
                firstMeshNode = node;
                break;
            }
        }
        assertNotNull(firstMeshNode, "mesh 0 must have a cube node");
        assertFalse(firstMeshNode.has("skin"), "cube nodes must not be skinned");
        YsmBone firstBone = model.bones().stream().filter(bone -> !bone.cubes().isEmpty()).findFirst().orElseThrow();
        float[] firstTranslation = floats(firstMeshNode.getAsJsonArray("translation"));
        float[] pivot = {firstBone.pivot().x(), firstBone.pivot().y(), firstBone.pivot().z()};
        for (int v = 0; v < face.positions().length; v += 3) {
            for (int c = 0; c < 3; c++) {
                float worldVertex = face.positions()[v + c] * 16f;
                assertEquals(worldVertex - pivot[c], firstPositions[v + c] + firstTranslation[c], 1e-4f,
                        "mesh-local vertex and node origin must preserve the bone-local raw point");
            }
        }

        float[] world = positionBounds(root, bin, true);
        assertArrayEquals(referenceBounds(model), world, 1e-3f, "default-pose bounds must match YSM's matrix algorithm");
        assertTrue(world[3] - world[2] > 10, "fixture must retain its full height");

        double maxTranslationMagnitude = 0d;
        double minTranslationMagnitude = Double.MAX_VALUE;
        Set<String> translations = new HashSet<>();
        for (JsonElement element : root.getAsJsonArray("nodes")) {
            JsonObject node = element.getAsJsonObject();
            if (!node.has("mesh")) continue;
            float[] translation = floats(node.getAsJsonArray("translation"));
            double magnitude = Math.sqrt(translation[0] * translation[0]
                    + translation[1] * translation[1] + translation[2] * translation[2]);
            maxTranslationMagnitude = Math.max(maxTranslationMagnitude, magnitude);
            minTranslationMagnitude = Math.min(minTranslationMagnitude, magnitude);
            translations.add(java.util.Arrays.toString(translation));
        }
        int distinctTranslations = translations.size();
        assertTrue(maxTranslationMagnitude > 0f,
                "cube node translations must carry world positions, not all zero");
        assertTrue(distinctTranslations > 1, "cube node translations must be varied");
    }

    private static void assertIdentityTransform(JsonObject node) {
        assertFalse(node.has("matrix"), "nodes must not use matrices");
        if (node.has("translation")) {
            assertArrayEquals(new float[]{0f, 0f, 0f}, floats(node.getAsJsonArray("translation")), 1e-6f);
        }
        if (node.has("rotation")) {
            assertArrayEquals(new float[]{0f, 0f, 0f, 1f}, floats(node.getAsJsonArray("rotation")), 1e-6f);
        }
        if (node.has("scale")) {
            assertArrayEquals(new float[]{1f, 1f, 1f}, floats(node.getAsJsonArray("scale")), 1e-6f);
        }
    }

    @Test
    void mergedRenderAssetPreservesRigidSkinningAndSubmitsFewMeshes() throws Exception {
        byte[] source;
        try (InputStream input = getClass().getResourceAsStream("/ysm/Flandre_Scarlet.ysm")) {
            assertNotNull(input);
            source = input.readAllBytes();
        }
        YsmModel model = YsmBinaryDecoder.decode(YsmJavaDecoder.decode(source));
        byte[] glb = YsmGlbWriter.toRuntimeGlb(model);
        JsonObject root = JsonParser.parseString(jsonChunk(glb)).getAsJsonObject();
        int jsonLength = littleEndian(glb, 12);
        byte[] bin = Arrays.copyOfRange(glb, 28 + jsonLength, glb.length);
        int visibleBones = (int) model.bones().stream().filter(b -> !b.cubes().isEmpty()).count();
        int batchCount = (visibleBones + 255) / 256;
        assertEquals(batchCount, root.getAsJsonArray("meshes").size());
        assertTrue(batchCount < 5, "the real fixture should use a few batches, not one mesh per cube");
        Map<Integer, Matrix4f> world = worldTransforms(root.getAsJsonArray("nodes"));
        int vertices = 0;
        for (int batch = 0; batch < batchCount; batch++) {
            JsonObject primitive = root.getAsJsonArray("meshes").get(batch).getAsJsonObject()
                    .getAsJsonArray("primitives").get(0).getAsJsonObject();
            JsonObject attributes = primitive.getAsJsonObject("attributes");
            float[] positions = accessor(root, bin, attributes.get("POSITION").getAsInt());
            float[] weights = accessor(root, bin, attributes.get("WEIGHTS_0").getAsInt());
            JsonObject skin = root.getAsJsonArray("skins").get(batch).getAsJsonObject();
            JsonArray joints = skin.getAsJsonArray("joints");
            float[] inverse = accessor(root, bin, skin.get("inverseBindMatrices").getAsInt());
            assertTrue(joints.size() <= 256);
            for (int i = 0; i < joints.size(); i++) {
                Matrix4f bind = world.get(joints.get(i).getAsInt());
                Matrix4f palette = new Matrix4f(bind).mul(new Matrix4f().set(inverse, i * 16));
                assertArrayEquals(new Matrix4f().get(new float[16]), palette.get(new float[16]), 2e-4f,
                        "bind pose palettes must be identity without losing world-space geometry");
            }
            for (int i = 0; i < weights.length; i += 4)
                assertArrayEquals(new float[]{1, 0, 0, 0}, Arrays.copyOfRange(weights, i, i + 4));
            for (float value : positions) assertTrue(Float.isFinite(value));
            vertices += positions.length / 3;
        }
        assertEquals(model.bones().stream().flatMap(b -> b.cubes().stream())
                .mapToInt(c -> c.faces().size() * 4).sum(), vertices);
        assertArrayEquals(referenceBounds(model), positionBounds(root, bin, false), 1e-3f);

        var scene = new AssimpModelLoader().loadModelFromMemory("runtime.glb", glb, ModelImportMode.DYNAMIC);
        assertNotNull(scene);
        assertEquals(batchCount, scene.getMeshes().size());
        var snapshot = PoseSnapshot.capture(1, scene, Map.of());
        assertEquals(batchCount, snapshot.copyMeshInstances().size());
        for (var mesh : scene.getMeshes()) {
            assertTrue(mesh.hasBones());
            assertTrue(snapshot.getBoneCount(mesh) <= 256);
            for (Matrix4f palette : snapshot.getBoneTransforms(mesh))
                assertArrayEquals(new Matrix4f().get(new float[16]), palette.get(new float[16]), 2e-3f);
        }
        assertTrue(snapshot.getBounds().maxY - snapshot.getBounds().minY > 10);
    }

    private static float[] positionBounds(JsonObject root, byte[] bin, boolean world) {
        JsonArray meshes = root.getAsJsonArray("meshes");
        Map<Integer, Matrix4f> transforms = worldTransforms(root.getAsJsonArray("nodes"));
        Map<Integer, Matrix4f> nodeByMesh = new HashMap<>();
        int nodeIndex = 0;
        for (JsonElement element : root.getAsJsonArray("nodes")) {
            JsonObject node = element.getAsJsonObject();
            if (node.has("mesh")) nodeByMesh.put(node.get("mesh").getAsInt(), transforms.get(nodeIndex));
            nodeIndex++;
        }
        float[] lo = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        float[] hi = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (int m = 0; m < meshes.size(); m++) {
            JsonObject primitive = meshes.get(m).getAsJsonObject()
                    .getAsJsonArray("primitives").get(0).getAsJsonObject();
            float[] positions = accessor(root, bin, primitive.getAsJsonObject("attributes")
                    .get("POSITION").getAsInt());
            Matrix4f transform = world ? nodeByMesh.get(m) : new Matrix4f();
            assertNotNull(transform, "mesh " + m + " must have a node");
            for (int v = 0; v < positions.length; v += 3) {
                Vector3f point = transform.transformPosition(new Vector3f(positions[v], positions[v + 1], positions[v + 2]));
                for (int c = 0; c < 3; c++) {
                    float value = point.get(c);
                    lo[c] = Math.min(lo[c], value);
                    hi[c] = Math.max(hi[c], value);
                }
            }
        }
        return new float[]{lo[0], hi[0], lo[1], hi[1], lo[2], hi[2]};
    }

    private static Map<Integer, Integer> parents(JsonArray nodes) {
        Map<Integer, Integer> parents = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            JsonArray children = nodes.get(i).getAsJsonObject().getAsJsonArray("children");
            if (children != null) for (JsonElement child : children)
                assertNull(parents.put(child.getAsInt(), i), "a node cannot have multiple parents");
        }
        return parents;
    }

    private static Map<Integer, Matrix4f> worldTransforms(JsonArray nodes) {
        Map<Integer, Integer> parents = parents(nodes);
        Map<Integer, Matrix4f> transforms = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) worldTransform(nodes, parents, transforms, i);
        return transforms;
    }

    private static Matrix4f worldTransform(JsonArray nodes, Map<Integer, Integer> parents,
                                            Map<Integer, Matrix4f> transforms, int index) {
        if (transforms.containsKey(index)) return transforms.get(index);
        JsonObject node = nodes.get(index).getAsJsonObject();
        Matrix4f local = new Matrix4f();
        if (node.has("translation")) {
            float[] t = floats(node.getAsJsonArray("translation"));
            local.translate(t[0], t[1], t[2]);
        }
        if (node.has("rotation")) {
            float[] q = floats(node.getAsJsonArray("rotation"));
            local.rotate(new Quaternionf(q[0], q[1], q[2], q[3]));
        }
        Matrix4f result = parents.containsKey(index)
                ? new Matrix4f(worldTransform(nodes, parents, transforms, parents.get(index))).mul(local) : local;
        transforms.put(index, result);
        return result;
    }

    private static float[] referenceBounds(YsmModel model) {
        Map<String, YsmBone> bones = new HashMap<>();
        for (YsmBone bone : model.bones()) bones.put(bone.name(), bone);
        float[] bounds = {Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (YsmBone bone : model.bones()) {
            Matrix4f transform = referenceTransform(bones, bone);
            for (YsmCube cube : bone.cubes()) for (YsmFace face : cube.faces().values()) {
                for (int i = 0; i < face.positions().length; i += 3) {
                    Vector3f p = transform.transformPosition(new Vector3f(face.positions()[i] * 16,
                            face.positions()[i + 1] * 16, face.positions()[i + 2] * 16));
                    for (int c = 0; c < 3; c++) {
                        bounds[c * 2] = Math.min(bounds[c * 2], p.get(c));
                        bounds[c * 2 + 1] = Math.max(bounds[c * 2 + 1], p.get(c));
                    }
                }
            }
        }
        return bounds;
    }

    private static Matrix4f referenceTransform(Map<String, YsmBone> bones, YsmBone bone) {
        YsmBone parent = bones.get(bone.parent());
        Matrix4f result = parent == null ? new Matrix4f() : referenceTransform(bones, parent);
        Vec3 p = bone.pivot(), r = bone.rotation();
        return result.translate(p.x(), p.y(), p.z()).rotateZ(r.z()).rotateY(r.y()).rotateX(r.x())
                .translate(-p.x(), -p.y(), -p.z());
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
