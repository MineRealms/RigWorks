package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.animation.ModelInstanceDefinition;
import cn.minerealms.assimploader.animation.AnimationPlayer;
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
import cn.minerealms.rigworks.character.core.CharacterProfile;
import cn.minerealms.rigworks.character.core.PlayerAnimationController;
import cn.minerealms.rigworks.character.core.PlayerVisualInput;
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

    static JsonObject read(Path path) throws Exception {
        try (var reader = Files.newBufferedReader(path)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    static void write(Path path, JsonObject value) throws Exception {
        Files.writeString(path, JSON.toJson(value) + "\n");
    }

    static Vector3f vector(JsonElement element) {
        JsonArray value = element.getAsJsonArray();
        if (value.size() != 3) throw new IllegalArgumentException("Expected a three-component point");
        var result = new Vector3f(value.get(0).getAsFloat(), value.get(1).getAsFloat(), value.get(2).getAsFloat());
        if (!result.isFinite()) throw new IllegalArgumentException("Non-finite authoring point");
        return result;
    }

    static JsonArray vector(Vector3f value) {
        var result = new JsonArray();
        result.add(value.x); result.add(value.y); result.add(value.z);
        return result;
    }

    static Vector3f enginePoint(JsonElement value) {
        Vector3f point = vector(value);
        return new Vector3f(-point.x, point.z, -point.y);
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    static JsonArray neutralBounds(AssimpScene scene) {
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

    static List<SpringBoneModifier.Collider> colliderSpecs(JsonObject colliders) {
        var volumes = new ArrayList<SpringBoneModifier.Collider>();
        for (var element : colliders.asMap().values()) {
            var value = element.getAsJsonObject();
            volumes.add(new SpringBoneModifier.Capsule(value.get("node").getAsString(),
                    vector(value.get("start")), vector(value.get("end")), value.get("radius").getAsFloat()));
        }
        return volumes;
    }

    private static JsonObject verifyMotion(AssimpScene scene, JsonObject source) {
        var report = new JsonObject();
        var motion = source.getAsJsonObject("motionSource");
        var expected = motion.getAsJsonObject("clips");
        var names = scene.getAnimations().stream().map(clip -> clip.getName()).collect(java.util.stream.Collectors.toSet());
        require(names.size() == source.getAsJsonArray("clips").size(), "FBX clip count differs from the authoring manifest");
        for (var name : source.getAsJsonArray("clips")) require(names.contains(name.getAsString()), "Missing FBX take: " + name);
        float maximumError = 0;
        int sampledJoints = 0;
        for (var entry : expected.entrySet()) {
            var clip = scene.getAnimations().stream().filter(value -> value.getName().equals(entry.getKey())).findFirst().orElseThrow();
            var spec = entry.getValue().getAsJsonObject();
            double duration = clip.getDuration() / clip.getTicksPerSecond();
            require(Math.abs(duration - spec.get("durationSeconds").getAsDouble()) < .00001,
                    "VMD duration changed in FBX export: " + entry.getKey() + " duration=" + duration);
            var player = new AnimationPlayer(clip, scene.getRootNode());
            for (var element : spec.getAsJsonArray("samples")) {
                var sample = element.getAsJsonObject();
                player.synchronizePhase(sample.get("seconds").getAsDouble() / duration);
                var locals = new LinkedHashMap<>(SkinBindPose.locals(scene));
                locals.putAll(player.getBoneTransforms());
                var graph = new PoseGraph(scene.getRootNode(), locals);
                for (var joint : sample.getAsJsonObject("joints").entrySet()) {
                    float error = graph.position(joint.getKey()).distance(enginePoint(joint.getValue()));
                    require(error < .00002f, "VMD sample changed after FBX import: " + entry.getKey() + "/" + joint.getKey()
                            + " time=" + sample.get("seconds") + " error=" + error);
                    maximumError = Math.max(maximumError, error);
                    sampledJoints++;
                }
            }
        }
        report.addProperty("source", motion.get("kind").getAsString());
        report.addProperty("vmdClips", expected.size());
        report.addProperty("totalClips", names.size());
        report.addProperty("sampledJoints", sampledJoints);
        report.addProperty("maximumImportedSampleErrorMetres", maximumError);
        return report;
    }

    private static JsonObject verifySelection(AssimpScene scene, JsonObject definition, JsonObject profileJson) {
        var profile = CharacterProfile.parse(profileJson);
        var selections = new JsonObject();
        var cases = new LinkedHashMap<String, PlayerVisualInput>();
        cases.put("idle", PlayerVisualInput.builder(1, .05).build());
        cases.put("walk", PlayerVisualInput.builder(1, .05).motion(4, 0, 0).build());
        cases.put("run", PlayerVisualInput.builder(1, .05).motion(5.6, 0, 0).sprinting(true).build());
        cases.put("crouch", PlayerVisualInput.builder(1, .05).crouching(true).build());
        cases.put("crouch_walk", PlayerVisualInput.builder(1, .05).crouching(true).motion(1.3, 0, 0).build());
        cases.put("jump", PlayerVisualInput.builder(1, .05).jumping(true).onGround(false).motion(0, 0, 3).build());
        cases.put("swim", PlayerVisualInput.builder(1, .05).swimming(true).onGround(false).motion(3, 0, 0).build());
        cases.put("elytra", PlayerVisualInput.builder(1, .05).elytra(true).onGround(false).build());
        for (var entry : cases.entrySet()) {
            try (var instance = new ModelInstanceDefinition(definition).instantiate(scene);
                 var controller = new PlayerAnimationController(profile, instance)) {
                controller.updateCompatible(entry.getValue());
                var selected = controller.lastSelection();
                String expected = profile.animations().get(entry.getKey());
                require(expected.equals(selected.animation()), "Gameplay intent bypassed the profile clip: " + entry.getKey()
                        + " expected=" + expected + " actual=" + selected.animation());
                selections.addProperty(entry.getKey(), selected.animation());
            }
        }
        try (var instance = new ModelInstanceDefinition(definition).instantiate(scene);
             var controller = new PlayerAnimationController(profile, instance)) {
            var use = controller.updateCompatible(PlayerVisualInput.builder(1, .05).usingItem(true).build());
            require(use.leftArm() == 1 && use.rightArm() == 1, "Uncalibrated item use must retain both native arms");
            selections.addProperty("itemUse", "native_arms");
            for (long generation = 1; generation <= 3; generation++) {
                controller.updateCompatible(PlayerVisualInput.builder(generation + 1, (generation + 1) * .05)
                        .motion(4, 0, 0).swinging(true).swingAction(generation, true, .2f).build());
                var layer = instance.animation().getLayer("character:upper-action");
                require(layer != null && layer.getPlayer().getAnimation().getName().equals(profile.animations().get("swing")),
                        "The accepted attack did not select the VMD upper-body layer");
                require(layer.getPlayer().hasSynchronizedPhase(), "Attack clip did not follow the game swing clock");
                require(!layer.getMask().containsKey("Hips") && !layer.getMask().containsKey("Thigh.L"),
                        "Attack layer overrode locomotion");
                double progress = layer.getPlayer().getCurrentTime() / layer.getPlayer().getAnimation().getDuration();
                require(Math.abs(progress - .2) < 1e-6, "Attack phase fell behind the accepted input");
            }
            selections.addProperty("attack", profile.animations().get("swing"));
            selections.addProperty("acceptedAttackRetriggers", 3);
        }
        return selections;
    }

    private static JsonObject verifySimulation(AssimpScene scene, JsonObject definition, Path rigFile) throws Exception {
        var report = new JsonObject(); var samples = new JsonArray(); var failures = new JsonArray();
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
            String worstLagBone = "", worstContactBone = "", worstContact = "";
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
                            if (clearance < minClearance) {
                                minClearance = clearance; worstContactBone = spring.settings().node();
                                worstContact = " tick=" + tick + " tail=" + posedTail + " anchor=" + graph.position(spring.settings().node())
                                        + " capsuleStart=" + volume.start() + " capsuleEnd=" + volume.end() + " capsuleRadius=" + volume.radius();
                            }
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
                if (minClearance <= -.002f) {
                    String failure = "Deep spring contact: " + clip.getName() + "/" + worstContactBone
                            + " clearance=" + minClearance + worstContact;
                    failures.add(failure); System.err.println(failure);
                }
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
        report.addProperty("passed", failures.isEmpty()); report.add("failures", failures);
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
        var motion = source.getAsJsonObject("motionSource");
        var vmdClips = motion.getAsJsonObject("clips");
        var states = new JsonObject();
        for (var clip : scene.getAnimations()) {
            String name = clip.getName();
            var state = new JsonObject(); state.addProperty("clip", name);
            state.addProperty("loop", vmdClips.has(name) ? vmdClips.getAsJsonObject(name).get("loop").getAsBoolean()
                    : !java.util.Set.of("jump", "swing", "dead").contains(name));
            states.add(name, state);
        }
        var profile = read(repository.resolve("model-tests/starpop/profile.json"));
        var mappings = profile.getAsJsonObject("animations");
        if (vmdClips.isEmpty()) {
            for (String intent : new ArrayList<>(mappings.keySet())) {
                if (states.has(intent)) mappings.addProperty(intent, intent);
                else mappings.remove(intent);
            }
            if (states.has("use_item")) mappings.addProperty("use_item", "use_item");
        }
        for (var entry : mappings.entrySet()) {
            String clip = entry.getValue().getAsString();
            require(states.has(clip), "Profile selects an unavailable clip: " + entry.getKey() + " -> " + clip);
            states.add(entry.getKey(), states.get(clip).deepCopy());
        }
        definition.add("states", states);
        for (var foot : definition.getAsJsonObject("footPlacement").asMap().values()) {
            var supported = new JsonArray();
            supported.add(mappings.get("idle").getAsString()); supported.add(mappings.get("crouch").getAsString());
            foot.getAsJsonObject().add("states", supported);
        }
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
        write(directory.resolve("profile.json"), profile);
        for (String file : new String[]{"rig.json", "equipment.json"}) {
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
        report.add("motion", verifyMotion(scene, source));
        report.add("motionSelection", verifySelection(scene, definition, profile));
        var physics = verifySimulation(scene, definition, directory.resolve("rig.json"));
        report.add("physics", physics);
        if (!physics.get("passed").getAsBoolean()) report.addProperty("result", "offline_failed");
        write(directory.resolve("runtime-validation.json"), report);
        require(physics.get("passed").getAsBoolean(), "Spring contact validation failed; see runtime-validation.json");
        System.out.println("STARPOP SIDECARS: " + springs.size() + " spring bones, " + colliders.size() + " colliders");
        System.out.println("STARPOP OFFLINE: " + report.get("result") + ", bind joint error=" + maxJointError);
    }
}
