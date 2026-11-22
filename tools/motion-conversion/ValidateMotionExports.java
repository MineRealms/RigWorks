import cn.minerealms.assimploader.animation.AnimationPlayer;
import cn.minerealms.assimploader.animation.PoseSnapshot;
import cn.minerealms.assimploader.animation.SkinBindPose;
import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.core.ModelImportMode;
import cn.minerealms.assimploader.model.AssimpAnimation;
import cn.minerealms.assimploader.model.AssimpMesh;
import cn.minerealms.assimploader.model.AssimpNode;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.assimploader.rendering.CpuSkinning;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Read-only comparison of exported FBX clips with the Blender export manifest. */
public final class ValidateMotionExports {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final double POSITION_TOLERANCE = 0.003;
    private static final double BOUNDS_TOLERANCE = 0.003;
    private static final double DURATION_TOLERANCE = 0.00001;
    private static final double[] PHASES = {0, 0.5, 1};
    private final JsonArray failures = new JsonArray();
    private double maximumJointError;
    private double maximumBoundsError;

    private record Bounds(Vector3f minimum, Vector3f maximum, long vertices) {
        JsonObject json() {
            JsonObject result = new JsonObject();
            result.add("minimum", vector(minimum));
            result.add("maximum", vector(maximum));
            result.add("dimensions", vector(new Vector3f(maximum).sub(minimum)));
            result.addProperty("vertices", vertices);
            return result;
        }
    }

    private static JsonArray vector(Vector3f value) {
        JsonArray result = new JsonArray();
        result.add(value.x); result.add(value.y); result.add(value.z);
        return result;
    }

    private static Vector3f vector(JsonArray value) {
        if (value == null || value.size() != 3) throw new IllegalArgumentException("Expected XYZ vector");
        Vector3f result = new Vector3f(value.get(0).getAsFloat(), value.get(1).getAsFloat(), value.get(2).getAsFloat());
        if (!result.isFinite()) throw new IllegalArgumentException("Non-finite reference vector");
        return result;
    }

    private static Vector3f enginePosition(Vector3f blender) {
        return new Vector3f(-blender.x, blender.z, -blender.y);
    }

    private static Bounds engineBounds(JsonObject reference) {
        Vector3f low = vector(reference.getAsJsonArray("minimum"));
        Vector3f high = vector(reference.getAsJsonArray("maximum"));
        if (low.x > high.x || low.y > high.y || low.z > high.z)
            throw new IllegalArgumentException("Inverted reference bounds");
        return new Bounds(new Vector3f(-high.x, low.z, -high.y),
                new Vector3f(-low.x, high.z, -low.y), 0);
    }

    private static double componentError(Vector3f a, Vector3f b) {
        return Math.max(Math.max(Math.abs((double) a.x - b.x), Math.abs((double) a.y - b.y)),
                Math.abs((double) a.z - b.z));
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private void require(boolean condition, String context, String message) {
        if (condition) return;
        JsonObject failure = new JsonObject();
        failure.addProperty("context", context);
        failure.addProperty("message", message);
        failures.add(failure);
    }

    private void exception(String context, Throwable error) {
        JsonObject failure = new JsonObject();
        failure.addProperty("context", context);
        failure.addProperty("exception", error.getClass().getName());
        failure.addProperty("message", String.valueOf(error.getMessage()));
        failures.add(failure);
    }

    private static void countNodes(AssimpNode node, Map<String, Integer> counts) {
        if (node == null) return;
        counts.merge(node.getName(), 1, Integer::sum);
        for (AssimpNode child : node.getChildren()) countNodes(child, counts);
    }

    private static Bounds skinBounds(PoseSnapshot pose) {
        Vector3f low = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f high = new Vector3f(Float.NEGATIVE_INFINITY);
        long count = 0;
        for (var instance : pose.copyMeshInstances()) {
            AssimpMesh mesh = instance.mesh();
            float[] positions = mesh.hasBones() || mesh.hasMorphTargets()
                    ? CpuSkinning.geometry(pose, mesh).positions() : mesh.getVertices();
            for (int index = 0; index < positions.length; index += 3) {
                Vector3f point = new Vector3f(positions[index], positions[index + 1], positions[index + 2]);
                if (!mesh.hasBones()) instance.transform().transformPosition(point);
                if (!point.isFinite()) throw new IllegalStateException("Non-finite skinned vertex: " + mesh.getName());
                low.min(point); high.max(point); count++;
            }
        }
        if (count == 0) throw new IllegalStateException("Pose contains no mesh vertices");
        return new Bounds(low, high, count);
    }

    private JsonObject skinWeights(AssimpScene scene, String targetId) {
        JsonObject result = new JsonObject();
        long vertices = 0, unweighted = 0, invalid = 0;
        int maximumInfluences = 0;
        double maximumSumError = 0;
        Set<String> weightedBones = new HashSet<>();
        for (AssimpMesh mesh : scene.getMeshes()) {
            if (!mesh.hasBones()) continue;
            weightedBones.addAll(mesh.getBoneNames());
            var skin = mesh.getSkinnedData();
            if (skin == null || skin.getVertexCount() == 0) {
                require(false, targetId + "/" + mesh.getName(), "Missing processed skin data");
                continue;
            }
            float[] weights = skin.getBoneWeights();
            int[] indices = skin.getBoneIndices();
            require(weights.length % skin.getVertexCount() == 0 && indices.length == weights.length,
                    targetId + "/" + mesh.getName(), "Invalid skin influence layout");
            int stride = weights.length / skin.getVertexCount();
            for (int vertex = 0; vertex < skin.getVertexCount(); vertex++) {
                double sum = 0;
                int influences = 0;
                for (int offset = 0; offset < stride; offset++) {
                    int index = vertex * stride + offset;
                    float weight = weights[index];
                    if (!Float.isFinite(weight) || weight < 0) { invalid++; continue; }
                    sum += weight;
                    if (weight > 0) {
                        influences++;
                        if (indices[index] < 0 || indices[index] >= mesh.getBoneNames().size()) invalid++;
                    }
                }
                if (sum <= 0) unweighted++;
                maximumSumError = Math.max(maximumSumError, Math.abs(sum - 1));
                maximumInfluences = Math.max(maximumInfluences, influences);
                vertices++;
            }
        }
        require(vertices > 0, targetId, "No imported skinned vertices");
        require(unweighted == 0 && invalid == 0 && maximumSumError <= 1e-5,
                targetId, "Processed skin weights invalid: unweighted=" + unweighted + ", invalid=" + invalid
                        + ", maximumSumError=" + maximumSumError);
        result.addProperty("status", "processed_import_weights_raw_weights_are_checked_by_blender_exporter");
        result.addProperty("vertices", vertices);
        result.addProperty("unweightedVertices", unweighted);
        result.addProperty("invalidInfluences", invalid);
        result.addProperty("maximumWeightSumError", maximumSumError);
        result.addProperty("maximumInfluences", maximumInfluences);
        result.addProperty("weightedBones", weightedBones.size());
        return result;
    }

    private JsonObject sample(AssimpScene scene, AnimationPlayer player, JsonObject reference,
                              Map<String, Matrix4f> bind, Map<AssimpMesh, float[]> zeroMorphs,
                              double phase, Map<String, Integer> nodes, String context) {
        player.synchronizePhase(phase);
        Map<String, Matrix4f> locals = new LinkedHashMap<>(bind);
        locals.putAll(player.getBoneTransforms());
        for (var entry : locals.entrySet())
            if (!entry.getValue().isFinite()) throw new IllegalStateException("Non-finite local: " + entry.getKey());
        PoseSnapshot pose = PoseSnapshot.capture(0, scene, locals, zeroMorphs);
        for (String node : locals.keySet()) {
            Matrix4f global = pose.getNodeTransform(node);
            if (global == null || !global.isFinite()) throw new IllegalStateException("Missing/non-finite global: " + node);
        }
        Bounds actualBounds = skinBounds(pose);
        Bounds expectedBounds = engineBounds(reference);
        double boundsError = Math.max(componentError(actualBounds.minimum, expectedBounds.minimum),
                componentError(actualBounds.maximum, expectedBounds.maximum));
        maximumBoundsError = Math.max(maximumBoundsError, boundsError);
        require(boundsError <= BOUNDS_TOLERANCE, context,
                "Deformed bounds differ from Blender by " + boundsError + " units (tolerance " + BOUNDS_TOLERANCE + ")");
        JsonObject result = new JsonObject();
        result.addProperty("phase", phase);
        result.addProperty("animationTicks", player.getCurrentTime());
        result.add("expectedBounds", expectedBounds.json());
        result.add("actualBounds", actualBounds.json());
        result.addProperty("maximumBoundsComponentError", boundsError);
        JsonObject joints = reference.getAsJsonObject("joints");
        if (joints == null || joints.size() == 0) throw new IllegalArgumentException("Missing Blender joint references");
        JsonObject jointResults = new JsonObject();
        double largestJointError = 0;
        for (var entry : joints.entrySet()) {
            String node = entry.getKey();
            Matrix4f global = pose.getNodeTransform(node);
            require(nodes.getOrDefault(node, 0) == 1, context + "/" + node, "Joint node must resolve exactly once");
            if (global == null || !global.isFinite()) {
                require(false, context + "/" + node, "Missing/non-finite sampled joint");
                continue;
            }
            Vector3f expected = enginePosition(vector(entry.getValue().getAsJsonArray()));
            Vector3f actual = global.getTranslation(new Vector3f());
            double error = actual.distance(expected);
            maximumJointError = Math.max(maximumJointError, error);
            largestJointError = Math.max(largestJointError, error);
            require(error <= POSITION_TOLERANCE, context + "/" + node,
                    "Joint differs from Blender by " + error + " units (tolerance " + POSITION_TOLERANCE + ")");
            JsonObject joint = new JsonObject();
            joint.add("expected", vector(expected));
            joint.add("actual", vector(actual));
            joint.addProperty("distance", error);
            jointResults.add(node, joint);
        }
        result.add("joints", jointResults);
        result.addProperty("maximumJointError", largestJointError);
        return result;
    }

    private JsonObject clip(AssimpScene scene, AssimpAnimation animation, JsonObject expected,
                            Map<String, Integer> nodes, String targetId) {
        String context = targetId + "/" + expected.get("name").getAsString();
        JsonObject result = new JsonObject();
        result.addProperty("name", expected.get("name").getAsString());
        result.addProperty("expectedTakeName", expected.get("expectedTakeName").getAsString());
        result.addProperty("actualTakeName", animation.getName());
        double ticksPerSecond = animation.getTicksPerSecond();
        double seconds = animation.getDuration() / ticksPerSecond;
        double expectedSeconds = expected.get("expectedDurationSeconds").getAsDouble();
        if (!Double.isFinite(ticksPerSecond) || ticksPerSecond <= 0 || !Double.isFinite(seconds) || seconds <= 0
                || !Double.isFinite(expectedSeconds) || expectedSeconds <= 0)
            throw new IllegalArgumentException("Invalid imported or expected clip timing");
        require(Math.abs(seconds - expectedSeconds) <= DURATION_TOLERANCE, context,
                "Duration " + seconds + " differs from expected " + expectedSeconds);
        result.addProperty("ticksPerSecond", ticksPerSecond);
        result.addProperty("durationTicks", animation.getDuration());
        result.addProperty("durationSeconds", seconds);
        result.addProperty("expectedDurationSeconds", expectedSeconds);
        result.addProperty("boneChannels", animation.getChannels().size());
        Set<String> channels = new HashSet<>();
        for (var channel : animation.getChannels()) {
            String node = channel.getBoneName();
            require(nodes.getOrDefault(node, 0) == 1, context + "/channel/" + node,
                    "Imported channel must resolve to exactly one raw node");
            require(channels.add(node), context + "/channel/" + node, "Duplicate animation channel");
        }
        require(!channels.isEmpty(), context, "Clip has no skeletal channels");
        Map<AssimpMesh, float[]> zeroMorphs = new IdentityHashMap<>();
        for (AssimpMesh mesh : scene.getMeshes()) zeroMorphs.put(mesh, new float[mesh.getMorphTargets().size()]);
        Map<String, Matrix4f> bind = SkinBindPose.locals(scene);
        AnimationPlayer player = new AnimationPlayer(animation, scene.getRootNode());
        player.setLooping(false);
        JsonArray references = expected.getAsJsonArray("boundsSamples");
        if (references == null || references.size() != PHASES.length)
            throw new IllegalArgumentException("Expected exactly start/mid/end Blender samples");
        double start = expected.get("startFrame").getAsDouble();
        double end = expected.get("endFrame").getAsDouble();
        if (!Double.isFinite(start) || !Double.isFinite(end) || end <= start)
            throw new IllegalArgumentException("Invalid Blender Action range");
        JsonArray samples = new JsonArray();
        for (int index = 0; index < PHASES.length; index++) {
            JsonObject reference = references.get(index).getAsJsonObject();
            double referencePhase = (reference.get("frame").getAsDouble() - start) / (end - start);
            if (Math.abs(referencePhase - PHASES[index]) > 1e-8)
                throw new IllegalArgumentException("Blender bounds sample does not match phase " + PHASES[index]);
            samples.add(sample(scene, player, reference, bind, zeroMorphs, PHASES[index], nodes,
                    context + "/phase=" + PHASES[index]));
        }
        result.add("samples", samples);
        return result;
    }

    private static boolean takeMatches(String raw, String expected) {
        if (raw.equals(expected)) return true;
        int split = raw.lastIndexOf('|');
        return split > 0 && raw.substring(split + 1).equals(expected);
    }

    private JsonObject target(JsonObject expected, Path directory) throws Exception {
        String id = expected.get("id").getAsString();
        JsonObject result = new JsonObject();
        result.addProperty("id", id);
        Path model = directory.resolve(expected.get("model").getAsString()).normalize();
        if (!model.startsWith(directory) || !Files.isRegularFile(model))
            throw new IllegalArgumentException("Missing/unsafe FBX path: " + model);
        String hash = sha256(model);
        if (!hash.equalsIgnoreCase(expected.get("sha256").getAsString()))
            throw new IllegalStateException("FBX hash differs from Blender manifest: " + model);
        require(Files.size(model) == expected.get("bytes").getAsLong(), id, "FBX size differs from manifest");
        result.addProperty("model", model.toString());
        result.addProperty("sha256", hash);
        AssimpScene scene = new AssimpModelLoader().loadModel(model, ModelImportMode.DYNAMIC);
        require(scene.isDynamic() && scene.getRootNode() != null, id, "Expected imported dynamic scene hierarchy");
        Map<String, Integer> nodes = new HashMap<>();
        countNodes(scene.getRootNode(), nodes);
        for (JsonElement bone : expected.getAsJsonArray("bones")) {
            String name = bone.getAsString();
            require(nodes.getOrDefault(name, 0) == 1, id + "/bone/" + name,
                    "Authored bone must resolve exactly once after FBX import");
        }
        for (AssimpMesh mesh : scene.getMeshes()) for (String bone : mesh.getBoneNames())
            require(nodes.getOrDefault(bone, 0) == 1, id + "/weightedBone/" + bone,
                    "Imported weighted bone must resolve exactly once");
        result.addProperty("meshCount", scene.getMeshes().size());
        result.addProperty("clipCount", scene.getAnimations().size());
        result.add("processedSkinWeights", skinWeights(scene, id));
        JsonArray requested = expected.getAsJsonArray("clips");
        require(requested.size() == expected.get("clipCount").getAsInt(), id, "Manifest clip count is inconsistent");
        require(scene.getAnimations().size() == requested.size(), id,
                "Imported take count " + scene.getAnimations().size() + " differs from expected " + requested.size());
        Set<AssimpAnimation> used = new HashSet<>();
        Set<String> expectedNames = new HashSet<>();
        Set<String> actualNames = new HashSet<>();
        for (AssimpAnimation animation : scene.getAnimations())
            require(actualNames.add(animation.getName()), id, "Duplicate imported take name: " + animation.getName());
        String importerPrefix = null;
        JsonArray clips = new JsonArray();
        for (JsonElement element : requested) {
            JsonObject requestedClip = element.getAsJsonObject();
            String name = requestedClip.get("expectedTakeName").getAsString();
            require(expectedNames.add(name), id, "Duplicate expected take name: " + name);
            List<AssimpAnimation> matches = new ArrayList<>();
            for (AssimpAnimation animation : scene.getAnimations())
                if (takeMatches(animation.getName(), name)) matches.add(animation);
            if (matches.size() != 1) {
                require(false, id + "/" + name, "Expected exactly one matching take, found " + matches.size());
                continue;
            }
            AssimpAnimation animation = matches.get(0);
            require(used.add(animation), id + "/" + name, "Imported take is bound more than once");
            String prefix = animation.getName().substring(0, animation.getName().length() - name.length());
            if (importerPrefix == null) importerPrefix = prefix;
            require(prefix.equals(importerPrefix), id + "/" + name, "Inconsistent importer armature prefix");
            try {
                clips.add(clip(scene, animation, requestedClip, nodes, id));
            } catch (RuntimeException error) {
                exception(id + "/" + name, error);
            }
        }
        for (AssimpAnimation animation : scene.getAnimations())
            require(used.contains(animation), id, "Unexpected imported take: " + animation.getName());
        result.addProperty("takeNamePolicy", "exact expected name, optionally preceded by one consistent importer armature prefix");
        result.addProperty("importerArmaturePrefix", importerPrefix == null ? "" : importerPrefix);
        result.add("clips", clips);
        return result;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: ValidateMotionExports <exports/manifest.json> <report.json>");
        Path manifestPath = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        if (output.equals(manifestPath)) throw new IllegalArgumentException("The validation report must not overwrite the Blender manifest");
        ValidateMotionExports validator = new ValidateMotionExports();
        JsonObject report = new JsonObject();
        report.addProperty("version", 1);
        report.addProperty("manifest", manifestPath.toString());
        report.addProperty("gameplay", "UNVERIFIED_LIVE");
        report.addProperty("blenderToEngine", "[-x,z,-y]");
        report.addProperty("positionTolerance", POSITION_TOLERANCE);
        report.addProperty("boundsComponentTolerance", BOUNDS_TOLERANCE);
        report.addProperty("durationToleranceSeconds", DURATION_TOLERANCE);
        report.addProperty("morphPolicy", "explicit_zero_for_every_imported_mesh");
        JsonArray targets = new JsonArray();
        report.add("targets", targets);
        try {
            JsonObject manifest = JsonParser.parseString(Files.readString(manifestPath)).getAsJsonObject();
            report.addProperty("manifestSha256", sha256(manifestPath));
            if (manifest.get("version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported export manifest version");
            JsonArray expectedTargets = manifest.getAsJsonArray("targets");
            if (expectedTargets == null || expectedTargets.size() == 0)
                throw new IllegalArgumentException("Export manifest has no targets");
            Set<String> ids = new HashSet<>();
            for (JsonElement element : expectedTargets) {
                JsonObject target = element.getAsJsonObject();
                String id = target.get("id").getAsString();
                validator.require(ids.add(id), id, "Duplicate export target ID");
                try {
                    targets.add(validator.target(target, manifestPath.getParent()));
                } catch (Exception | LinkageError error) {
                    validator.exception(id, error);
                }
            }
        } catch (Exception | LinkageError error) {
            validator.exception("manifest", error);
        }
        report.addProperty("status", validator.failures.size() == 0 ? "TESTED_OFFLINE" : "FAILED");
        report.addProperty("maximumJointError", validator.maximumJointError);
        report.addProperty("maximumBoundsComponentError", validator.maximumBoundsError);
        report.add("failures", validator.failures);
        Files.createDirectories(output.getParent());
        Files.writeString(output, JSON.toJson(report) + "\n");
        System.out.println("MOTION_EXPORT_VALIDATION " + report.get("status").getAsString());
        System.out.println("TARGETS " + targets.size() + " FAILURES " + validator.failures.size());
        System.out.println("MAX_JOINT_ERROR " + validator.maximumJointError + " MAX_BOUNDS_ERROR " + validator.maximumBoundsError);
        System.out.println("OUTPUT " + output);
        for (int index = 0; index < Math.min(12, validator.failures.size()); index++)
            System.err.println(JSON.toJson(validator.failures.get(index)));
        if (validator.failures.size() > 0) throw new IllegalStateException("FBX validation failed; see " + output);
    }
}
