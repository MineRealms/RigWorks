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
        List<YsmAnimation> animationClips = new ArrayList<>();
        int animationBlobCount = reader.readVarInt();
        for (int i = 0; i < animationBlobCount; i++) {
            int categoryId = reader.readVarInt();
            if (reader.readVarInt() != 1) throw new IOException("Expected 1 after animation id");
            for (YsmAnimation animation : parseAnimations(reader, format, categoryId)) {
                animationClips.add(animation);
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

        return new YsmModel(format, modelId, bones, textures, animations, controllers, animationClips);
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
                    Vec3 faceNormal = reader.readVec3();
                    face.normal[0] = faceNormal.x();
                    face.normal[1] = faceNormal.y();
                    face.normal[2] = faceNormal.z();
                    for (int v = 0; v < 4; v++) {
                        Vec3 point = reader.readVec3();
                        int offset = v * 3;
                        face.positions[offset] = point.x();
                        face.positions[offset + 1] = point.y();
                        face.positions[offset + 2] = point.z();
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
            for (int v = 0; v < 4; v++) {
                int offset = v * 3;
                minX = Math.min(minX, face.positions[offset]);
                minY = Math.min(minY, face.positions[offset + 1]);
                minZ = Math.min(minZ, face.positions[offset + 2]);
                maxX = Math.max(maxX, face.positions[offset]);
                maxY = Math.max(maxY, face.positions[offset + 1]);
                maxZ = Math.max(maxZ, face.positions[offset + 2]);
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
            float[] u4 = {face.u[0], face.u[1], face.u[2], face.u[3]};
            float[] v4 = {face.v[0], face.v[1], face.v[2], face.v[3]};
            mapped.put("face_" + i, new YsmFace(face.positions, face.normal, uv, "", u4, v4));
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

    private static List<YsmAnimation> parseAnimations(YsmByteReader reader, int format, int categoryId) throws IOException {
        List<YsmAnimation> result = new ArrayList<>();
        int animationCount = reader.readVarInt();
        for (int i = 0; i < animationCount; i++) {
            String name = reader.readString();
            float duration = reader.readFloat() / 20f;
            int loopMode = reader.readVarInt();
            List<Integer> formatFlags = new ArrayList<>();
            List<YsmTypedValue> blendWeight = new ArrayList<>();
            if (format > 9) {
                formatFlags.add(reader.readVarInt());
                formatFlags.add(reader.readVarInt());
                int blendWeightCount = reader.readVarInt();
                for (int j = 0; j < blendWeightCount; j++) blendWeight.add(reader.readTypedValuePreserved());
                formatFlags.add(reader.readVarInt());
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
            List<YsmInstructionKeyframe> instructions = new ArrayList<>(timelineGroups);
            for (int j = 0; j < timelineGroups; j++) {
                int events = reader.readVarInt();
                List<String> group = new ArrayList<>(events);
                for (int k = 0; k < events; k++) group.add(reader.readString());
                instructions.add(new YsmInstructionKeyframe(reader.readFloat() / 20f, group));
            }
            ArrayList<YsmSoundKeyframe> sounds = new ArrayList<>();
            if (format > 9) {
                int soundEffects = reader.readVarInt();
                sounds.ensureCapacity(soundEffects);
                for (int j = 0; j < soundEffects; j++) {
                    String sound = reader.readString();
                    sounds.add(new YsmSoundKeyframe(sound, reader.readFloat() / 20f));
                }
            }

            boolean loop = loopMode == 1 || loopMode == 3;
            result.add(new YsmAnimation(name, duration, loop, channels, loopMode,
                    blendWeight, instructions, sounds, formatFlags, animationCategory(categoryId), categoryId));
        }
        return result;
    }

    private static String animationCategory(int id) {
        return switch (id) {
            case 1 -> "main";
            case 2 -> "arm";
            case 3 -> "extra";
            case 4 -> "tac";
            case 5 -> "arrow";
            case 6 -> "carryon";
            case 7 -> "parcool";
            case 8 -> "swem";
            case 9 -> "slashblade";
            case 10 -> "tlm";
            case 11 -> "fp_arm";
            case 12 -> "immersive_melodies";
            case 13 -> "irons_spell_books";
            default -> "unk_" + id;
        };
    }

    private static List<YsmKeyframe> parseChannel(YsmByteReader reader) throws IOException {
        List<YsmKeyframe> frames = new ArrayList<>();
        int keyframeCount = reader.readVarInt();
        for (int i = 0; i < keyframeCount; i++) {
            float time = reader.readFloat() / 20f;
            int interpolationMode = reader.readVarInt();
            List<YsmTypedValue> firstValues = readTriple(reader);
            int preFlag = reader.readVarInt();
            if (preFlag != 0 && preFlag != 1)
                throw new IOException("Unsupported keyframe pre/post flag " + preFlag + " at offset " + reader.offset());
            boolean hasPre = preFlag == 1;
            List<YsmTypedValue> preValues = hasPre ? firstValues : List.of();
            List<YsmTypedValue> postValues = hasPre ? readTriple(reader) : firstValues;
            frames.add(new YsmKeyframe(time, preValues, postValues,
                    interpolationName(interpolationMode), interpolationMode, hasPre));
        }
        return frames;
    }

    private static List<YsmTypedValue> readTriple(YsmByteReader reader) throws IOException {
        return List.of(reader.readTypedValuePreserved(), reader.readTypedValuePreserved(), reader.readTypedValuePreserved());
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
            String sourceGroup;
            int sourceGroupId = -1;
            if (format <= 15) {
                sourceGroupId = reader.readVarInt();
                sourceGroup = "legacy_controller_" + i;
            } else {
                sourceGroup = reader.readString();
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
                    List<YsmAnimationReference> animationReferences = new ArrayList<>(animationsSize);
                    for (int k = 0; k < animationsSize; k++) {
                        String referencedAnimation = reader.readString();
                        String condition = reader.readString();
                        animations.put(referencedAnimation, condition);
                        animationReferences.add(new YsmAnimationReference(referencedAnimation, condition));
                    }
                    int transitionsSize = reader.readVarInt();
                    List<YsmTransition> transitions = new ArrayList<>();
                    for (int k = 0; k < transitionsSize; k++) {
                        transitions.add(new YsmTransition(reader.readString(), reader.readString()));
                    }
                    int onEntry = reader.readVarInt();
                    List<String> onEntryCommands = new ArrayList<>(onEntry);
                    for (int k = 0; k < onEntry; k++) onEntryCommands.add(reader.readString());
                    int onExit = reader.readVarInt();
                    List<String> onExitCommands = new ArrayList<>(onExit);
                    for (int k = 0; k < onExit; k++) onExitCommands.add(reader.readString());
                    Float linearLength = null;
                    List<YsmBlendPoint> blendPoints = new ArrayList<>();
                    if (reader.readVarInt() != 0) {
                        linearLength = reader.readFloat();
                    } else {
                        int blendCount = reader.readVarInt();
                        for (int k = 0; k < blendCount; k++) {
                            blendPoints.add(new YsmBlendPoint(reader.readFloat(), reader.readFloat()));
                        }
                    }
                    boolean blendViaShortestPath = reader.readVarInt() != 0;
                    ArrayList<String> soundEffects = new ArrayList<>();
                    if (format > 26) {
                        int effectCount = reader.readVarInt();
                        soundEffects.ensureCapacity(effectCount);
                        for (int k = 0; k < effectCount; k++) soundEffects.add(reader.readString());
                    }
                    states.put(stateName, new YsmState(animations, transitions, animationReferences,
                            onEntryCommands, onExitCommands, new YsmBlendTransition(linearLength, blendPoints),
                            blendViaShortestPath, soundEffects));
                }
                out.add(new YsmController(animationName, initialState, states, sourceGroup, sourceGroupId));
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
        final float[] normal = new float[3];
        final float[] positions = new float[12];
        final float[] u = new float[4];
        final float[] v = new float[4];
    }
}
