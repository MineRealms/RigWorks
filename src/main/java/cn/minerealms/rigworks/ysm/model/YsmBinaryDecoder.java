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

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class YsmBinaryDecoder {
    private static final String[] PATHS = {"rotation", "position", "scale"};

    private YsmBinaryDecoder() {
    }

    public static YsmModel decode(byte[] payload) throws IOException {
        if (payload == null || payload.length < 4) {
            throw new IOException("YSM payload too short");
        }
        YsmByteReader reader = new YsmByteReader(payload);
        int format = (int) reader.readDword();
        if (format > 15) {
            throw new IOException("Unsupported YSM format " + format + ": only legacy V1.5 (format <= 15) is implemented");
        }
        if (format < 4) {
            throw new IOException("Unsupported YSM format " + format + ": only legacy V1.5 (format >= 4) is implemented");
        }
        return decodeLegacyV15(reader, format);
    }

    private static YsmModel decodeLegacyV15(YsmByteReader reader, int format) throws IOException {
        reader.skip(reader.readVarInt());

        List<RawGeometry> geometries = new ArrayList<>();
        int modelCount = reader.readVarInt();
        for (int i = 0; i < modelCount; i++) {
            int modelType = reader.readVarInt();
            int padding = reader.readVarInt();
            if (padding != 1) throw new IOException("Expected 1 after model id, got " + padding);
            RawGeometry geometry = parseGeometry(reader);
            geometry.modelType = modelType;
            geometries.add(geometry);
        }

        RawGeometry main = selectMain(geometries);
        String modelId = main == null ? "" : main.identifier;
        List<YsmBone> bones = main == null ? List.of() : main.bones;

        Map<String, YsmAnimation> animations = new LinkedHashMap<>();
        int animationBlobCount = reader.readVarInt();
        for (int i = 0; i < animationBlobCount; i++) {
            reader.readVarInt();
            if (reader.readVarInt() != 1) throw new IOException("Expected 1 after animation id");
            for (YsmAnimation animation : parseAnimations(reader, format).values()) {
                animations.putIfAbsent(animation.name(), animation);
            }
        }

        List<YsmController> controllers = new ArrayList<>();
        if (format > 9) {
            parseControllers(reader, format, controllers);
            int tableSize = reader.readVarInt();
            for (int i = 0; i < tableSize; i++) {
                reader.readString();
                reader.readString();
            }
        }

        Map<String, YsmTexture> textures = new LinkedHashMap<>();
        int textureCount = reader.readVarInt();
        for (int i = 0; i < textureCount; i++) {
            String name = reader.readString();
            byte[] data = reader.readByteArray();
            int width = reader.readVarInt();
            int height = reader.readVarInt();
            int subCount = reader.readVarInt();
            for (int j = 0; j < subCount; j++) {
                reader.readVarInt();
                reader.readByteArray();
                reader.readVarInt();
                reader.readVarInt();
            }
            if (!textures.containsKey(name)) {
                textures.put(name, new YsmTexture(name, width, height, YsmPngEncoder.toPng(data, width, height)));
            }
        }

        if (format > 9) {
            parseSoundFiles(reader, format);
            int tableSize = reader.readVarInt();
            for (int i = 0; i < tableSize; i++) {
                reader.readString();
                reader.readString();
            }
        }

        int extraTextureCount = reader.readVarInt();
        for (int i = 0; i < extraTextureCount; i++) {
            reader.readString();
            reader.readByteArray();
            reader.readVarInt();
            reader.readVarInt();
        }

        int modelTableSize = reader.readVarInt();
        for (int i = 0; i < modelTableSize; i++) {
            reader.readVarInt();
            reader.readString();
        }
        int animationTableSize = reader.readVarInt();
        for (int i = 0; i < animationTableSize; i++) {
            reader.readVarInt();
            reader.readString();
        }
        int textureTableSize = reader.readVarInt();
        for (int i = 0; i < textureTableSize; i++) {
            reader.readString();
            reader.readString();
            int subCount = reader.readVarInt();
            for (int j = 0; j < subCount; j++) {
                reader.readVarInt();
                reader.readString();
            }
        }

        return new YsmModel(format, modelId, bones, textures, animations, controllers);
    }

    private static RawGeometry parseGeometry(YsmByteReader reader) throws IOException {
        int boneCount = reader.readVarInt();
        List<RawBone> rawBones = new ArrayList<>();
        for (int i = 0; i < boneCount; i++) {
            String parent = reader.readString();
            int cubeCount = reader.readVarInt();
            List<RawCube> rawCubes = new ArrayList<>();
            for (int j = 0; j < cubeCount; j++) {
                int faceCount = reader.readVarInt();
                List<RawFace> rawFaces = new ArrayList<>();
                for (int k = 0; k < faceCount; k++) {
                    RawFace face = new RawFace();
                    face.normal = reader.readVec3();
                    for (int v = 0; v < 4; v++) {
                        face.positions[v] = reader.readVec3();
                        face.u[v] = reader.readFloat();
                        face.v[v] = reader.readFloat();
                    }
                    rawFaces.add(face);
                }
                reader.readVarInt();
                reader.readVarInt();
                reader.readVarInt();
                rawCubes.add(new RawCube(rawFaces));
            }
            String name = reader.readString();
            for (int p = 0; p < 5; p++) reader.readVarInt();
            Vec3 pivot = reader.readVec3();
            Vec3 rotation = reader.readVec3();
            rawBones.add(new RawBone(name, parent, pivot, rotation, rawCubes));
        }

        String identifier = reader.readString();
        float textureHeight = reader.readFloat();
        float textureWidth = reader.readFloat();
        reader.readFloat();
        reader.readFloat();
        int offsetCount = reader.readVarInt();
        for (int i = 0; i < offsetCount; i++) reader.readFloat();
        reader.readFloat();
        reader.readFloat();
        if (reader.readVarInt() > 0) parseLegacyInfo(reader);
        reader.readVarInt();
        reader.readVarInt();
        reader.readVarInt();

        float tw = textureWidth > 0f ? textureWidth : 1f;
        float th = textureHeight > 0f ? textureHeight : 1f;
        List<YsmBone> bones = new ArrayList<>();
        for (RawBone raw : rawBones) {
            List<YsmCube> cubes = new ArrayList<>();
            for (RawCube rawCube : raw.cubes) {
                cubes.add(bakeCube(rawCube.faces, tw, th));
            }
            bones.add(new YsmBone(raw.name, raw.parent, raw.pivot, raw.rotation, cubes));
        }
        return new RawGeometry(identifier, bones);
    }

    private static YsmCube bakeCube(List<RawFace> faces, float textureWidth, float textureHeight) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (RawFace face : faces) {
            for (Vec3 point : face.positions) {
                if (point == null) continue;
                minX = Math.min(minX, point.x());
                minY = Math.min(minY, point.y());
                minZ = Math.min(minZ, point.z());
                maxX = Math.max(maxX, point.x());
                maxY = Math.max(maxY, point.y());
                maxZ = Math.max(maxZ, point.z());
            }
        }
        Vec3 origin = faces.isEmpty() || Float.isInfinite(minX)
                ? Vec3.ZERO : new Vec3(minX, minY, minZ);
        Vec3 size = faces.isEmpty() || Float.isInfinite(maxX)
                ? Vec3.ZERO : new Vec3(maxX - minX, maxY - minY, maxZ - minZ);

        Map<String, YsmFace> mapped = new LinkedHashMap<>();
        for (int i = 0; i < faces.size(); i++) {
            RawFace face = faces.get(i);
            float uMin = Math.min(Math.min(face.u[0], face.u[1]), Math.min(face.u[2], face.u[3]));
            float uMax = Math.max(Math.max(face.u[0], face.u[1]), Math.max(face.u[2], face.u[3]));
            float vMin = Math.min(Math.min(face.v[0], face.v[1]), Math.min(face.v[2], face.v[3]));
            float vMax = Math.max(Math.max(face.v[0], face.v[1]), Math.max(face.v[2], face.v[3]));
            int[] uv = {
                    Math.round(uMin * textureWidth), Math.round(vMin * textureHeight),
                    Math.round(uMax * textureWidth), Math.round(vMax * textureHeight)
            };
            mapped.put("face_" + i, new YsmFace(uv, ""));
        }
        return new YsmCube(origin, size, Vec3.ZERO, Vec3.ZERO, mapped, 0f, false);
    }

    private static void parseLegacyInfo(YsmByteReader reader) throws IOException {
        reader.readString();
        reader.readString();
        int extraAnimations = reader.readVarInt();
        for (int i = 0; i < extraAnimations; i++) reader.readString();
        int authors = reader.readVarInt();
        for (int i = 0; i < authors; i++) reader.readString();
        reader.readString();
        reader.readVarInt();
    }

    private static Map<String, YsmAnimation> parseAnimations(YsmByteReader reader, int format) throws IOException {
        Map<String, YsmAnimation> result = new LinkedHashMap<>();
        int animationCount = reader.readVarInt();
        for (int i = 0; i < animationCount; i++) {
            String name = reader.readString();
            float duration = reader.readFloat() / 20f;
            int loopMode = reader.readVarInt();
            if (format > 9) {
                reader.readVarInt();
                reader.readVarInt();
                int blendWeightCount = reader.readVarInt();
                for (int j = 0; j < blendWeightCount; j++) reader.readTypedValue();
                reader.readVarInt();
            }

            List<YsmChannel> channels = new ArrayList<>();
            int boneCount = reader.readVarInt();
            for (int j = 0; j < boneCount; j++) {
                String bone = reader.readString();
                for (String path : PATHS) {
                    List<YsmKeyframe> frames = parseChannel(reader);
                    if (!frames.isEmpty()) channels.add(new YsmChannel(bone, path, frames));
                }
            }

            int timelineGroups = reader.readVarInt();
            for (int j = 0; j < timelineGroups; j++) {
                int events = reader.readVarInt();
                for (int k = 0; k < events; k++) reader.readString();
                reader.readFloat();
            }
            if (format > 9) {
                int soundEffects = reader.readVarInt();
                for (int j = 0; j < soundEffects; j++) {
                    reader.readString();
                    reader.readFloat();
                }
            }

            boolean loop = loopMode == 1 || loopMode == 3;
            result.put(name, new YsmAnimation(name, duration, loop, channels));
        }
        return result;
    }

    private static List<YsmKeyframe> parseChannel(YsmByteReader reader) throws IOException {
        List<YsmKeyframe> frames = new ArrayList<>();
        int keyframeCount = reader.readVarInt();
        for (int i = 0; i < keyframeCount; i++) {
            float time = reader.readFloat() / 20f;
            int interpolationMode = reader.readVarInt();
            float[] values = readTriple(reader);
            boolean hasPre = reader.readVarInt() > 0;
            if (hasPre) values = readTriple(reader);
            frames.add(new YsmKeyframe(time, values, interpolationName(interpolationMode)));
        }
        return frames;
    }

    private static float[] readTriple(YsmByteReader reader) throws IOException {
        return new float[]{reader.readTypedValue(), reader.readTypedValue(), reader.readTypedValue()};
    }

    private static String interpolationName(int mode) {
        return switch (mode) {
            case 1 -> "step";
            case 2 -> "catmullrom";
            default -> "linear";
        };
    }

    private static void parseControllers(YsmByteReader reader, int format, List<YsmController> out) throws IOException {
        int controllerCount = reader.readVarInt();
        for (int i = 0; i < controllerCount; i++) {
            String name;
            if (format <= 15) {
                reader.readVarInt();
                name = "legacy_controller_" + i;
            } else {
                name = reader.readString();
                reader.readString();
            }
            int animationCount = reader.readVarInt();
            for (int j = 0; j < animationCount; j++) {
                String animationName = reader.readString();
                String initialState = reader.readString();
                int statesCount = reader.readVarInt();
                Map<String, YsmState> states = new LinkedHashMap<>();
                for (int s = 0; s < statesCount; s++) {
                    String stateName = reader.readString();
                    int animationsSize = reader.readVarInt();
                    Map<String, String> animations = new LinkedHashMap<>();
                    for (int k = 0; k < animationsSize; k++) {
                        animations.put(reader.readString(), reader.readString());
                    }
                    int transitionsSize = reader.readVarInt();
                    List<YsmTransition> transitions = new ArrayList<>();
                    for (int k = 0; k < transitionsSize; k++) {
                        transitions.add(new YsmTransition(reader.readString(), reader.readString()));
                    }
                    int onEntry = reader.readVarInt();
                    for (int k = 0; k < onEntry; k++) reader.readString();
                    int onExit = reader.readVarInt();
                    for (int k = 0; k < onExit; k++) reader.readString();
                    if (reader.readVarInt() != 0) {
                        reader.readFloat();
                    } else {
                        int blendCount = reader.readVarInt();
                        for (int k = 0; k < blendCount; k++) {
                            reader.readFloat();
                            reader.readFloat();
                        }
                    }
                    reader.readVarInt();
                    if (format > 26) {
                        int effectCount = reader.readVarInt();
                        for (int k = 0; k < effectCount; k++) reader.readString();
                    }
                    states.put(stateName, new YsmState(animations, transitions));
                }
                out.add(new YsmController(name.isEmpty() ? animationName : name, initialState, states));
            }
        }
    }

    private static void parseSoundFiles(YsmByteReader reader, int format) throws IOException {
        int soundCount = reader.readVarInt();
        for (int i = 0; i < soundCount; i++) {
            reader.readString();
            if (format > 15) reader.readString();
            reader.readByteArray();
        }
    }

    private static RawGeometry selectMain(List<RawGeometry> geometries) {
        for (RawGeometry geometry : geometries) {
            if (geometry.modelType == 1) return geometry;
        }
        return geometries.isEmpty() ? null : geometries.get(0);
    }

    private static final class RawGeometry {
        final String identifier;
        final List<YsmBone> bones;
        int modelType;

        RawGeometry(String identifier, List<YsmBone> bones) {
            this.identifier = identifier;
            this.bones = bones;
        }
    }

    private static final class RawBone {
        final String name;
        final String parent;
        final Vec3 pivot;
        final Vec3 rotation;
        final List<RawCube> cubes;

        RawBone(String name, String parent, Vec3 pivot, Vec3 rotation, List<RawCube> cubes) {
            this.name = name;
            this.parent = parent;
            this.pivot = pivot;
            this.rotation = rotation;
            this.cubes = cubes;
        }
    }

    private static final class RawCube {
        final List<RawFace> faces;

        RawCube(List<RawFace> faces) {
            this.faces = faces;
        }
    }

    private static final class RawFace {
        Vec3 normal;
        final Vec3[] positions = new Vec3[4];
        final float[] u = new float[4];
        final float[] v = new float[4];
    }
}
