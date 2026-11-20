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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class YsmGlbWriter {
    private static final int GLB_MAGIC = 0x46546C67;
    private static final int JSON_CHUNK = 0x4E4F534A;
    private static final int BIN_CHUNK = 0x004E4942;
    private static final int ARRAY_BUFFER = 34962;
    private static final int ELEMENT_ARRAY_BUFFER = 34963;
    private static final int FLOAT = 5126;
    private static final int UNSIGNED_SHORT = 5123;
    private static final int UNSIGNED_INT = 5125;
    private static final float SCALE = 16.0f;
    private static final int ROOT_NODE_INDEX = 0;
    private static final int ROOT_INDEX = 1;

    private final YsmModel model;
    private final ByteArrayOutputStream bin = new ByteArrayOutputStream();
    private final List<String> bufferViews = new ArrayList<>();
    private final List<String> accessors = new ArrayList<>();
    private final List<String> nodes = new ArrayList<>();
    private final List<String> meshes = new ArrayList<>();
    private final List<String> materials = new ArrayList<>();
    private final List<String> textures = new ArrayList<>();
    private final List<String> images = new ArrayList<>();
    private final List<String> animations = new ArrayList<>();
    private final List<String> skins = new ArrayList<>();
    private final List<String> rootNodes = new ArrayList<>();
    private final List<String> rootChildren = new ArrayList<>();
    private final Map<String, Integer> boneNodeIndices = new LinkedHashMap<>();
    private final Map<String, YsmBone> bonesByName = new LinkedHashMap<>();
    private final Map<Integer, List<String>> nodeChildren = new LinkedHashMap<>();
    private final Map<String, Integer> materialByTexture = new HashMap<>();
    private final Map<String, YsmTexture> textureByName = new LinkedHashMap<>();

    private YsmGlbWriter(YsmModel model) {
        this.model = model;
    }

    public static byte[] toGlb(YsmModel model) throws IOException {
        if (model == null) throw new IOException("YSM model is null");
        return new YsmGlbWriter(model).build();
    }

    private byte[] build() throws IOException {
        buildTextures();
        buildHierarchy();
        buildBoneMetadata();
        buildMeshes();
        finalizeRoot();
        // A minimal identity animation keeps scene.hasAnimations() true so RigWorks
        // creates an AnimationState (a pure static mesh without skeleton/instance
        // definition is rejected). It does not move any geometry.
        buildIdentityAnimation();
        return assemble();
    }

    private void buildTextures() {
        for (YsmTexture texture : model.textures().values()) {
            textureByName.put(texture.name(), texture);
            int imageIndex = images.size();
            int view = addBufferView(texture.pngBytes(), null);
            images.add(new J().str("name", texture.name())
                    .num("bufferView", view).str("mimeType", "image/png").end());
            int samplerIndex = textures.size();
            textures.add(new J().num("source", imageIndex).num("sampler", 0).end());
            materials.add(new J().str("name", texture.name())
                    .raw("pbrMetallicRoughness", new J()
                            .raw("baseColorTexture", new J().num("index", samplerIndex).end())
                            .num("metallicFactor", 0f).num("roughnessFactor", 1f).end())
                    .bool("doubleSided", true).str("alphaMode", "OPAQUE").end());
            materialByTexture.put(texture.name(), samplerIndex);
        }
    }

    private void buildHierarchy() {
        nodes.add(new J().str("name", "RootNode")
                .raw("children", new A().add(Integer.toString(ROOT_INDEX)).end()).end());
        nodes.add(null);
        rootNodes.add(Integer.toString(ROOT_NODE_INDEX));
    }

    private void buildBoneMetadata() throws IOException {
        List<YsmBone> bones = model.bones() == null ? List.of() : model.bones();
        for (YsmBone bone : bones) {
            if (bone.name() == null || bone.name().isBlank()
                    || bonesByName.putIfAbsent(bone.name(), bone) != null)
                throw new IOException("YSM bone names must be nonempty and unique: " + bone.name());
        }
        for (YsmBone bone : bones) {
            java.util.Set<String> ancestors = new java.util.HashSet<>();
            YsmBone ancestor = bone;
            while (ancestor != null) {
                if (!ancestors.add(ancestor.name())) throw new IOException("Cyclic YSM bone hierarchy: " + bone.name());
                ancestor = bonesByName.get(ancestor.parent());
            }
        }
        for (YsmBone bone : bones) {
            int boneNode = nodes.size();
            boneNodeIndices.putIfAbsent(bone.name(), boneNode);
            nodes.add(null);
        }
        for (YsmBone bone : bones) {
            int index = boneNodeIndices.get(bone.name());
            Vec3 pivot = bone.pivot() == null ? Vec3.ZERO : bone.pivot();
            YsmBone parent = bone.parent() == null ? null : bonesByName.get(bone.parent());
            Vec3 parentPivot = parent == null || parent.pivot() == null ? Vec3.ZERO : parent.pivot();
            float[] translation = {
                    pivot.x() - parentPivot.x(),
                    pivot.y() - parentPivot.y(),
                    pivot.z() - parentPivot.z()
            };
            Vec3 rotation = bone.rotation() == null ? Vec3.ZERO : bone.rotation();
            nodes.set(index, new J().str("name", bone.name())
                    .raw("translation", jsonFloats(translation))
                    .raw("rotation", jsonFloats(quaternionXYZ(rotation.x(), rotation.y(), rotation.z()))).end());
            Integer parentIndex = bone.parent() == null ? null : boneNodeIndices.get(bone.parent());
            if (parentIndex == null || parentIndex == index) rootChildren.add(Integer.toString(index));
            else nodeChildren.computeIfAbsent(parentIndex, ignored -> new ArrayList<>()).add(Integer.toString(index));
        }
    }

    private void finalizeRoot() {
        for (var entry : nodeChildren.entrySet()) {
            int index = entry.getKey();
            String node = nodes.get(index);
            if (node == null) continue;
            int close = node.lastIndexOf('}');
            nodes.set(index, node.substring(0, close) + ",\"children\":"
                    + new A().addAll(entry.getValue()).end() + "}");
        }
        J root = new J().str("name", "Root");
        if (!rootChildren.isEmpty()) root.raw("children", new A().addAll(rootChildren).end());
        nodes.set(ROOT_INDEX, root.end());
    }

    private void buildMeshes() throws IOException {
        List<YsmBone> bones = model.bones() == null ? List.of() : model.bones();
        YsmTexture texture = textureByName.values().stream().findFirst().orElse(null);
        float texWidth = texture == null || texture.width() <= 0 ? 1f : texture.width();
        float texHeight = texture == null || texture.height() <= 0 ? 1f : texture.height();

        for (YsmBone bone : bones) {
            List<YsmCube> cubes = bone.cubes() == null ? List.of() : bone.cubes();
            for (YsmCube cube : cubes) {
                List<YsmFace> faces = cube.faces() == null ? List.of() : new ArrayList<>(cube.faces().values());
                float[] cubeOrigin = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
                for (YsmFace face : faces) {
                    float[] raw = face.positions();
                    if (raw == null) continue;
                    for (int i = 0; i < raw.length; i += 3) {
                        for (int c = 0; c < 3; c++) {
                            cubeOrigin[c] = Math.min(cubeOrigin[c], raw[i + c] * SCALE);
                        }
                    }
                }
                for (int c = 0; c < 3; c++) {
                    if (!Float.isFinite(cubeOrigin[c])) cubeOrigin[c] = 0f;
                }
                int vertexCount = Math.multiplyExact(faces.size(), 4);
                float[] positions = new float[Math.multiplyExact(vertexCount, 3)];
                float[] normals = new float[positions.length];
                float[] uvs = new float[Math.multiplyExact(vertexCount, 2)];
                int[] indices = new int[Math.multiplyExact(faces.size(), 6)];

                int vertex = 0;
                int index = 0;
                for (YsmFace face : faces) {
                    float[] rawPositions = face.positions();
                    float[] faceNormal = face.normal();
                    float[] faceU = face.u();
                    float[] faceV = face.v();
                    boolean perVertexUv = faceU != null && faceV != null
                            && faceU.length >= 4 && faceV.length >= 4;
                    float[] bboxU = null;
                    float[] bboxV = null;
                    if (!perVertexUv) {
                        int[] uv = face.uvCoords() == null
                                ? new int[]{0, 0, texWidthInt(texWidth), texHeightInt(texHeight)} : face.uvCoords();
                        float u0 = clamp01(uv[0] / texWidth);
                        float u1 = clamp01(uv[2] / texWidth);
                        float v0 = 1f - clamp01(uv[1] / texHeight);
                        float v1 = 1f - clamp01(uv[3] / texHeight);
                        bboxU = new float[]{u0, u1, u1, u0};
                        bboxV = new float[]{v0, v0, v1, v1};
                    }
                    int base = vertex;
                    for (int v = 0; v < 4; v++) {
                        int src = v * 3;
                        int p = vertex * 3;
                        positions[p] = rawPositions[src] * SCALE - cubeOrigin[0];
                        positions[p + 1] = rawPositions[src + 1] * SCALE - cubeOrigin[1];
                        positions[p + 2] = rawPositions[src + 2] * SCALE - cubeOrigin[2];
                        normals[p] = faceNormal[0];
                        normals[p + 1] = faceNormal[1];
                        normals[p + 2] = faceNormal[2];
                        int t = vertex * 2;
                        if (perVertexUv) {
                            uvs[t] = faceU[v];
                            uvs[t + 1] = 1f - faceV[v];
                        } else {
                            uvs[t] = bboxU[v];
                            uvs[t + 1] = bboxV[v];
                        }
                        vertex++;
                    }
                    indices[index++] = base;
                    indices[index++] = base + 1;
                    indices[index++] = base + 2;
                    indices[index++] = base;
                    indices[index++] = base + 2;
                    indices[index++] = base + 3;
                }

                J primitive = new J()
                        .raw("attributes", new J()
                                .num("POSITION", addFloatAccessor(positions, 3))
                                .num("NORMAL", addFloatAccessor(normals, 3))
                                .num("TEXCOORD_0", addFloatAccessor(uvs, 2)).end())
                        .num("indices", addIndexAccessor(indices, vertexCount));
                if (!materials.isEmpty()) primitive.num("material", 0);
                int meshIndex = meshes.size();
                meshes.add(new J().raw("primitives", new A().add(primitive.end()).end()).end());

                int cubeNode = nodes.size();
                Vec3 pivot = bone.pivot() == null ? Vec3.ZERO : bone.pivot();
                nodes.add(new J().str("name", bone.name() + "_cube_" + meshIndex)
                        .num("mesh", meshIndex)
                        .raw("translation", jsonFloats(new float[]{
                                cubeOrigin[0] - pivot.x(),
                                cubeOrigin[1] - pivot.y(),
                                cubeOrigin[2] - pivot.z()
                        })).end());
                Integer boneNode = boneNodeIndices.get(bone.name());
                if (boneNode == null) rootChildren.add(Integer.toString(cubeNode));
                else nodeChildren.computeIfAbsent(boneNode, ignored -> new ArrayList<>()).add(Integer.toString(cubeNode));
            }
        }
    }

    private byte[] assemble() {
        byte[] binary = bin.toByteArray();
        J gltf = new J()
                .raw("asset", new J().str("version", "2.0").str("generator", "RigWorks YsmGlbWriter").end())
                .num("scene", 0)
                .raw("scenes", new A().add(new J().raw("nodes", new A().addAll(rootNodes).end()).end()).end())
                .raw("nodes", new A().addAll(nodes).end())
                .raw("meshes", new A().addAll(meshes).end())
                .raw("materials", new A().addAll(materials).end())
                .raw("textures", new A().addAll(textures).end())
                .raw("samplers", "[{\"magFilter\":9729,\"minFilter\":9987,\"wrapS\":10497,\"wrapT\":10497}]")
                .raw("images", new A().addAll(images).end())
                .raw("skins", new A().addAll(skins).end())
                .raw("animations", new A().addAll(animations).end())
                .raw("accessors", new A().addAll(accessors).end())
                .raw("bufferViews", new A().addAll(bufferViews).end())
                .raw("buffers", new A().add(new J().num("byteLength", binary.length).end()).end());

        byte[] json = pad(gltf.end().getBytes(StandardCharsets.UTF_8), (byte) ' ');
        byte[] data = pad(binary, (byte) 0);
        int total = 12 + 8 + json.length + 8 + data.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(total);
        putInt(out, GLB_MAGIC);
        putInt(out, 2);
        putInt(out, total);
        putInt(out, json.length);
        putInt(out, JSON_CHUNK);
        out.write(json, 0, json.length);
        putInt(out, data.length);
        putInt(out, BIN_CHUNK);
        out.write(data, 0, data.length);
        return out.toByteArray();
    }

    /** Minimal 1-keyframe identity rotation channel on the root node so the GLB has an
     * animation, which makes RigWorks/AssimpLoader create an AnimationState for the scene. */
    private void buildIdentityAnimation() {
        if (nodes.isEmpty()) return;
        int input = addFloatAccessor(new float[]{0f}, 1);
        int output = addFloatAccessor(new float[]{0f, 0f, 0f, 1f}, 4);
        String sampler = new J().num("input", input).num("output", output)
                .str("interpolation", "STEP").end();
        String channel = new J().num("sampler", 0)
                .raw("target", new J().num("node", 0).str("path", "rotation").end()).end();
        animations.add(new J().str("name", "bind_pose")
                .raw("samplers", new A().add(sampler).end())
                .raw("channels", new A().add(channel).end()).end());
    }

    private int addFloatAccessor(float[] values, int components) {
        padBuffer(4);
        int view = registerView(bin.size(), values.length * 4, ARRAY_BUFFER);
        for (float value : values) putInt(bin, Float.floatToRawIntBits(value));
        String type = components == 1 ? "SCALAR" : components == 2 ? "VEC2" : components == 3 ? "VEC3" : "VEC4";
        int index = accessors.size();
        accessors.add(new J().num("bufferView", view).num("componentType", FLOAT)
                .num("count", values.length / components).str("type", type).end());
        return index;
    }

    private int addIndexAccessor(int[] values, int vertexCount) {
        if (vertexCount > 65535) return addUintAccessor(values, ELEMENT_ARRAY_BUFFER);
        padBuffer(4);
        int view = registerView(bin.size(), values.length * 2, ELEMENT_ARRAY_BUFFER);
        for (int value : values) putShort(bin, value);
        int index = accessors.size();
        accessors.add(new J().num("bufferView", view).num("componentType", UNSIGNED_SHORT)
                .num("count", values.length).str("type", "SCALAR").end());
        return index;
    }

    private int addUintAccessor(int[] values, Integer target) {
        padBuffer(4);
        int view = registerView(bin.size(), values.length * 4, target);
        for (int value : values) putInt(bin, value);
        int index = accessors.size();
        accessors.add(new J().num("bufferView", view).num("componentType", UNSIGNED_INT)
                .num("count", values.length).str("type", "SCALAR").end());
        return index;
    }

    private int addBufferView(byte[] bytes, Integer target) {
        padBuffer(4);
        int view = registerView(bin.size(), bytes.length, target);
        bin.write(bytes, 0, bytes.length);
        return view;
    }

    private int registerView(int offset, int length, Integer target) {
        J view = new J().num("buffer", 0).num("byteOffset", offset).num("byteLength", length);
        if (target != null) view.num("target", target);
        int index = bufferViews.size();
        bufferViews.add(view.end());
        return index;
    }

    private void padBuffer(int alignment) {
        int remainder = bin.size() % alignment;
        if (remainder != 0) {
            for (int i = 0; i < alignment - remainder; i++) bin.write(0);
        }
    }

    private static byte[] pad(byte[] input, byte filler) {
        int remainder = input.length % 4;
        if (remainder == 0) return input;
        byte[] result = new byte[input.length + 4 - remainder];
        System.arraycopy(input, 0, result, 0, input.length);
        java.util.Arrays.fill(result, input.length, result.length, filler);
        return result;
    }

    private static int texWidthInt(float value) {
        return Math.max(1, Math.round(value));
    }

    private static int texHeightInt(float value) {
        return Math.max(1, Math.round(value));
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value)) return 0f;
        return Math.max(0f, Math.min(1f, value));
    }

    private static float[] quaternionXYZ(float x, float y, float z) {
        float hx = x * 0.5f, hy = y * 0.5f, hz = z * 0.5f;
        float sx = (float) Math.sin(hx), cx = (float) Math.cos(hx);
        float sy = (float) Math.sin(hy), cy = (float) Math.cos(hy);
        float sz = (float) Math.sin(hz), cz = (float) Math.cos(hz);
        return new float[]{
                sx * cy * cz - cx * sy * sz,
                cx * sy * cz + sx * cy * sz,
                cx * cy * sz - sx * sy * cz,
                cx * cy * cz + sx * sy * sz
        };
    }

    private static String jsonFloats(float[] values) {
        A array = new A();
        for (float value : values) {
            array.add(Float.isFinite(value) ? Float.toString(value) : "0");
        }
        return array.end();
    }

    private static void putInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void putShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static String q(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    private static final class J {
        private final StringBuilder content = new StringBuilder("{");
        private boolean first = true;

        J raw(String key, String value) {
            if (!first) content.append(',');
            first = false;
            content.append(q(key)).append(':').append(value);
            return this;
        }

        J str(String key, String value) {
            return raw(key, q(value == null ? "" : value));
        }

        J num(String key, int value) {
            return raw(key, Integer.toString(value));
        }

        J num(String key, float value) {
            return raw(key, Float.isFinite(value) ? Float.toString(value) : "0");
        }

        J bool(String key, boolean value) {
            return raw(key, Boolean.toString(value));
        }

        String end() {
            return content.append('}').toString();
        }
    }

    private static final class A {
        private final StringBuilder content = new StringBuilder("[");
        private boolean first = true;

        A add(String value) {
            if (!first) content.append(',');
            first = false;
            content.append(value);
            return this;
        }

        A addAll(List<String> values) {
            for (String value : values) add(value);
            return this;
        }

        boolean isEmpty() {
            return first;
        }

        String end() {
            return content.append(']').toString();
        }
    }
}
