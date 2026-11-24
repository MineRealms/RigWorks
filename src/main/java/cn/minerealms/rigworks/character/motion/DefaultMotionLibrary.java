package cn.minerealms.rigworks.character.motion;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** Shared, solved Y-up poses. Playback requires only Java and never loads the source MMD engine. */
public final class DefaultMotionLibrary {
    public static final String RESOURCE = "/assets/rigworks/motions/default/library.rwmd.gz";
    private static final int MAGIC = 0x52574D44;
    private static final int STRIDE = 7;
    private static final int MAX_COMPONENTS = 4_194_304;
    private static final int MAX_DECOMPRESSED_BYTES = 32 * 1024 * 1024;
    private static final Set<String> MODES = Set.of("loop", "once", "static");
    private static final Set<String> MASKS = Set.of("full_body", "upper_body", "left_arm", "right_arm");

    private final int fps;
    private final List<String> roles;
    private final Map<String, Clip> clips;
    private final Pose bind;
    private final int poseArrayBytes;
    private final int[] mirroredRoles;

    private DefaultMotionLibrary(int fps, List<String> roles, Map<String, Clip> clips, Pose bind, int components) {
        this.fps = fps;
        this.roles = List.copyOf(roles);
        this.clips = Collections.unmodifiableMap(new LinkedHashMap<>(clips));
        this.bind = bind;
        this.poseArrayBytes = components * Float.BYTES;
        mirroredRoles = new int[roles.size()];
        for (int index = 0; index < roles.size(); index++) {
            String role = roles.get(index);
            String counterpart = role.startsWith("left_") ? "right_" + role.substring(5)
                    : role.startsWith("right_") ? "left_" + role.substring(6) : role;
            int paired = roles.indexOf(counterpart);
            mirroredRoles[index] = paired < 0 ? index : paired;
        }
    }

    private static final class Builtin {
        static final DefaultMotionLibrary LIBRARY;
        static final IllegalStateException FAILURE;
        static {
            DefaultMotionLibrary library = null;
            IllegalStateException failure = null;
            try (InputStream input = DefaultMotionLibrary.class.getResourceAsStream(RESOURCE)) {
                if (input == null) throw new IOException("Missing built-in motion resource " + RESOURCE);
                library = read(input);
            } catch (IOException error) {
                org.apache.logging.log4j.LogManager.getLogger("rigworks.motion").error(
                        "Cannot load DEFAULT motion library {}", RESOURCE, error);
                failure = new IllegalStateException("Cannot load DEFAULT motion library " + RESOURCE, error);
            }
            LIBRARY = library;
            FAILURE = failure;
        }
    }

    /** Loads the shared library once. Every avatar owns only reusable output poses and its calibration. */
    public static DefaultMotionLibrary builtin() {
        if (Builtin.FAILURE != null) throw Builtin.FAILURE;
        return Builtin.LIBRARY;
    }

    public int fps() { return fps; }
    public List<String> roles() { return roles; }
    public Set<String> clipNames() { return clips.keySet(); }
    public Clip clip(String id) { return clips.get(id); }
    public int poseArrayBytes() { return poseArrayBytes; }
    public Pose newPose() { return new Pose(roles.size()); }

    /** The caller owns this copy; passing it to sample cannot corrupt the shared reference pose. */
    public Pose bind() { return newPose().set(bind); }

    /** Reflects source X and swaps paired semantic sides without allocating. In-place use is rejected. */
    public void mirror(Pose source, Pose destination) {
        if (source == destination) throw new IllegalArgumentException("Motion mirror requires separate source and destination poses");
        source.requireSize(roles.size() * STRIDE);
        destination.requireSize(roles.size() * STRIDE);
        for (int role = 0; role < mirroredRoles.length; role++) {
            int from = mirroredRoles[role] * STRIDE, to = role * STRIDE;
            destination.values[to] = -source.values[from];
            destination.values[to + 1] = source.values[from + 1];
            destination.values[to + 2] = source.values[from + 2];
            destination.values[to + 3] = source.values[from + 3];
            destination.values[to + 4] = -source.values[from + 4];
            destination.values[to + 5] = -source.values[from + 5];
            destination.values[to + 6] = source.values[from + 6];
        }
    }

    /** Reads a bounded RWMD/gzip stream and takes ownership of the stream. */
    public static DefaultMotionLibrary read(InputStream source) throws IOException {
        Objects.requireNonNull(source, "Motion library input");
        try (var input = new DataInputStream(new LimitedInputStream(
                new GZIPInputStream(new BufferedInputStream(source)), MAX_DECOMPRESSED_BYTES))) {
            if (input.readInt() != MAGIC || input.readInt() != 1)
                throw new IOException("Unsupported DEFAULT motion library header");
            int fps = input.readUnsignedShort();
            int roleCount = input.readUnsignedShort(), clipCount = input.readUnsignedShort();
            if (fps < 1 || fps > 240 || roleCount < 1 || roleCount > 64 || clipCount < 1 || clipCount > 256)
                throw new IOException("Invalid DEFAULT motion library counts");
            var roles = new ArrayList<String>(roleCount);
            for (int role = 0; role < roleCount; role++) {
                String name = text(input);
                if (roles.contains(name)) throw new IOException("Duplicate motion role: " + name);
                roles.add(name);
            }
            int components = roleCount * STRIDE;
            Pose bind = new Pose(roleCount);
            readPoses(input, bind.values, "bind");
            var clips = new LinkedHashMap<String, Clip>();
            for (int index = 0; index < clipCount; index++) {
                String id = text(input), sourceName = text(input), mode = text(input), mask = text(input);
                double duration = input.readDouble();
                int count = input.readInt();
                if (!MODES.contains(mode) || !MASKS.contains(mask) || !Double.isFinite(duration)
                        || duration < 0 || duration > 600 || count < 1 || count > 36_001
                        || count != (int) Math.ceil(duration * fps - 1e-8) + 1
                        || mode.equals("static") && (count != 1 || duration != 0))
                    throw new IOException("Invalid DEFAULT motion metadata for " + id);
                long size = (long) count * roleCount * STRIDE;
                if (size > MAX_COMPONENTS - components)
                    throw new IOException("DEFAULT motion library exceeds the pose memory budget at " + id);
                components += (int) size;
                float[] frames = new float[(int) size];
                readPoses(input, frames, id);
                Clip clip = new Clip(id, sourceName, mode, mask, duration, fps, roleCount, count, frames);
                if (clips.putIfAbsent(id, clip) != null) throw new IOException("Duplicate motion clip: " + id);
            }
            if (input.read() != -1) throw new IOException("Trailing DEFAULT motion library data");
            return new DefaultMotionLibrary(fps, roles, clips, bind, components);
        }
    }

    private static String text(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        if (count < 1 || count > 256) throw new IOException("Invalid DEFAULT motion string length");
        byte[] bytes = new byte[count];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void readPoses(DataInputStream input, float[] values, String id) throws IOException {
        for (int i = 0; i < values.length; i++) {
            float value = input.readFloat();
            if (!Float.isFinite(value) || Math.abs(value) > 1_000_000)
                throw new IOException("Invalid DEFAULT motion component in " + id);
            values[i] = value;
        }
        for (int offset = 0; offset < values.length; offset += STRIDE) {
            float squared = 0;
            for (int i = 3; i < STRIDE; i++) squared += values[offset + i] * values[offset + i];
            if (Math.abs(squared - 1) > .001f)
                throw new IOException("Nonunit DEFAULT motion quaternion in " + id);
            float inverse = (float) (1 / Math.sqrt(squared));
            for (int i = 3; i < STRIDE; i++) values[offset + i] *= inverse;
        }
    }

    /** Flat caller-owned sampling buffer. None of the accessors expose shared clip arrays. */
    public static final class Pose {
        private final float[] values;
        private Pose(int roles) {
            values = new float[roles * STRIDE];
            for (int offset = 6; offset < values.length; offset += STRIDE) values[offset] = 1;
        }
        public int roleCount() { return values.length / STRIDE; }
        public Vector3f position(int role, Vector3f destination) {
            int offset = Objects.checkIndex(role, roleCount()) * STRIDE;
            return destination.set(values[offset], values[offset + 1], values[offset + 2]);
        }
        public Quaternionf rotation(int role, Quaternionf destination) {
            int offset = Objects.checkIndex(role, roleCount()) * STRIDE + 3;
            return destination.set(values[offset], values[offset + 1], values[offset + 2], values[offset + 3]);
        }
        public Pose set(Pose source) {
            requireSize(source.values.length);
            System.arraycopy(source.values, 0, values, 0, values.length);
            return this;
        }
        public Pose blend(Pose from, Pose to, float weight) {
            requireSize(from.values.length);
            requireSize(to.values.length);
            if (!Float.isFinite(weight) || weight < 0 || weight > 1)
                throw new IllegalArgumentException("Motion blend weight must be in [0,1]");
            interpolate(from.values, 0, to.values, 0, weight, values);
            return this;
        }
        private void requireSize(int count) {
            if (values.length != count) throw new IllegalArgumentException("Motion pose has a different role count");
        }
    }

    /** Immutable sampled motion; times wrap for loops, clamp for one-shots, and hold for static poses. */
    public static final class Clip {
        private final String id, source, mode, mask;
        private final double duration;
        private final int fps, roles, frameCount;
        private final float[] frames;
        private Clip(String id, String source, String mode, String mask, double duration,
                     int fps, int roles, int frameCount, float[] frames) {
            this.id = id; this.source = source; this.mode = mode; this.mask = mask; this.duration = duration;
            this.fps = fps; this.roles = roles; this.frameCount = frameCount; this.frames = frames;
        }
        public String id() { return id; }
        public String source() { return source; }
        public String mode() { return mode; }
        public String mask() { return mask; }
        public double duration() { return duration; }
        public int frameCount() { return frameCount; }
        public boolean looping() { return mode.equals("loop"); }

        public void sample(double seconds, Pose destination) {
            if (!Double.isFinite(seconds)) throw new IllegalArgumentException("Nonfinite motion sample time");
            destination.requireSize(roles * STRIDE);
            double time = Math.max(0, seconds);
            if (duration > 0 && looping()) time %= duration;
            else time = Math.min(time, duration);
            double frame = Math.min(time * fps, frameCount - 1);
            int first = (int) frame, next = Math.min(first + 1, frameCount - 1);
            int stride = roles * STRIDE;
            if (first == next || frame == first) {
                System.arraycopy(frames, first * stride, destination.values, 0, stride);
            } else {
                interpolate(frames, first * stride, frames, next * stride, (float) (frame - first), destination.values);
            }
        }
    }

    private static void interpolate(float[] from, int fromOffset, float[] to, int toOffset,
                                    float weight, float[] destination) {
        for (int role = 0; role < destination.length; role += STRIDE) {
            int a = fromOffset + role, b = toOffset + role;
            for (int component = 0; component < 3; component++)
                destination[role + component] = from[a + component]
                        + (to[b + component] - from[a + component]) * weight;
            float dot = 0;
            for (int component = 3; component < STRIDE; component++) dot += from[a + component] * to[b + component];
            float sign = dot < 0 ? -1 : 1;
            dot = Math.min(1, Math.abs(dot));
            float startWeight = 1 - weight, endWeight = weight;
            if (dot < .9995f) {
                double angle = Math.acos(dot), inverseSin = 1 / Math.sin(angle);
                startWeight = (float) (Math.sin((1 - weight) * angle) * inverseSin);
                endWeight = (float) (Math.sin(weight * angle) * inverseSin);
            }
            float squared = 0;
            for (int component = 3; component < STRIDE; component++) {
                float value = from[a + component] * startWeight + to[b + component] * endWeight * sign;
                destination[role + component] = value;
                squared += value * value;
            }
            float inverseLength = (float) (1 / Math.sqrt(squared));
            for (int component = 3; component < STRIDE; component++) destination[role + component] *= inverseLength;
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private int remaining;
        private LimitedInputStream(InputStream input, int maximum) { super(input); remaining = maximum; }
        @Override public int read() throws IOException {
            if (remaining == 0) throw new IOException("DEFAULT motion library exceeds decompression budget");
            int value = in.read();
            if (value != -1) remaining--;
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining == 0) throw new IOException("DEFAULT motion library exceeds decompression budget");
            int count = in.read(bytes, offset, Math.min(length, remaining));
            if (count != -1) remaining -= count;
            return count;
        }
    }
}
