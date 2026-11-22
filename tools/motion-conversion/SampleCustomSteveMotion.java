import com.mmd.jni.nativebridge.DataFlowBridge;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Offline, optional reference sampler. The original engine binaries stay outside this repository. */
public final class SampleCustomSteveMotion {
    private static final int FPS = 60;
    private static final int MAX_CLIPS = 64;
    private static final int MAX_SAMPLES_PER_CLIP = FPS * 60 + 1;

    private record ClipSpec(String id, String source, int start, int end, String mode, String sourceMask) {
        int lastFrame(int maximum) { return end < 0 ? maximum : end; }
    }

    private record Sample(float[] worldPositions, float[] worldRotations, float[] localPositions, float[] localRotations) {}

    public static void main(String[] args) throws Exception {
        Path pack = Path.of(option(args, "pack", "G:/DOWNLOAD-EDGE/CustomSteve-neoforge-1.21.11-1.0.0-rc-hf4-FullPack.zip"));
        Path output = Path.of(option(args, "output", "build/vmd-motion-preview/source-samples.json")).toAbsolutePath();
        Path manifest = Path.of(option(args, "manifest", "tools/motion-conversion/source_clips.tsv")).toAbsolutePath();
        List<ClipSpec> clips = readManifest(manifest);
        Path bridge = Path.of(System.getProperty("mmd.jni.path", "H:/MinecraftMods/Reverse/customsteve-1.21.11-rc-hf4-analysis/extracted/customsteve-natives/windows-x86_64/df_java_bridge.dll"));
        Path source = output.getParent().resolve("source");
        Files.createDirectories(source);
        Map<String, Path> inputs = new LinkedHashMap<>();
        try (ZipFile archive = new ZipFile(pack.toFile())) {
            List<? extends ZipEntry> models = archive.stream().filter(e -> e.getName().contains("/pmxModels/")
                    && e.getName().endsWith(".pmx") && !e.getName().contains("/sampleVanillaWeapons/")).toList();
            if (models.size() != 1) throw new IllegalArgumentException("Expected exactly one character PMX, got " + models.size());
            copy(archive, models.getFirst(), source.resolve("source.pmx"));
            inputs.put("pmx", source.resolve("source.pmx"));
            for (ClipSpec clip : clips) {
                if (inputs.containsKey(clip.source)) continue;
                ZipEntry entry = archive.getEntry("CustomSteve/motions/" + clip.source);
                if (entry == null) throw new IllegalArgumentException("Missing motion: " + clip.source);
                Path path = source.resolve(clip.source);
                copy(archive, entry, path);
                inputs.put(clip.source, path);
            }
        }
        System.setProperty("mmd.jni.path", bridge.toAbsolutePath().toString());
        DataFlowBridge.load();
        long engine = DataFlowBridge.dfEngineCreate();
        long pmx = 0, bones = 0, morphs = 0, vertices = 0;
        try {
            check(engine, "engine");
            pmx = DataFlowBridge.dfPmxLoadFile(engine, inputs.get("pmx").toString());
            check(pmx, "PMX");
            bones = DataFlowBridge.dfBoneBufferCreate(pmx);
            morphs = DataFlowBridge.dfMorphBufferCreate(pmx);
            vertices = DataFlowBridge.dfVertexBufferCreate(pmx);
            check(bones, "bone buffer");
            check(morphs, "morph buffer");
            check(vertices, "vertex buffer");
            int count = DataFlowBridge.dfBoneBufferCount(bones);
            String[] names = new String[count];
            int[] parents = new int[count];
            float[] rest = new float[count * 3];
            for (int i = 0; i < count; i++) names[i] = DataFlowBridge.dfPmxGetBoneName(pmx, i);
            DataFlowBridge.dfPmxBoneParentIndices(pmx, parents, count);
            DataFlowBridge.dfBonesReset(bones, pmx);
            DataFlowBridge.dfBonesComputeWorld(bones, pmx);
            DataFlowBridge.dfBoneBufferCopyRestPositions(bones, rest, count);
            Sample bind = snapshot(bones, count);
            validateRest(bind, rest);
            Path temporary = output.resolveSibling(output.getFileName() + ".partial");
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                writer.write("{\"version\":1,\"basis\":\"mmd-y-up\",\"coordinateSystem\":{\"handedness\":\"right\",\"sourcePmxConversion\":\"x,y,-z\",\"units\":\"native MMD units\"},\"fps\":" + FPS
                        + ",\"manifestSha256\":" + quoted(sha256(manifest)) + ",\"engine\":{");
                writer.write("\"artifact\":\"com.mmd:java_ngengine:1.0.0-rc-hf2\",\"bridgeSha256\":" + quoted(sha256(bridge)));
                Path javaArtifact = Path.of(DataFlowBridge.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                if (Files.isRegularFile(javaArtifact)) writer.write(",\"javaArtifactSha256\":" + quoted(sha256(javaArtifact)));
                writer.write(",\"simdLevel\":" + DataFlowBridge.dfEngineGetSimdLevel(engine) + "},\"sourceHashes\":{");
                boolean first = true;
                for (var input : inputs.entrySet()) {
                    if (!first) writer.write(','); first = false;
                    writer.write(quoted(input.getKey()) + ":" + quoted(sha256(input.getValue())));
                }
                writer.write("},\"bones\":[");
                for (int i = 0; i < count; i++) {
                    if (i > 0) writer.write(',');
                    writer.write("{\"name\":" + quoted(names[i]) + ",\"parent\":" + parents[i] + ",\"restPosition\":");
                    floats(writer, Arrays.copyOfRange(rest, i * 3, i * 3 + 3)); writer.write('}');
                }
                writer.write("],\"bind\":"); sampleJson(writer, 0, bind);
                writer.write(",\"clips\":{");
                for (int i = 0; i < clips.size(); i++) {
                    if (i > 0) writer.write(',');
                    ClipSpec clip = clips.get(i);
                    sampleClip(writer, engine, pmx, bones, morphs, vertices, count, names, clip, inputs.get(clip.source));
                }
                writer.write("}}\n");
            }
            Files.move(temporary, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Wrote " + output + " (" + Files.size(output) + " bytes, " + count + " bones, " + clips.size() + " clips)");
        } finally {
            if (vertices != 0) DataFlowBridge.dfVertexBufferDestroy(vertices);
            if (morphs != 0) DataFlowBridge.dfMorphBufferDestroy(morphs);
            if (bones != 0) DataFlowBridge.dfBoneBufferDestroy(bones);
            if (pmx != 0) DataFlowBridge.dfPmxDestroy(pmx);
            if (engine != 0) DataFlowBridge.dfEngineDestroy(engine);
        }
    }

    private static void sampleClip(BufferedWriter writer, long engine, long pmx, long bones, long morphs, long vertices,
                                   int count, String[] names, ClipSpec clip, Path path) throws Exception {
        String name = clip.id;
        long vmd = DataFlowBridge.dfVmdLoadFile(engine, path.toString());
        long motion = 0;
        try {
            check(vmd, name + " VMD");
            motion = DataFlowBridge.dfMotionCtxCreate(pmx, vmd);
            check(motion, name + " motion context");
            DataFlowBridge.dfMotionCtxSetLoop(motion, 0);
            int sourceMaximum = maxBoneFrame(path);
            int lastFrame = clip.lastFrame(sourceMaximum);
            if (clip.start > sourceMaximum || lastFrame > sourceMaximum || lastFrame < clip.start) {
                throw new IllegalArgumentException("Clip " + name + " requests [" + clip.start + "," + lastFrame
                        + "] outside source frames [0," + sourceMaximum + "]");
            }
            boolean isStatic = clip.mode.equals("static");
            if (isStatic && lastFrame != clip.start) throw new IllegalArgumentException("Static clip must select a single frame: " + name);
            double duration = (lastFrame - clip.start) / 30.0;
            int samples = (int) Math.ceil(duration * FPS) + 1;
            if (samples > MAX_SAMPLES_PER_CLIP) throw new IllegalArgumentException("Preview clip exceeds one minute: " + name);
            Sample first = evaluate(pmx, bones, morphs, vertices, motion, count, clip.start);
            evaluate(pmx, bones, morphs, vertices, motion, count, (clip.start + lastFrame) * 0.5f);
            Sample firstAgain = evaluate(pmx, bones, morphs, vertices, motion, count, clip.start);
            double seekError = maxDifference(first.worldPositions, firstAgain.worldPositions);
            double seekRotationError = maxDifference(first.worldRotations, firstAgain.worldRotations);
            if (seekError > 1e-4) throw new IllegalStateException("Nonrepeatable absolute seek: " + name + " error=" + seekError);
            if (seekRotationError > 1e-4) throw new IllegalStateException("Nonrepeatable rotation seek: " + name + " error=" + seekRotationError);
            writer.write(quoted(name) + ":{\"duration\":" + duration
                    + ",\"sourceVmd\":" + quoted(path.getFileName().toString())
                    + ",\"sourceMaxFrame\":" + sourceMaximum + ",\"sourceStartFrame\":" + clip.start + ",\"sourceEndFrame\":" + lastFrame
                    + ",\"samplingMode\":" + quoted(clip.mode) + ",\"sourceMask\":" + quoted(clip.sourceMask)
                    + ",\"absoluteSeekError\":" + seekError + ",\"absoluteRotationSeekError\":" + seekRotationError + ",\"frames\":[");
            float[] kneeReference = first.localRotations;
            double kneeRotation = 0;
            for (int sample = 0; sample < samples; sample++) {
                if (sample > 0) writer.write(',');
                double time = samples == 1 ? 0 : Math.min(sample / (double) FPS, duration);
                Sample pose = evaluate(pmx, bones, morphs, vertices, motion, count, (float) (clip.start + time * 30));
                sampleJson(writer, time, pose);
                for (int i = 0; i < count; i++) if (names[i].equals("\u5de6\u3072\u3056") || names[i].equals("\u53f3\u3072\u3056")) {
                    double dot = 0;
                    for (int c = 0; c < 4; c++) dot += kneeReference[i * 4 + c] * pose.localRotations[i * 4 + c];
                    kneeRotation = Math.max(kneeRotation, Math.toDegrees(2 * Math.acos(Math.min(1, Math.abs(dot)))));
                }
            }
            writer.write("],\"maxKneeRotationFromFirstDegrees\":" + kneeRotation + "}");
            System.out.println(name + " <- " + clip.source + "[" + clip.start + ":" + lastFrame + "] " + clip.mode
                    + ": " + samples + " samples, duration=" + duration + "s, seekError=" + seekError
                    + ", kneeRotation=" + kneeRotation + "deg");
            if ((name.equals("walk") || name.equals("run") || name.equals("jump")) && kneeRotation < 1) {
                throw new IllegalStateException("IK did not animate the knee in " + name);
            }
        } finally {
            if (motion != 0) DataFlowBridge.dfMotionCtxDestroy(motion);
            if (vmd != 0) DataFlowBridge.dfVmdDestroy(vmd);
        }
    }

    private static Sample evaluate(long pmx, long bones, long morphs, long vertices, long motion, int count, float frame) {
        DataFlowBridge.dfBonesReset(bones, pmx);
        for (int i = 0, n = DataFlowBridge.dfMorphBufferCount(morphs); i < n; i++) {
            DataFlowBridge.dfMorphBufferSetWeight(morphs, i, 0);
        }
        DataFlowBridge.dfMotionCtxSetFrame(motion, frame);
        // The native evaluator advances by its argument, so seeking uses an explicit frame plus zero delta.
        DataFlowBridge.dfMotionEvaluate(motion, 0, bones, morphs);
        if (Math.abs(DataFlowBridge.dfMotionCtxGetFrame(motion) - frame) > 1e-3) {
            throw new IllegalStateException("Native motion frame did not match absolute seek " + frame);
        }
        DataFlowBridge.dfMorphsApply(pmx, morphs, vertices);
        DataFlowBridge.dfApplyBoneMorphs(pmx, morphs, bones);
        DataFlowBridge.dfBonesFullUpdateWithMotion(motion, bones, pmx);
        return snapshot(bones, count);
    }

    private static Sample snapshot(long bones, int count) {
        int[] indices = new int[count];
        float[] offsets = new float[count * 3], rotations = new float[count * 4], attachments = new float[count * 7];
        for (int i = 0; i < count; i++) { indices[i] = i; rotations[i * 4 + 3] = 1; }
        // Identity attachments expose the final native world matrix's translation and quaternion.
        DataFlowBridge.dfBoneBufferComputeAttachmentPoses(bones, indices, offsets, rotations, attachments, count);
        float[] worldPositions = new float[count * 3], worldRotations = new float[count * 4];
        float[] localPositions = new float[count * 3], localRotations = new float[count * 4], checkedPositions = new float[count * 3];
        for (int i = 0; i < count; i++) {
            System.arraycopy(attachments, i * 7, worldPositions, i * 3, 3);
            System.arraycopy(attachments, i * 7 + 3, worldRotations, i * 4, 4);
            double norm = 0;
            for (int c = 0; c < 4; c++) norm += worldRotations[i * 4 + c] * worldRotations[i * 4 + c];
            if (Math.abs(norm - 1) > 1e-3) throw new IllegalStateException("Invalid world quaternion on bone " + i + ": " + norm);
        }
        DataFlowBridge.dfBoneBufferCopyWorldPositions(bones, checkedPositions, count);
        if (maxDifference(worldPositions, checkedPositions) > 1e-4) throw new IllegalStateException("Attachment/world position mismatch");
        DataFlowBridge.dfBoneBufferCopyLocalPositions(bones, localPositions, count);
        DataFlowBridge.dfBoneBufferCopyLocalRotations(bones, localRotations, count);
        return new Sample(worldPositions, worldRotations, localPositions, localRotations);
    }

    private static void validateRest(Sample sample, float[] rest) {
        if (maxDifference(sample.worldPositions, rest) > 1e-4) throw new IllegalStateException("Unplayed bind differs from PMX rest positions");
    }

    private static double maxDifference(float[] a, float[] b) {
        double maximum = 0;
        for (int i = 0; i < a.length; i++) {
            if (!Float.isFinite(a[i]) || !Float.isFinite(b[i])) throw new IllegalStateException("Non-finite source pose");
            maximum = Math.max(maximum, Math.abs(a[i] - b[i]));
        }
        return maximum;
    }

    private static int maxBoneFrame(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        var buffer = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int modelBytes = new String(data, 0, 30, StandardCharsets.US_ASCII).contains("0002") ? 20 : 10;
        buffer.position(30 + modelBytes);
        long count = Integer.toUnsignedLong(buffer.getInt());
        if (count > (data.length - buffer.position()) / 111) throw new IllegalArgumentException("Truncated VMD bone records");
        int maximum = 0;
        for (long i = 0; i < count; i++) {
            int offset = buffer.position();
            maximum = Math.max(maximum, buffer.getInt(offset + 15));
            buffer.position(offset + 111);
        }
        return maximum;
    }

    private static void sampleJson(BufferedWriter writer, double time, Sample sample) throws IOException {
        writer.write("{\"time\":" + time + ",\"worldPositions\":"); floats(writer, sample.worldPositions);
        writer.write(",\"worldRotations\":"); floats(writer, sample.worldRotations);
        writer.write(",\"localPositions\":"); floats(writer, sample.localPositions);
        writer.write(",\"localRotations\":"); floats(writer, sample.localRotations); writer.write('}');
    }

    private static void floats(BufferedWriter writer, float[] values) throws IOException {
        writer.write('[');
        for (int i = 0; i < values.length; i++) {
            if (!Float.isFinite(values[i])) throw new IllegalStateException("Non-finite float");
            if (i > 0) writer.write(','); writer.write(Float.toString(values[i]));
        }
        writer.write(']');
    }

    private static String quoted(String text) {
        var result = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '"') result.append('\\').append(c);
            else if (c < 32 || c > 126) result.append(String.format("\\u%04x", (int) c));
            else result.append(c);
        }
        return result.append('"').toString();
    }

    private static void copy(ZipFile zip, ZipEntry entry, Path output) throws IOException {
        try (var input = zip.getInputStream(entry)) {
            Files.copy(input, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static String option(String[] args, String name, String fallback) {
        for (int i = 0; i + 1 < args.length; i++) if (args[i].equals("--" + name)) return args[i + 1];
        return fallback;
    }

    private static void check(long handle, String label) {
        if (handle == 0) throw new IllegalStateException("Native allocation/load failed: " + label);
    }

    private static List<ClipSpec> readManifest(Path path) throws IOException {
        var clips = new ArrayList<ClipSpec>();
        var ids = new HashSet<String>();
        String header = "clip\tsource\tstart_frame\tend_frame\tmode\tsource_mask";
        boolean hasHeader = false;
        int lineNumber = 0;
        for (String sourceLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNumber++;
            String line = sourceLine.strip();
            if (lineNumber == 1 && line.startsWith("\ufeff")) line = line.substring(1);
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (!hasHeader) {
                if (!line.equals(header)) throw new IllegalArgumentException(path + ":" + lineNumber + ": expected manifest TSV header");
                hasHeader = true;
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields.length != 6) throw new IllegalArgumentException(path + ":" + lineNumber + ": expected six tab-separated fields");
            for (int i = 0; i < fields.length; i++) fields[i] = fields[i].strip();
            if (!fields[0].matches("[a-z][a-z0-9_]{0,63}") || !ids.add(fields[0])) {
                throw new IllegalArgumentException(path + ":" + lineNumber + ": invalid or duplicate clip id");
            }
            if (!fields[1].matches("[A-Za-z0-9_-]+\\.vmd")) {
                throw new IllegalArgumentException(path + ":" + lineNumber + ": expected a VMD filename, not a path");
            }
            int start = parseFrame(fields[2], path, lineNumber);
            int end = fields[3].equals("*") ? -1 : parseFrame(fields[3], path, lineNumber);
            if (!List.of("static", "once", "loop").contains(fields[4])
                    || !List.of("full_body", "upper_body", "left_arm", "right_arm").contains(fields[5])) {
                throw new IllegalArgumentException(path + ":" + lineNumber + ": unsupported playback mode or source mask");
            }
            if (end >= 0 && end < start) throw new IllegalArgumentException(path + ":" + lineNumber + ": end precedes start");
            clips.add(new ClipSpec(fields[0], fields[1], start, end, fields[4], fields[5]));
            if (clips.size() > MAX_CLIPS) throw new IllegalArgumentException("At most " + MAX_CLIPS + " preview clips");
        }
        if (clips.isEmpty()) throw new IllegalArgumentException("Empty clip manifest: " + path);
        return List.copyOf(clips);
    }

    private static int parseFrame(String text, Path path, int line) {
        try {
            int value = Integer.parseInt(text);
            if (value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(path + ":" + line + ": invalid nonnegative frame: " + text, exception);
        }
    }
}
