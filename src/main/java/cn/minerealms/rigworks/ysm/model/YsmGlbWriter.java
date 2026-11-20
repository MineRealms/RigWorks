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

    private static final float[][] FACE_CORNERS = {
            {1, 0, 0, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1},
            {-1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 0, 0},
            {0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0, 0, 1, 0},
            {0, -1, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1},
            {0, 0, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1},
            {0, 0, -1, 1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0}
    };

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
    private final Map<String, Integer> boneNodes = new HashMap<>();
    private final Map<String, float[]> bindGlobals = new HashMap<>();
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
        buildSkin();
        buildAnimations();
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
                    .bool("doubleSided", true).end());
            materialByTexture.put(texture.name(), samplerIndex);
        }
    }

    private void buildBones() {
        List<YsmBone> bones = model.bones() == null ? List.of() : model.bones();
        for (int i = 0; i < bones.size(); i++) {
            boneNodes.put(bones.get(i).name(), i);
        }
        for (int i = 0; i < bones.size(); i++) {
            YsmBone bone = bones.get(i);
            A children = new A();
            for (int j = 0; j < bones.size(); j++) {
                Integer parent = boneNodes.get(bones.get(j).parent());
                if (parent != null && parent == i && j != i) children.add(Integer.toString(j));
            }
            // glTF node translations are relative to the parent. Bedrock pivots are
            // absolute model-space coordinates, so subtract the parent pivot.
            Integer parentIndex = boneNodes.get(bone.parent());
            Vec3 pivot = bone.pivot();
            float tx = pivot.x(), ty = pivot.y(), tz = pivot.z();
            if (parentIndex != null && parentIndex != i) {
                Vec3 pp = bones.get(parentIndex).pivot();
                tx -= pp.x(); ty -= pp.y(); tz -= pp.z();
            }
            J node = new J().str("name", bone.name())
                    .raw("translation", vec3(tx, ty, tz))
                    .raw("rotation", quat(bone.rotation()));
            if (!children.isEmpty()) node.raw("children", children.end());
            nodes.add(node.end());
        }
        for (int i = 0; i < bones.size(); i++) {
            Integer parent = boneNodes.get(bones.get(i).parent());
            if (parent == null || parent == i) rootNodes.add(Integer.toString(i));
        }
        for (int i = 0; i < bones.size(); i++) {
            int index = i;
            bindGlobals.computeIfAbsent(bones.get(index).name(), name -> bindFor(bones, index, new boolean[bones.size()]));
        }
    }

    private float[] bindFor(List<YsmBone> bones, int index, boolean[] visiting) {
        if (visiting[index]) return identity();
        visiting[index] = true;
        YsmBone bone = bones.get(index);
        // Bedrock cube coordinates are already in model space. A bone's bind transform
        // is rotate-around-pivot: T(pivot) * R * T(-pivot). It must NOT be accumulated
        // down the hierarchy with parent translations, otherwise each level re-adds
        // the pivot offset and the model explodes to hundreds of units tall.
        float[] r = rotationMatrix(quatValues(bone.rotation()));
        float px = bone.pivot().x(), py = bone.pivot().y(), pz = bone.pivot().z();
        float[] result = pivotRotation(r, px, py, pz);
        visiting[index] = false;
        return result;
    }

    private static float[] rotationMatrix(float[] q) {
        float x = q[0], y = q[1], z = q[2], w = q[3];
        float xx = x * x, yy = y * y, zz = z * z;
        float xy = x * y, xz = x * z, yz = y * z;
        float wx = w * x, wy = w * y, wz = w * z;
        float[] m = new float[16];
        m[0] = 1f - 2f * (yy + zz); m[1] = 2f * (xy + wz);        m[2] = 2f * (xz - wy);
        m[4] = 2f * (xy - wz);      m[5] = 1f - 2f * (xx + zz);    m[6] = 2f * (yz + wx);
        m[8] = 2f * (xz + wy);      m[9] = 2f * (yz - wx);        m[10] = 1f - 2f * (xx + yy);
        m[15] = 1f;
        return m;
    }

    private static float[] pivotRotation(float[] r, float px, float py, float pz) {
        float[] out = r.clone();
        out[12] = px - (r[0] * px + r[4] * py + r[8] * pz);
        out[13] = py - (r[1] * px + r[5] * py + r[9] * pz);
        out[14] = pz - (r[2] * px + r[6] * py + r[10] * pz);
        return out;
    }

    private void buildMeshes() {
        List<YsmBone> bones = model.bones() == null ? List.of() : model.bones();
        for (int boneIndex = 0; boneIndex < bones.size(); boneIndex++) {
            YsmBone bone = bones.get(boneIndex);
            float[] bind = bindGlobals.getOrDefault(bone.name(), identity());
            List<YsmCube> cubes = bone.cubes() == null ? List.of() : bone.cubes();
            for (YsmCube cube : cubes) {
                int meshIndex = meshes.size();
                meshes.add(buildMesh(cube, boneIndex, bind));
                int nodeIndex = nodes.size();
                J meshNode = new J().str("name", bone.name() + "_mesh_" + meshIndex)
                        .num("mesh", meshIndex).num("skin", 0);
                nodes.add(meshNode.end());
                rootNodes.add(Integer.toString(nodeIndex));
            }
        }
    }

    private String buildMesh(YsmCube cube, int boneIndex, float[] bind) {
        List<YsmFace> faces = new ArrayList<>(cube.faces() == null ? List.of() : cube.faces().values());
        float[] positions = new float[faces.size() * 4 * 3];
        float[] normals = new float[faces.size() * 4 * 3];
        float[] uvs = new float[faces.size() * 4 * 2];
        float[] joints = new float[faces.size() * 4 * 4];
        float[] weights = new float[faces.size() * 4 * 4];
        int[] indices = new int[faces.size() * 6];

        Vec3 origin = cube.origin() == null ? Vec3.ZERO : cube.origin();
        Vec3 size = cube.size() == null ? Vec3.ZERO : cube.size();
        String materialName = faces.isEmpty() ? null : faces.get(0).texture();
        Integer material = materialName == null ? null : materialByTexture.get(materialName);
        if (material == null && !materialByTexture.isEmpty()) material = materialByTexture.values().iterator().next();
        YsmTexture texture = materialName == null ? null : textureByName.get(materialName);
        if (texture == null && !textureByName.isEmpty()) texture = textureByName.values().iterator().next();
        float texWidth = texture == null || texture.width() <= 0 ? 1f : texture.width();
        float texHeight = texture == null || texture.height() <= 0 ? 1f : texture.height();

        for (int f = 0; f < faces.size(); f++) {
            float[] corner = FACE_CORNERS[f % FACE_CORNERS.length];
            float normalX = corner[0], normalY = corner[1], normalZ = corner[2];
            YsmFace face = faces.get(f);
            int[] uv = face.uvCoords() == null ? new int[]{0, 0, texWidthInt(texWidth), texHeightInt(texHeight)} : face.uvCoords();
            float u0 = clamp01(uv[0] / texWidth);
            float u1 = clamp01(uv[2] / texWidth);
            float v0 = 1f - clamp01(uv[1] / texHeight);
            float v1 = 1f - clamp01(uv[3] / texHeight);
            float[] faceU = {u0, u1, u1, u0};
            float[] faceV = {v0, v0, v1, v1};

            for (int v = 0; v < 4; v++) {
                float px = origin.x() + corner[3 + v * 3] * size.x();
                float py = origin.y() + corner[4 + v * 3] * size.y();
                float pz = origin.z() + corner[5 + v * 3] * size.z();
                float[] world = transform(bind, px, py, pz);
                int p = (f * 4 + v) * 3;
                positions[p] = world[0];
                positions[p + 1] = world[1];
                positions[p + 2] = world[2];
                normals[p] = normalX;
                normals[p + 1] = normalY;
                normals[p + 2] = normalZ;
                int t = (f * 4 + v) * 2;
                uvs[t] = faceU[v];
                uvs[t + 1] = faceV[v];
                int j = (f * 4 + v) * 4;
                joints[j] = boneIndex;
                weights[j] = 1f;
            }
            int base = f * 4;
            int i = f * 6;
            indices[i] = base;
            indices[i + 1] = base + 1;
            indices[i + 2] = base + 2;
            indices[i + 3] = base;
            indices[i + 4] = base + 2;
            indices[i + 5] = base + 3;
        }

        J primitive = new J()
                .raw("attributes", new J()
                        .num("POSITION", addFloatAccessor(positions, 3))
                        .num("NORMAL", addFloatAccessor(normals, 3))
                        .num("TEXCOORD_0", addFloatAccessor(uvs, 2))
                        .num("JOINTS_0", addUshortAccessor(joints))
                        .num("WEIGHTS_0", addFloatAccessor(weights, 4)).end())
                .num("indices", addIndexAccessor(indices));
        if (material != null) primitive.num("material", material);
        return new J().raw("primitives", new A().add(primitive.end()).end()).end();
    }

    private void buildSkin() {
        List<YsmBone> bones = model.bones() == null ? List.of() : model.bones();
        if (bones.isEmpty()) return;
        A jointNodes = new A();
        for (int i = 0; i < bones.size(); i++) jointNodes.add(Integer.toString(i));
        float[] inverseBind = new float[bones.size() * 16];
        for (int i = 0; i < bones.size(); i++) {
            float[] bind = bindGlobals.getOrDefault(bones.get(i).name(), identity());
            float[] inverse = invertTranslationRotation(bind);
            System.arraycopy(inverse, 0, inverseBind, i * 16, 16);
        }
        int ibm = addMat4Accessor(inverseBind);
        String skin = new J().num("skeleton", 0)
                .raw("joints", jointNodes.end()).num("inverseBindMatrices", ibm).end();
        int index = skinsSize();
        skins.add(index, skin);
    }

    private final List<String> skins = new ArrayList<>();

    private int skinsSize() {
        return skins.size();
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
                if (!rotation && !"position".equals(path) && !"scale".equals(path)) continue;

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

    private int addUshortAccessor(float[] values) {
        padBuffer(4);
        int view = registerView(bin.size(), values.length * 2, ARRAY_BUFFER);
        for (float value : values) putShort(bin, (int) value);
        int index = accessors.size();
        accessors.add(new J().num("bufferView", view).num("componentType", UNSIGNED_SHORT)
                .num("count", values.length).str("type", "SCALAR").end());
        return index;
    }

    private int addIndexAccessor(int[] values) {
        padBuffer(4);
        int view = registerView(bin.size(), values.length * 2, ELEMENT_ARRAY_BUFFER);
        for (int value : values) putShort(bin, value);
        int index = accessors.size();
        accessors.add(new J().num("bufferView", view).num("componentType", UNSIGNED_SHORT)
                .num("count", values.length).str("type", "SCALAR").end());
        return index;
    }

    private int addMat4Accessor(float[] values) {
        padBuffer(4);
        int view = registerView(bin.size(), values.length * 4, ARRAY_BUFFER);
        for (float value : values) putInt(bin, Float.floatToRawIntBits(value));
        int index = accessors.size();
        accessors.add(new J().num("bufferView", view).num("componentType", FLOAT)
                .num("count", values.length / 16).str("type", "MAT4").end());
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

    private static float[] matrixTR(Vec3 translation, float[] q) {
        float x = q[0], y = q[1], z = q[2], w = q[3];
        float xx = x * x, yy = y * y, zz = z * z;
        float xy = x * y, xz = x * z, yz = y * z;
        float wx = w * x, wy = w * y, wz = w * z;
        float[] m = new float[16];
        m[0] = 1f - 2f * (yy + zz);
        m[1] = 2f * (xy + wz);
        m[2] = 2f * (xz - wy);
        m[4] = 2f * (xy - wz);
        m[5] = 1f - 2f * (xx + zz);
        m[6] = 2f * (yz + wx);
        m[8] = 2f * (xz + wy);
        m[9] = 2f * (yz - wx);
        m[10] = 1f - 2f * (xx + yy);
        m[12] = translation.x();
        m[13] = translation.y();
        m[14] = translation.z();
        m[15] = 1f;
        return m;
    }

    private static float[] multiply(float[] a, float[] b) {
        float[] out = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                out[c * 4 + r] = a[r] * b[c * 4]
                        + a[4 + r] * b[c * 4 + 1]
                        + a[8 + r] * b[c * 4 + 2]
                        + a[12 + r] * b[c * 4 + 3];
            }
        }
        return out;
    }

    private static float[] invertTranslationRotation(float[] m) {
        float[] out = new float[16];
        for (int c = 0; c < 3; c++) {
            for (int r = 0; r < 3; r++) out[c * 4 + r] = m[r * 4 + c];
        }
        float tx = m[12], ty = m[13], tz = m[14];
        out[12] = -(m[0] * tx + m[4] * ty + m[8] * tz);
        out[13] = -(m[1] * tx + m[5] * ty + m[9] * tz);
        out[14] = -(m[2] * tx + m[6] * ty + m[10] * tz);
        out[15] = 1f;
        return out;
    }

    private static float[] transform(float[] m, float x, float y, float z) {
        return new float[]{
                m[0] * x + m[4] * y + m[8] * z + m[12],
                m[1] * x + m[5] * y + m[9] * z + m[13],
                m[2] * x + m[6] * y + m[10] * z + m[14]
        };
    }

    private static float[] identity() {
        float[] m = new float[16];
        m[0] = m[5] = m[10] = m[15] = 1f;
        return m;
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
