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
    private final List<String> rootNodes = new ArrayList<>();
    private final List<List<String>> nodeChildren = new ArrayList<>();
    private final Map<String, Integer> boneNodes = new HashMap<>();
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
        buildBones();
        buildMeshes();
        finalizeBoneNodes();
        // Animations stay disabled: rotation keyframes need pivot-aware transforms.
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
            textures.add(new J().num("source", imageIndex).num("sampler", samplerIndex).end());
            materials.add(new J().str("name", texture.name())
                    .raw("pbrMetallicRoughness", new J()
                            .raw("baseColorTexture", new J().num("index", samplerIndex).end())
                            .num("metallicFactor", 0f).num("roughnessFactor", 1f).end())
                    .bool("doubleSided", true).str("alphaMode", "OPAQUE").end());
            materialByTexture.put(texture.name(), samplerIndex);
        }
    }

    private void buildBones() {
        List<YsmBone> bones = model.bones() == null ? List.of() : model.bones();
        for (int i = 0; i < bones.size(); i++) {
            YsmBone bone = bones.get(i);
            boneNodes.put(bone.name(), i);
            nodes.add(null);
            nodeChildren.add(new ArrayList<>());
        }
        for (int i = 0; i < bones.size(); i++) {
            YsmBone bone = bones.get(i);
            Integer parent = parentIndexOf(bone);
            if (parent == null) rootNodes.add(Integer.toString(i));
            else nodeChildren.get(parent).add(Integer.toString(i));
        }
    }

    private Integer parentIndexOf(YsmBone bone) {
        String parent = bone.parent();
        if (parent == null || parent.isEmpty()) return null;
        Integer index = boneNodes.get(parent);
        return index == null || index >= boneNodes.size() ? null : index;
    }

    private void finalizeBoneNodes() {
        List<YsmBone> bones = model.bones() == null ? List.of() : model.bones();
        for (int i = 0; i < bones.size(); i++) {
            YsmBone bone = bones.get(i);
            Vec3 pivot = bone.pivot() == null ? Vec3.ZERO : bone.pivot();
            Integer parent = parentIndexOf(bone);
            Vec3 parentPivot = parent == null ? Vec3.ZERO
                    : bones.get(parent).pivot() == null ? Vec3.ZERO : bones.get(parent).pivot();
            J node = new J().str("name", bone.name())
                    .raw("translation", vec3(pivot.x() - parentPivot.x(),
                            pivot.y() - parentPivot.y(), pivot.z() - parentPivot.z()))
                    .raw("rotation", quat(bone.rotation()));
            List<String> children = nodeChildren.get(i);
            if (!children.isEmpty()) node.raw("children", new A().addAll(children).end());
            nodes.set(i, node.end());
        }
    }

    private void buildMeshes() throws IOException {
        List<YsmBone> bones = model.bones() == null ? List.of() : model.bones();
        YsmTexture texture = textureByName.values().stream().findFirst().orElse(null);
        float texWidth = texture == null || texture.width() <= 0 ? 1f : texture.width();
        float texHeight = texture == null || texture.height() <= 0 ? 1f : texture.height();

        for (int boneIndex = 0; boneIndex < bones.size(); boneIndex++) {
            YsmBone bone = bones.get(boneIndex);
            List<YsmCube> cubes = bone.cubes() == null ? List.of() : bone.cubes();
            for (YsmCube cube : cubes) {
                List<YsmFace> faces = cube.faces() == null ? List.of() : new ArrayList<>(cube.faces().values());
                Vec3 origin = cubeOrigin(faces);
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
                    int[] uv = face.uvCoords() == null
                            ? new int[]{0, 0, texWidthInt(texWidth), texHeightInt(texHeight)} : face.uvCoords();
                    float u0 = clamp01(uv[0] / texWidth);
                    float u1 = clamp01(uv[2] / texWidth);
                    float v0 = 1f - clamp01(uv[1] / texHeight);
                    float v1 = 1f - clamp01(uv[3] / texHeight);
                    float[] faceU = {u0, u1, u1, u0};
                    float[] faceV = {v0, v0, v1, v1};
                    int base = vertex;
                    for (int v = 0; v < 4; v++) {
                        int src = v * 3;
                        int p = vertex * 3;
                        positions[p] = (rawPositions[src] - origin.x()) * SCALE;
                        positions[p + 1] = (rawPositions[src + 1] - origin.y()) * SCALE;
                        positions[p + 2] = (rawPositions[src + 2] - origin.z()) * SCALE;
                        normals[p] = faceNormal[0];
                        normals[p + 1] = faceNormal[1];
                        normals[p + 2] = faceNormal[2];
                        int t = vertex * 2;
                        uvs[t] = faceU[v];
                        uvs[t + 1] = faceV[v];
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

                Vec3 pivot = bone.pivot() == null ? Vec3.ZERO : bone.pivot();
                int cubeNode = nodes.size();
                nodes.add(new J().str("name", bone.name() + "_cube_" + meshIndex)
                        .num("mesh", meshIndex)
                        .raw("translation", vec3(origin.x() * SCALE - pivot.x(),
                                origin.y() * SCALE - pivot.y(), origin.z() * SCALE - pivot.z())).end());
                nodeChildren.add(new ArrayList<>());
                nodeChildren.get(boneIndex).add(Integer.toString(cubeNode));
            }
        }
    }

    private static Vec3 cubeOrigin(List<YsmFace> faces) {
        if (faces.isEmpty()) return Vec3.ZERO;
        float x = Float.POSITIVE_INFINITY, y = Float.POSITIVE_INFINITY, z = Float.POSITIVE_INFINITY;
        for (YsmFace face : faces) {
            float[] positions = face.positions();
            for (int v = 0; v < positions.length; v += 3) {
                x = Math.min(x, positions[v]);
                y = Math.min(y, positions[v + 1]);
                z = Math.min(z, positions[v + 2]);
            }
        }
        return new Vec3(x, y, z);
    }

    private void buildAnimations() {
        if (model.animations() == null) return;
        for (YsmAnimation animation : model.animations().values()) {
            A glChannels = new A();
            List<String> localSamplers = new ArrayList<>();
            boolean hasAny = false;
            for (YsmChannel channel : animation.channels()) {
                Integer node = boneNodes.get(channel.bone());
                if (node == null || channel.frames() == null || channel.frames().isEmpty()) continue;
                String path = channel.path();
                boolean rotation = "rotation".equals(path);
                // YSM position/scale keyframes are bone-local deltas in YSM space; emitting
                // them as absolute glTF node transforms explodes the flat root-joint rig.
                // Rotation carries the skeletal motion; skip translation/scale entirely.
                if (!rotation) continue;

                float[] times = new float[channel.frames().size()];
                float[] output = new float[channel.frames().size() * (rotation ? 4 : 3)];
                boolean step = true;
                for (int i = 0; i < channel.frames().size(); i++) {
                    YsmKeyframe frame = channel.frames().get(i);
                    times[i] = frame.time();
                    float[] value = frame.value();
                    if (rotation) {
                        float[] q = quatValues(new Vec3(value[0], value[1], value[2]));
                        System.arraycopy(q, 0, output, i * 4, 4);
                    } else {
                        output[i * 3] = value[0];
                        output[i * 3 + 1] = value[1];
                        output[i * 3 + 2] = value[2];
                    }
                    step &= "step".equals(frame.interpolation());
                }
                int input = addFloatAccessor(times, 1);
                int out = addFloatAccessor(output, rotation ? 4 : 3);
                int sampler = localSamplers.size();
                localSamplers.add(new J().num("input", input).num("output", out)
                        .str("interpolation", step ? "STEP" : "LINEAR").end());
                glChannels.add(new J().num("sampler", sampler)
                        .raw("target", new J().num("node", node).str("path", path).end()).end());
                hasAny = true;
            }
            if (hasAny) {
                animations.add(new J().str("name", animation.name())
                        .raw("samplers", new A().addAll(localSamplers).end())
                        .raw("channels", glChannels.end()).end());
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
                .raw("skins", "[]")
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

    private static String vec3(Vec3 value) {
        if (value == null) value = Vec3.ZERO;
        return vec3(value.x(), value.y(), value.z());
    }

    private static String vec3(float x, float y, float z) {
        return new A().add(Float.toString(x)).add(Float.toString(y))
                .add(Float.toString(z)).end();
    }

    private static String quat(Vec3 euler) {
        float[] q = quatValues(euler);
        return new A().add(Float.toString(q[0])).add(Float.toString(q[1]))
                .add(Float.toString(q[2])).add(Float.toString(q[3])).end();
    }

    private static float[] quatValues(Vec3 euler) {
        if (euler == null) euler = Vec3.ZERO;
        float cx = (float) Math.cos(euler.x() * 0.5), sx = (float) Math.sin(euler.x() * 0.5);
        float cy = (float) Math.cos(euler.y() * 0.5), sy = (float) Math.sin(euler.y() * 0.5);
        float cz = (float) Math.cos(euler.z() * 0.5), sz = (float) Math.sin(euler.z() * 0.5);
        return new float[]{
                sx * cy * cz - cx * sy * sz,
                cx * sy * cz + sx * cy * sz,
                cx * cy * sz - sx * sy * cz,
                cx * cy * cz + sx * sy * sz
        };
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
