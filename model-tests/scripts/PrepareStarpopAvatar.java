package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.animation.ModelInstanceDefinition;
import cn.minerealms.assimploader.animation.PoseGraph;
import cn.minerealms.assimploader.animation.PoseSnapshot;
import cn.minerealms.assimploader.animation.SkinBindPose;
import cn.minerealms.assimploader.animation.physics.SpringBoneModifier;
import cn.minerealms.assimploader.character.solver.CharacterRigDefinition;
import cn.minerealms.assimploader.collision.CapsuleCollider;
import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.core.ModelImportMode;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Authoring utility: calibrates a generated example with the real FBX importer. */
public final class PrepareStarpopAvatar {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String MODEL = "rigworks:models/character/starpop/starpop.fbx";

    private static JsonObject read(Path path) throws Exception {
        try (var reader = Files.newBufferedReader(path)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void write(Path path, JsonObject value) throws Exception {
        Files.writeString(path, JSON.toJson(value) + "\n");
    }

    private static Vector3f vector(JsonElement element) {
        JsonArray value = element.getAsJsonArray();
        if (value.size() != 3) throw new IllegalArgumentException("Expected a three-component point");
        var result = new Vector3f(value.get(0).getAsFloat(), value.get(1).getAsFloat(), value.get(2).getAsFloat());
        if (!result.isFinite()) throw new IllegalArgumentException("Non-finite authoring point");
        return result;
    }

    private static JsonArray vector(Vector3f value) {
        var result = new JsonArray();
        result.add(value.x); result.add(value.y); result.add(value.z);
        return result;
    }

    private static Vector3f enginePoint(JsonElement value) {
        Vector3f point = vector(value);
        return new Vector3f(-point.x, point.z, -point.y);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static JsonArray neutralBounds(AssimpScene scene) {
        var morphs = new java.util.IdentityHashMap<cn.minerealms.assimploader.model.AssimpMesh, float[]>();
        for (var mesh : scene.getMeshes()) morphs.put(mesh, new float[mesh.getMorphTargets().size()]);
        var pose = PoseSnapshot.capture(0, scene, SkinBindPose.locals(scene), morphs);
        Vector3f low = new Vector3f(Float.POSITIVE_INFINITY), high = new Vector3f(Float.NEGATIVE_INFINITY);
        for (var entry : pose.copyMeshInstances()) {
            var mesh = entry.mesh();
            float[] vertices = CpuSkinning.geometry(pose, mesh).positions();
            for (int i = 0; i < vertices.length; i += 3) {
                var point = new Vector3f(vertices[i], vertices[i + 1], vertices[i + 2]);
                require(point.isFinite(), "Non-finite neutral vertex");
                low.min(point); high.max(point);
            }
        }
        require(Math.abs(low.y) < 0.00002f, "Neutral soles are not on the floor: " + low);
        require(Math.abs(high.y - low.y - 1.85f) < 0.00002f, "Neutral height is not 1.85 blocks");
        var bounds = new JsonArray(); bounds.add(vector(low)); bounds.add(vector(high)); return bounds;
    }

    private static List<SpringBoneModifier.Collider> colliderSpecs(JsonObject colliders) {
        var volumes = new ArrayList<SpringBoneModifier.Collider>();
        for (var element : colliders.asMap().values()) {
            var value = element.getAsJsonObject();
            volumes.add(new SpringBoneModifier.Capsule(value.get("node").getAsString(),
                    vector(value.get("start")), vector(value.get("end")), value.get("radius").getAsFloat()));
        }
        return volumes;
    }

    private static JsonObject verifySimulation(AssimpScene scene, JsonObject definition, Path rigFile) throws Exception {
        var report = new JsonObject(); var samples = new JsonArray();
        var bindLocals = SkinBindPose.locals(scene);
        var colliders = colliderSpecs(definition.getAsJsonObject("colliders"));
        CharacterRigDefinition rig;
        try (var reader = Files.newBufferedReader(rigFile)) { rig = CharacterRigDefinition.read(reader); }
        Map<String, Float> maxLag = new LinkedHashMap<>();
        for (String id : definition.getAsJsonObject("springs").keySet()) maxLag.put(id, 0f);
        int maxSubsteps = 0, simulatedTicks = 0;
        float maxPoseTailError = 0;
        for (var clip : scene.getAnimations()) {
            float clipLag = 0, minClearance = Float.POSITIVE_INFINITY;
            String worstLagBone = "", worstContactBone = "";
            try (var instance = new ModelInstanceDefinition(definition).instantiate(scene)) {
                var state = instance.animation();
                rig.install(state);
                require(state.transitionTo(clip.getName(), .15), "Missing exported clip: " + clip.getName());
                for (int tick = 0; tick < 40; tick++) {
                    state.update(0.05f);
                    simulatedTicks++;
                    var pose = state.getPoseSnapshot();
                    var locals = pose.copyLocalTransforms();
                    for (var local : locals.entrySet()) {
                        require(local.getValue().isFinite(), "Invalid local pose: " + clip.getName() + "/" + local.getKey());
                        require(pose.getNodeTransform(local.getKey()).isFinite(), "Invalid global pose: " + local.getKey());
                    }
                    var graph = new PoseGraph(scene.getRootNode(), locals);
                    List<CapsuleCollider> volumes = colliders.stream().map(collider -> collider.at(graph)).toList();
                    for (String id : maxLag.keySet()) {
                        var spring = (SpringBoneModifier) state.getModifier(id);
                        Vector3f tail = spring.getTailPosition();
                        require(tail != null && tail.isFinite(), "Spring did not evaluate: " + id);
                        maxSubsteps = Math.max(maxSubsteps, spring.getLastSubsteps());
                        require(spring.getLastSubsteps() <= 8, "Spring exceeded its work budget");
                        Vector3f posedTail = pose.getNodeTransform(spring.settings().node()).transformPosition(spring.settings().tail());
                        float tailError = posedTail.distance(tail);
                        maxPoseTailError = Math.max(maxPoseTailError, tailError);
                        require(tailError < .00002f, "Spring tail calibration failed: " + id + " in " + clip.getName()
                                + " tick " + tick + " error=" + tailError);
                        Matrix4f neutralLocal = bindLocals.get(spring.settings().node());
                        var baseline = pose.getNodeTransform(spring.settings().node()).mul(graph.local(spring.settings().node()).invert()).mul(neutralLocal);
                        float lag = baseline.transformPosition(spring.settings().tail()).distance(tail);
                        maxLag.compute(id, (key, value) -> Math.max(value, lag));
                        if (lag > clipLag) { clipLag = lag; worstLagBone = spring.settings().node(); }
                        for (var volume : volumes) {
                            float clearance = volume.closestPoint(posedTail).distance(posedTail) - volume.radius() - spring.settings().radius();
                            if (clearance < minClearance) { minClearance = clearance; worstContactBone = spring.settings().node(); }
                        }
                    }
                    for (var mesh : scene.getMeshes()) {
                        for (float weight : pose.getMorphWeights(mesh)) require(weight == 0, "Neutral facial weight was overridden");
                        if (tick % 13 != 0) continue;
                        for (float value : CpuSkinning.geometry(pose, mesh).positions())
                            require(Float.isFinite(value), "Non-finite deformed mesh: " + clip.getName());
                    }
                }
                var item = new JsonObject(); item.addProperty("clip", clip.getName());
                require(minClearance > -.002f, "Deep spring contact: " + clip.getName() + "/" + worstContactBone
                        + " clearance=" + minClearance);
                item.addProperty("maxTipLagMetres", clipLag); item.addProperty("maxTipLagNode", worstLagBone);
                item.addProperty("minParticleClearanceMetres", minClearance); item.addProperty("minClearanceNode", worstContactBone);
                item.addProperty("ticks", 40); samples.add(item);
            }
        }
        var lags = new JsonObject(); maxLag.forEach(lags::addProperty);
        require(maxLag.values().stream().anyMatch(value -> value > 0.0001f), "Secondary motion never moved");
        report.addProperty("simulationSecondsPerClip", 2);
        report.addProperty("simulationTicks", simulatedTicks);
        report.addProperty("maximumSubstepsObserved", maxSubsteps);
        report.addProperty("maximumPoseTailErrorMetres", maxPoseTailError);
        report.add("clips", samples); report.add("maximumTipLagMetres", lags);
        return report;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: PrepareStarpopAvatar <AssetDir> <RigWorksDir>");
        Path directory = Path.of(args[0]).toAbsolutePath().normalize();
        Path repository = Path.of(args[1]).toAbsolutePath().normalize();
        var source = read(directory.resolve("authoring.json"));
        Path model = directory.resolve("starpop.fbx");
        String actualHash = CharacterLocalModelAssets.hash(model);
        String expectedHash = source.get("sha256").getAsString();
        require(actualHash.equals(expectedHash), "Authoring manifest and FBX differ: expected "
                + expectedHash + "; actual " + actualHash + ". Regenerate both before packaging.");
        var scene = new AssimpModelLoader().loadModel(model, ModelImportMode.DYNAMIC);
        var graph = new PoseGraph(scene.getRootNode(), SkinBindPose.locals(scene));
        float maxJointError = 0;
        for (var entry : source.getAsJsonObject("bones").entrySet()) {
            float error = graph.position(entry.getKey()).distance(enginePoint(entry.getValue().getAsJsonObject().get("head")));
            maxJointError = Math.max(maxJointError, error);
            require(error < 0.00002f, "Imported bind joint disagrees with Blender: " + entry.getKey() + " error=" + error);
        }
        var basePath = repository.resolve("src/main/resources/assets/rigworks/models/character/block_player");
        var definition = read(basePath.resolve("block_player.instance.json"));
        definition.addProperty("model", MODEL);
        var springs = new JsonObject();
        for (var entry : source.getAsJsonObject("springInputs").entrySet()) {
            String node = entry.getKey(); var spec = entry.getValue().getAsJsonObject();
            var localTail = graph.global(node).invert().transformPosition(enginePoint(spec.get("tail")));
            var value = new JsonObject(); value.addProperty("node", node); value.add("tail", vector(localTail));
            for (String key : new String[]{"stiffness", "damping", "radius"}) value.add(key, spec.get(key).deepCopy());
            value.add("gravity", vector(new Vector3f(0, -spec.get("gravity").getAsFloat(), 0)));
            value.addProperty("fixedStep", 1d / 120); value.addProperty("maxSubsteps", 8);
            // Reset quickly when a clip moves an anchor by more than its chain can follow.
            value.addProperty("teleportDistance", node.startsWith("Skirt.") ? .085 : .16);
            springs.add("starpop_" + node.replace('.', '_').toLowerCase(Locale.ROOT), value);
        }
        definition.add("springs", springs);
        var colliders = new JsonObject();
        for (var entry : source.getAsJsonObject("colliderInputs").entrySet()) {
            String node = entry.getKey(); var spec = entry.getValue().getAsJsonObject();
            Matrix4f global = graph.global(node), inverse = new Matrix4f(global).invert();
            var value = new JsonObject(); value.addProperty("type", "capsule"); value.addProperty("node", node);
            value.add("start", vector(inverse.transformPosition(enginePoint(spec.get("start")))));
            value.add("end", vector(inverse.transformPosition(enginePoint(spec.get("end")))));
            value.addProperty("radius", spec.get("radius").getAsFloat() / SpringBoneModifier.conservativeScale(global));
            colliders.add(node, value);
        }
        definition.add("colliders", colliders);
        var morphs = new JsonObject();
        for (var mesh : scene.getMeshes()) if (mesh.hasMorphTargets()) {
            var weights = new JsonArray(); for (var target : mesh.getMorphTargets()) weights.add(0);
            morphs.add(mesh.getName(), weights);
        }
        definition.add("morphs", morphs);
        for (String file : new String[]{"profile.json", "rig.json", "equipment.json"}) {
            Files.copy(repository.resolve("model-tests/starpop").resolve(file), directory.resolve(file),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        write(directory.resolve("starpop.instance.json"), definition);
        var report = new JsonObject(); report.addProperty("result", "offline_verified");
        report.addProperty("gameplay", "unverified"); report.addProperty("maximumBindJointErrorMetres", maxJointError);
        report.addProperty("authoringAndModelSha256", actualHash);
        report.add("neutralBounds", neutralBounds(scene));
        report.addProperty("meshes", scene.getMeshes().size()); report.addProperty("materials", scene.getMaterials().size());
        report.addProperty("dynamicBones", springs.size()); report.addProperty("colliders", colliders.size());
        report.add("physics", verifySimulation(scene, definition, directory.resolve("rig.json")));
        write(directory.resolve("runtime-validation.json"), report);
        System.out.println("STARPOP SIDECARS: " + springs.size() + " spring bones, " + colliders.size() + " colliders");
        System.out.println("STARPOP OFFLINE: " + report.get("result") + ", bind joint error=" + maxJointError);
    }
}
