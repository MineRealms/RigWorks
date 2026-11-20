import cn.minerealms.assimploader.animation.AnimationState;
import cn.minerealms.assimploader.animation.PoseGraph;
import cn.minerealms.assimploader.animation.PoseSnapshot;
import cn.minerealms.assimploader.animation.SkinBindPose;
import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.core.ModelImportMode;
import cn.minerealms.assimploader.model.AssimpAnimation;
import cn.minerealms.assimploader.model.AssimpMesh;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.assimploader.rendering.CpuSkinning;
import com.google.gson.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** Offline, format-level inspection. It never claims Minecraft gameplay acceptance. */
public final class InspectAvatar {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    private static JsonArray vector(Vector3f value) {
        var result = new JsonArray(); result.add(value.x); result.add(value.y); result.add(value.z); return result;
    }
    private static JsonObject bounds(PoseSnapshot pose) {
        var low = new Vector3f(Float.POSITIVE_INFINITY); var high = new Vector3f(Float.NEGATIVE_INFINITY);
        for (var instance : pose.copyMeshInstances()) {
            var mesh = instance.mesh();
            // Morph targets also deform meshes without bones.
            var positions = mesh.hasBones() || mesh.hasMorphTargets()
                    ? CpuSkinning.geometry(pose, mesh).positions() : mesh.getVertices();
            for (int i = 0; i < positions.length; i += 3) {
                var point = new Vector3f(positions[i], positions[i + 1], positions[i + 2]);
                if (!mesh.hasBones()) instance.transform().transformPosition(point);
                if (!point.isFinite()) throw new IllegalStateException("Non-finite skinned vertex in " + mesh.getName());
                low.min(point); high.max(point);
            }
        }
        var result = new JsonObject(); result.add("min", vector(low)); result.add("max", vector(high));
        result.addProperty("height", high.y - low.y); return result;
    }
    private static JsonObject sample(PoseSnapshot pose, String node) {
        var result = bounds(pose); var transform = pose.getNodeTransform(node);
        if (transform == null) { result.addProperty("node_status", "unknown"); return result; }
        var position = transform.getTranslation(new Vector3f());
        var rotation = transform.getUnnormalizedRotation(new org.joml.Quaternionf());
        result.add("node_position", vector(position));
        result.add("node_rotation", new JsonArray());
        var q = result.getAsJsonArray("node_rotation"); q.add(rotation.x); q.add(rotation.y); q.add(rotation.z); q.add(rotation.w);
        result.addProperty("node_status", "finite");
        var sums = new JsonObject();
        for (var mesh : pose.getScene().getMeshes()) if (mesh.hasMorphTargets()) {
            double sum = 0; for (float value : pose.getMorphWeights(mesh)) sum += value;
            sums.addProperty(mesh.getName(), sum);
        }
        result.add("sampled_morph_sums", sums); return result;
    }
    private static String sha256(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256"); digest.update(Files.readAllBytes(file));
        var out = new StringBuilder(); for (byte value : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", value)); return out.toString();
    }
    private static void requireFinite(Matrix4f value, String label) {
        if (value == null || !value.isFinite()) throw new IllegalStateException("Missing or non-finite transform: " + label);
    }
    private static JsonObject clip(AssimpAnimation animation, AssimpScene scene, String node) throws Exception {
        var result = new JsonObject(); result.addProperty("name", animation.getName());
        double tps = animation.getTicksPerSecond(), seconds = tps > 0 ? animation.getDuration() / tps : Double.NaN;
        if (!Double.isFinite(tps) || !Double.isFinite(seconds) || tps <= 0 || seconds < 0)
            throw new IllegalStateException("Invalid animation timing: " + animation.getName());
        result.addProperty("ticks_per_second", tps); result.addProperty("duration_ticks", animation.getDuration()); result.addProperty("duration_seconds", seconds);
        result.addProperty("bone_channels", animation.getChannels().size()); result.addProperty("morph_channels", animation.getMorphChannels().size());
        var samples = new JsonArray();
        for (double fraction : new double[]{0, .5, .999}) {
            try (var state = new AnimationState("avatar-validation", scene)) {
                state.initialize();
                boolean selected = state.playAnimation(animation.getName());
                if (!selected) throw new IllegalStateException("Animation disappeared during inspection: " + animation.getName());
                if (Double.isFinite(seconds) && seconds > 0) state.update((float)(seconds * fraction));
                var pose = state.getPoseSnapshot();
                for (var entry : pose.copyLocalTransforms().entrySet()) {
                    requireFinite(entry.getValue(), animation.getName() + "/" + entry.getKey());
                    requireFinite(pose.getNodeTransform(entry.getKey()), animation.getName() + "/global/" + entry.getKey());
                }
                var item = sample(pose, node); item.addProperty("fraction", fraction); samples.add(item);
            }
        }
        result.add("samples", samples); return result;
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: InspectAvatar <Model.fbx/glb> <Node> <Output.json>");
        Path model = Path.of(args[0]).toAbsolutePath().normalize(), output = Path.of(args[2]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(model)) throw new IllegalArgumentException("Model does not exist: " + model);
        var scene = new AssimpModelLoader().loadModel(model, ModelImportMode.DYNAMIC);
        var report = new JsonObject(); report.addProperty("tool_version", 1); report.addProperty("result", "inspected");
        report.addProperty("semantic_status", "unknown_without_profile_or_live_client"); report.addProperty("model", model.toString());
        report.addProperty("sha256", sha256(model)); report.addProperty("source_format", scene.getSourceFormat()); report.addProperty("source_generator", scene.getSourceGenerator());
        report.addProperty("dynamic_scene", scene.isDynamic()); report.addProperty("mesh_count", scene.getMeshes().size()); report.addProperty("material_count", scene.getMaterials().size());
        report.addProperty("clip_count", scene.getAnimations().size());
        boolean knownNode = scene.findNodeByName(args[1]) != null; report.addProperty("sample_node", args[1]); report.addProperty("sample_node_known", knownNode);
        var meshes = new JsonArray(); double unsafe = 0; int morphCount = 0; var bones = new TreeSet<String>();
        for (AssimpMesh mesh : scene.getMeshes()) {
            var item = new JsonObject(); item.addProperty("name", mesh.getName()); item.addProperty("vertices", mesh.getVertexCount()); item.addProperty("triangles", mesh.getFaceCount());
            item.addProperty("bones", mesh.getBoneNames().size()); item.addProperty("morph_count", mesh.getMorphTargets().size()); item.addProperty("has_normals", mesh.hasNormals()); item.addProperty("has_uv0", mesh.hasTexCoords());
            bones.addAll(mesh.getBoneNames());
            var targets = new JsonArray(); double sum = 0, absoluteSum = 0; int active = 0;
            for (var target : mesh.getMorphTargets()) { var t = new JsonObject(); t.addProperty("name", target.getName()); t.addProperty("alias", mesh.getMorphTargetAliases().get(target.getName())); t.addProperty("default", target.getDefaultWeight()); targets.add(t); sum += target.getDefaultWeight(); absoluteSum += Math.abs(target.getDefaultWeight()); if (target.getDefaultWeight() != 0) active++; }
            item.add("morphs", targets); item.addProperty("default_morph_sum", sum); item.addProperty("default_morph_absolute_sum", absoluteSum); item.addProperty("nonzero_morph_defaults", active); item.addProperty("default_morph_status", active > 0 ? "review_authored_semantics" : "all_zero_not_visual_acceptance"); if (mesh.hasMorphTargets()) { morphCount += mesh.getMorphTargets().size(); unsafe += absoluteSum; }
            var aliases = new JsonObject(); mesh.getMorphTargetAliases().forEach(aliases::addProperty); item.add("aliases", aliases); meshes.add(item);
        }
        report.add("meshes", meshes); report.addProperty("morph_target_count", morphCount); report.addProperty("unique_weighted_bones", bones.size()); report.addProperty("nonzero_default_morph_absolute_sum", unsafe);
        var bind = SkinBindPose.locals(scene); var graph = new PoseGraph(scene.getRootNode(), bind); var bindNodes = new JsonObject();
        for (var entry : bind.entrySet()) { requireFinite(entry.getValue(), "bind/" + entry.getKey()); if (entry.getKey().equals(args[1])) bindNodes.add(entry.getKey(), vector(graph.position(entry.getKey()))); }
        report.add("bind_sample", bindNodes);
        report.add("skin_bind_bounds_imported_morph_defaults", bounds(PoseSnapshot.capture(0, scene, bind)));
        var zeroMorphs = new IdentityHashMap<AssimpMesh, float[]>();
        scene.getMeshes().stream().filter(AssimpMesh::hasMorphTargets).forEach(mesh -> zeroMorphs.put(mesh, new float[mesh.getMorphTargets().size()]));
        report.add("skin_bind_bounds_zero_morph_probe", bounds(PoseSnapshot.capture(0, scene, bind, zeroMorphs)));
        var clips = new JsonArray(); for (var animation : scene.getAnimations()) clips.add(clip(animation, scene, args[1])); report.add("clips", clips);
        report.addProperty("checks", "finite transforms, model hash, mesh/morph/clip counts, bind bounds and three samples per clip");
        Files.createDirectories(output.getParent()); Files.writeString(output, JSON.toJson(report) + "\n");
        System.out.println("INSPECTED " + model); System.out.println("SHA256 " + report.get("sha256")); System.out.println("MESHES " + scene.getMeshes().size() + " CLIPS " + scene.getAnimations().size() + " MORPHS " + morphCount + " NONZERO_DEFAULT_ABSOLUTE_SUM " + unsafe); System.out.println("OUTPUT " + output);
    }
}
