package cn.minerealms.rigworks.ysm.asset;

import cn.minerealms.rigworks.ysm.model.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/** Bounded reader for RigWorks' own cache schema; no YSM decoding or native conversion occurs here. */
public final class RwymReader {
    public record Geometry(int sourceId, String category, String identifier, List<YsmBone> bones) {}
    public record Asset(int sourceFormat, List<Geometry> geometries, List<YsmAnimation> clips,
                        List<YsmController> controllers, Map<String, YsmTexture> textures) {
        public YsmModel mainModel() throws IOException {
            Geometry main = geometries.stream().filter(g -> g.sourceId() == 1 || "main".equals(g.category()))
                    .findFirst().orElse(geometries.isEmpty() ? null : geometries.get(0));
            if (main == null) throw new IOException("RWYM asset contains no geometry");
            Map<String, YsmAnimation> byName = new LinkedHashMap<>();
            for (YsmAnimation clip : clips) byName.putIfAbsent(clip.name(), clip);
            return new YsmModel(sourceFormat, main.identifier(), main.bones(), textures, byName, controllers, clips);
        }
    }

    private record Section(int type, int offset, int length) {}
    private RwymReader() {}

    public static Asset read(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 24 || bytes.length > RwymNative.MAX_ASSET_BYTES)
            throw new IOException("Invalid RWYM asset size");
        Reader header = new Reader(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN));
        if (header.integer() != 0x4D595752 || header.integer() != 0x00180001)
            throw new IOException("Unsupported RWYM magic or schema");
        int format = header.integer();
        int count = header.count();
        if (count > 4096 || header.longValue() != 24 || 24L + count * 32L > bytes.length)
            throw new IOException("Invalid RWYM section directory");
        int directoryEnd = 24 + count * 32;
        Map<Integer, Section> sections = new LinkedHashMap<>();
        List<Section> ranges = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int type = header.integer();
            header.integer();
            long offset = header.longValue(), size = header.longValue();
            long checksum = Integer.toUnsignedLong(header.integer());
            if (header.integer() != 0 || offset < directoryEnd || offset > bytes.length || size < 0 || size > bytes.length - offset)
                throw new IOException("Invalid RWYM section bounds");
            var section = new Section(type, (int) offset, (int) size);
            if (type >= 1 && type <= 7 && sections.putIfAbsent(type, section) != null)
                throw new IOException("Duplicate RWYM section: " + type);
            ranges.add(section);
            CRC32 crc = new CRC32();
            crc.update(bytes, section.offset(), section.length());
            if (crc.getValue() != checksum) throw new IOException("RWYM section checksum mismatch: " + type);
        }
        ranges.sort(Comparator.comparingInt(Section::offset));
        for (int i = 1; i < ranges.size(); i++)
            if ((long) ranges.get(i - 1).offset() + ranges.get(i - 1).length() > ranges.get(i).offset())
                throw new IOException("Overlapping RWYM sections");
        for (int type = 1; type <= 7; type++) if (!sections.containsKey(type))
            throw new IOException("Missing RWYM section: " + type);
        return new Asset(format, models(reader(bytes, sections.get(2))), animations(reader(bytes, sections.get(3))),
                controllers(reader(bytes, sections.get(4))), textures(reader(bytes, sections.get(5))));
    }

    private static Reader reader(byte[] bytes, Section section) {
        return new Reader(ByteBuffer.wrap(bytes, section.offset(), section.length()).slice().order(ByteOrder.LITTLE_ENDIAN));
    }

    private static List<Geometry> models(Reader r) throws IOException {
        List<Geometry> result = new ArrayList<>();
        for (int i = 0, n = r.count(); i < n; i++) {
            int id = r.integer();
            String category = r.string();
            r.integer();
            String identifier = r.string();
            r.skip(16);
            r.skip(Math.multiplyExact(r.count(), 4));
            r.skip(8);
            List<YsmBone> bones = new ArrayList<>();
            for (int b = 0, bn = r.count(); b < bn; b++) {
                String parent = r.string(), name = r.string();
                List<YsmCube> cubes = new ArrayList<>();
                for (int c = 0, cn = r.count(); c < cn; c++) {
                    Map<String, YsmFace> faces = new LinkedHashMap<>();
                    for (int f = 0, fn = r.count(); f < fn; f++) {
                        float[] normal = r.vector();
                        float[] positions = new float[12], u = new float[4], v = new float[4];
                        for (int p = 0; p < 4; p++) {
                            for (int axis = 0; axis < 3; axis++) positions[p * 3 + axis] = r.number();
                            u[p] = r.number(); v[p] = r.number();
                        }
                        faces.put("face_" + f, new YsmFace(positions, normal, null, "", u, v));
                    }
                    r.skip(12);
                    cubes.add(new YsmCube(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, faces, 0, false));
                }
                r.skip(20);
                Vec3 pivot = r.vec3(), rotation = r.vec3();
                bones.add(new YsmBone(name, parent, pivot, rotation, List.copyOf(cubes)));
            }
            r.integer();
            if (r.bool()) {
                r.string(); r.string(); r.strings(); r.strings(); r.string(); r.integer();
            }
            r.skip(12);
            result.add(new Geometry(id, category, identifier, List.copyOf(bones)));
        }
        r.end();
        return List.copyOf(result);
    }

    private static List<YsmAnimation> animations(Reader r) throws IOException {
        List<YsmAnimation> clips = new ArrayList<>();
        String[] paths = {"rotation", "position", "scale"};
        for (int i = 0, n = r.count(); i < n; i++) {
            int categoryId = r.integer();
            String category = r.string();
            r.integer();
            for (int a = 0, an = r.count(); a < an; a++) {
                String name = r.string();
                float duration = r.duration() / 20f;
                int loop = r.integer();
                List<Integer> flags = new ArrayList<>();
                for (int k = 0, kn = r.count(); k < kn; k++) flags.add(r.integer());
                List<YsmTypedValue> weight = r.values();
                List<YsmChannel> channels = new ArrayList<>();
                for (int b = 0, bn = r.count(); b < bn; b++) {
                    String bone = r.string();
                    for (String path : paths) {
                        List<YsmKeyframe> frames = new ArrayList<>();
                        for (int k = 0, kn = r.count(); k < kn; k++) {
                            float time = r.number() / 20f;
                            int interpolation = r.integer();
                            boolean hasPre = r.bool();
                            List<YsmTypedValue> pre = hasPre ? r.value3() : List.of();
                            frames.add(new YsmKeyframe(time, pre, r.value3(),
                                    interpolation == 2 ? "catmullrom" : "linear", interpolation, hasPre));
                        }
                        if (!frames.isEmpty()) channels.add(new YsmChannel(bone, path, List.copyOf(frames)));
                    }
                }
                List<YsmInstructionKeyframe> instructions = new ArrayList<>();
                for (int k = 0, kn = r.count(); k < kn; k++) instructions.add(new YsmInstructionKeyframe(r.number() / 20f, r.strings()));
                List<YsmSoundKeyframe> sounds = new ArrayList<>();
                for (int k = 0, kn = r.count(); k < kn; k++) {
                    float time = r.number() / 20f;
                    sounds.add(new YsmSoundKeyframe(r.string(), time));
                }
                clips.add(new YsmAnimation(name, duration, loop == 1, channels, loop, weight, instructions, sounds, flags, category, categoryId));
            }
        }
        r.end();
        return List.copyOf(clips);
    }

    private static List<YsmController> controllers(Reader r) throws IOException {
        List<YsmController> controllers = new ArrayList<>();
        for (int i = 0, n = r.count(); i < n; i++) {
            String name = r.string(), group = r.string();
            r.string(); r.integer();
            String initial = r.string();
            Map<String, YsmState> states = new LinkedHashMap<>();
            for (int s = 0, sn = r.count(); s < sn; s++) {
                String state = r.string();
                Map<String, String> animationMap = new LinkedHashMap<>();
                List<YsmAnimationReference> references = new ArrayList<>();
                for (int a = 0, an = r.count(); a < an; a++) {
                    String animation = r.string(), condition = r.string();
                    animationMap.put(animation, condition);
                    references.add(new YsmAnimationReference(animation, condition));
                }
                List<YsmTransition> transitions = new ArrayList<>();
                for (int t = 0, tn = r.count(); t < tn; t++) {
                    String condition = r.string(), next = r.string();
                    transitions.add(new YsmTransition(next, condition));
                }
                List<String> entry = r.strings(), exit = r.strings();
                boolean fixed = r.bool();
                float length = r.number();
                List<YsmBlendPoint> points = new ArrayList<>();
                for (int b = 0, bn = r.count(); b < bn; b++) points.add(new YsmBlendPoint(r.number(), r.number()));
                boolean shortest = r.bool();
                states.put(state, new YsmState(animationMap, transitions, references, entry, exit,
                        new YsmBlendTransition(fixed ? length : null, points), shortest, r.strings()));
            }
            controllers.add(new YsmController(name, initial, states, group, -1));
        }
        r.end();
        return List.copyOf(controllers);
    }

    private static Map<String, YsmTexture> textures(Reader r) throws IOException {
        Map<String, YsmTexture> textures = new LinkedHashMap<>();
        for (int i = 0, n = r.count(); i < n; i++) {
            String name = r.string(); r.string();
            textures.put(name, image(r, name, true));
            for (int v = 0, vn = r.count(); v < vn; v++) image(r, name, false);
        }
        for (int i = 0, n = r.count(); i < n; i++) { String name = r.string(); image(r, name, false); }
        for (int type = 0; type < 2; type++) for (int i = 0, n = r.count(); i < n; i++) { r.string(); r.string(); r.skipBytes(); }
        for (int i = 0, n = r.count(); i < n; i++) {
            r.string(); r.string();
            for (int e = 0, en = r.count(); e < en; e++) { r.string(); r.string(); }
        }
        r.end();
        return java.util.Collections.unmodifiableMap(textures);
    }

    private static YsmTexture image(Reader r, String fallbackName, boolean decode) throws IOException {
        r.integer();
        String name = r.string(); r.string();
        int width = r.integer(), height = r.integer();
        r.integer(); r.integer();
        int length = r.length();
        if (width < 0 || height < 0 || width > 16384 || height > 16384 || (long) width * height * 4 != length)
            throw new IOException("Invalid RWYM image dimensions");
        if (!decode) { r.skip(length); return null; }
        byte[] rgba = r.bytes(length);
        return new YsmTexture(name.isBlank() ? fallbackName : name, width, height, YsmPngEncoder.toPng(rgba, width, height));
    }

    private static final class Reader {
        private final ByteBuffer data;
        Reader(ByteBuffer data) { this.data = data; }
        void need(int n) throws IOException { if (n < 0 || n > data.remaining()) throw new IOException("Truncated RWYM section"); }
        int integer() throws IOException { need(4); return data.getInt(); }
        long longValue() throws IOException { need(8); return data.getLong(); }
        float number() throws IOException {
            float value = Float.intBitsToFloat(integer());
            if (!Float.isFinite(value)) throw new IOException("Non-finite RWYM number");
            return value;
        }
        float duration() throws IOException {
            float value = Float.intBitsToFloat(integer());
            if (Float.isNaN(value) || value < 0) throw new IOException("Invalid RWYM animation duration");
            return value;
        }
        int count() throws IOException {
            int n = integer();
            if (n < 0 || n > 1_000_000 || n > data.remaining()) throw new IOException("Invalid RWYM collection size");
            return n;
        }
        int length() throws IOException { int n = integer(); need(n); return n; }
        void skip(int length) throws IOException { need(length); data.position(data.position() + length); }
        void skipBytes() throws IOException { skip(length()); }
        byte[] bytes(int length) throws IOException { need(length); byte[] bytes = new byte[length]; data.get(bytes); return bytes; }
        String string() throws IOException {
            int length = length();
            if (length > 16 * 1024 * 1024) throw new IOException("RWYM string exceeds limit");
            ByteBuffer part = data.slice(); part.limit(length);
            try {
                String text = StandardCharsets.UTF_8.newDecoder().decode(part).toString();
                skip(length); return text;
            } catch (CharacterCodingException error) { throw new IOException("Invalid RWYM UTF-8", error); }
        }
        boolean bool() throws IOException { need(1); byte value = data.get(); if (value != 0 && value != 1) throw new IOException("Invalid RWYM boolean"); return value != 0; }
        float[] vector() throws IOException { return new float[]{number(), number(), number()}; }
        Vec3 vec3() throws IOException { return new Vec3(number(), number(), number()); }
        List<String> strings() throws IOException { List<String> values = new ArrayList<>(); for (int i = 0, n = count(); i < n; i++) values.add(string()); return values; }
        YsmTypedValue value() throws IOException {
            need(1); int tag = data.get() & 255;
            return switch (tag) { case 1 -> YsmTypedValue.ofFloat(number()); case 2 -> YsmTypedValue.expression(string()); default -> throw new IOException("Invalid RWYM expression tag"); };
        }
        List<YsmTypedValue> value3() throws IOException { return List.of(value(), value(), value()); }
        List<YsmTypedValue> values() throws IOException { List<YsmTypedValue> result = new ArrayList<>(); for (int i = 0, n = count(); i < n; i++) result.add(value()); return result; }
        void end() throws IOException { if (data.hasRemaining()) throw new IOException("Trailing RWYM section bytes"); }
    }
}
