package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.animation.ModelInstanceDefinition;
import cn.minerealms.assimploader.animation.PoseGraph;
import cn.minerealms.assimploader.animation.PoseSnapshot;
import cn.minerealms.assimploader.animation.SkinBindPose;
import cn.minerealms.assimploader.animation.physics.SpringBoneModifier;
import cn.minerealms.assimploader.character.solver.CharacterRigDefinition;
import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.core.ModelImportMode;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.assimploader.rendering.CpuSkinning;
import cn.minerealms.rigworks.character.core.CharacterProfile;
import cn.minerealms.rigworks.character.core.PlayerActionState;
import cn.minerealms.rigworks.character.core.PlayerVisualInput;
import cn.minerealms.rigworks.character.motion.DefaultMotionController;
import cn.minerealms.rigworks.character.motion.DefaultMotionLibrary;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static cn.minerealms.rigworks.character.client.PrepareStarpopAvatar.*;

/** Calibrates a zero-clip avatar and evaluates the real Java DEFAULT and spring solvers. */
public final class PrepareMisakaAvatar {
    private static final int TICKS = 60;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: PrepareMisakaAvatar <AssetDir> <RigWorksDir>");
        prepare(Path.of(args[0]), Path.of(args[1]), "misaka");
    }

    static void prepare(Path assetDirectory, Path repositoryDirectory, String stem) throws Exception {
        require(stem.matches("[a-z][a-z0-9_-]{0,63}"), "Invalid avatar stem");
        Path directory = assetDirectory.toAbsolutePath().normalize();
        Path repository = repositoryDirectory.toAbsolutePath().normalize();
        var source = read(directory.resolve("authoring.json"));
        require(source.get("model").getAsString().equals(stem + ".fbx"), "Unexpected authoring model filename");
        require(source.getAsJsonArray("clips").isEmpty(), "This avatar must test DEFAULT without baked target clips");
        Path model = directory.resolve(stem + ".fbx");
        String hash = CharacterLocalModelAssets.hash(model);
        require(hash.equals(source.get("sha256").getAsString()), "Authoring manifest and FBX differ; regenerate both");
        var scene = new AssimpModelLoader().loadModel(model, ModelImportMode.DYNAMIC);
        require(scene.getAnimations().isEmpty(), "Unexpected FBX animation; DEFAULT sample must contain zero clips");
        require(scene.hasSkeleton(), "FBX has no skin skeleton");
        require(scene.getMeshes().size() == source.get("meshCount").getAsInt(), "Mesh count changed on import");
        var graph = new PoseGraph(scene.getRootNode(), SkinBindPose.locals(scene));
        float jointError = 0;
        for (var entry : source.getAsJsonObject("bones").entrySet()) {
            require(graph.contains(entry.getKey()), "Missing imported bone: " + entry.getKey());
            float error = graph.position(entry.getKey()).distance(enginePoint(entry.getValue().getAsJsonObject().get("head")));
            jointError = Math.max(jointError, error);
            require(error < .00002f, "Imported skin bind differs from Blender: " + entry.getKey() + " error=" + error);
        }
        var template = repository.resolve("model-tests").resolve(stem);
        var profile = read(template.resolve("profile.json"));
        require(profile.getAsJsonObject("animations").isEmpty(), "DEFAULT sample must not map target clips");
        var parsedProfile = CharacterProfile.parse(profile);
        var head = parsedProfile.bone("head");
        var headForward = graph.global(head.node()).transformDirection(head.referenceRotation().quaternion()
                .transform(parsedProfile.headLook().forward().vector())).normalize();
        var authoringForward = enginePoint(source.get("front")).normalize();
        require(headForward.dot(authoringForward) > .9999f, "Head forward calibration disagrees with the authored face");
        var definition = new JsonObject();
        definition.addProperty("version", 1);
        definition.addProperty("model", profile.get("model").getAsString());
        var performance = new JsonObject(); performance.addProperty("renderInterpolation", true);
        definition.add("performance", performance);
        definition.add("states", new JsonObject());
        var attachments = new JsonObject();
        Map.of("main_hand", "Hand.R", "off_hand", "Hand.L", "head", "Head", "chest", "Chest").forEach((name, node) -> {
            var attachment = new JsonObject(); attachment.addProperty("node", node); attachments.add(name, attachment);
        });
        definition.add("attachments", attachments);
        var springs = new JsonObject();
        for (var entry : source.getAsJsonObject("springInputs").entrySet()) {
            String node = entry.getKey(); var input = entry.getValue().getAsJsonObject();
            require(graph.contains(node), "Missing spring bone: " + node);
            var localTail = graph.global(node).invert().transformPosition(enginePoint(input.get("tail")));
            var spring = new JsonObject(); spring.addProperty("node", node); spring.add("tail", vector(localTail));
            for (String key : List.of("stiffness", "damping", "radius")) spring.add(key, input.get(key).deepCopy());
            spring.add("gravity", vector(new Vector3f(0, -input.get("gravity").getAsFloat(), 0)));
            spring.addProperty("fixedStep", 1d / 120); spring.addProperty("maxSubsteps", 8);
            spring.addProperty("teleportDistance", input.has("teleportDistance") ? input.get("teleportDistance").getAsFloat()
                    : node.startsWith("Skirt.") ? .085f : .16f);
            springs.add(stem + "_" + node.replace('.', '_').toLowerCase(Locale.ROOT), spring);
        }
        require(!springs.isEmpty(), "The sample must contain secondary motion");
        definition.add("springs", springs);
        var colliders = new JsonObject();
        for (var entry : source.getAsJsonObject("colliderInputs").entrySet()) {
            String node = entry.getKey(); var input = entry.getValue().getAsJsonObject();
            require(graph.contains(node), "Missing capsule bone: " + node);
            Matrix4f global = graph.global(node), inverse = new Matrix4f(global).invert();
            var capsule = new JsonObject(); capsule.addProperty("type", "capsule"); capsule.addProperty("node", node);
            capsule.add("start", vector(inverse.transformPosition(enginePoint(input.get("start")))));
            capsule.add("end", vector(inverse.transformPosition(enginePoint(input.get("end")))));
            capsule.addProperty("radius", input.get("radius").getAsFloat() / SpringBoneModifier.conservativeScale(global));
            colliders.add(node, capsule);
        }
        definition.add("colliders", colliders);
        var morphs = new JsonObject(); var defaults = new JsonObject();
        for (var mesh : scene.getMeshes()) if (mesh.hasMorphTargets()) {
            var weights = new JsonArray();
            for (var morph : mesh.getMorphTargets()) { weights.add(0); defaults.addProperty(morph.getName(), 0); }
            morphs.add(mesh.getName(), weights);
        }
        definition.add("morphs", morphs);
        var rig = read(template.resolve("rig.json")); rig.add("defaults", defaults);
        write(directory.resolve("profile.json"), profile);
        write(directory.resolve("rig.json"), rig);
        Files.copy(template.resolve("equipment.json"), directory.resolve("equipment.json"), StandardCopyOption.REPLACE_EXISTING);
        write(directory.resolve(stem + ".instance.json"), definition);

        var report = new JsonObject(); report.addProperty("result", "offline_verified");
        report.addProperty("gameplay", "UNVERIFIED_LIVE"); report.addProperty("motionMode", "DEFAULT");
        report.addProperty("authoringAndModelSha256", hash); report.addProperty("maximumBindJointErrorMetres", jointError);
        report.addProperty("modelYawDegrees", parsedProfile.modelYawDegrees()); report.add("neutralHeadForward", vector(headForward));
        report.add("neutralBounds", neutralBounds(scene)); report.add("skin", verifySkin(scene));
        report.addProperty("meshes", scene.getMeshes().size()); report.addProperty("materials", scene.getMaterials().size());
        report.addProperty("targetClips", scene.getAnimations().size()); report.addProperty("embeddedTextures", scene.getTextures().size());
        report.addProperty("dynamicBones", springs.size()); report.addProperty("colliders", colliders.size());
        report.addProperty("scriptsIncluded", false);
        report.addProperty("builtinMotionCount", DefaultMotionLibrary.builtin().clipNames().size());
        report.addProperty("builtinMotionSharedPoseBytes", DefaultMotionLibrary.builtin().poseArrayBytes());
        var physics = verifyDefaultSimulation(scene, definition, profile, directory.resolve("rig.json"));
        report.add("defaultMotionAndPhysics", physics);
        if (!physics.get("passed").getAsBoolean()) report.addProperty("result", "offline_failed");
        write(directory.resolve("runtime-validation.json"), report);
        require(physics.get("passed").getAsBoolean(), "DEFAULT/spring validation failed; see runtime-validation.json");
        System.out.println(stem.toUpperCase(Locale.ROOT) + " SIDECARS: " + springs.size() + " spring bones, " + colliders.size() + " colliders");
        System.out.println("DEFAULT OFFLINE: zero target clips, zero scripts; bind joint error=" + jointError);
    }

    private static JsonObject verifySkin(AssimpScene scene) {
        int vertices = 0, influences = 0; float maxWeightError = 0;
        for (var mesh : scene.getMeshes()) {
            var skin = mesh.getSkinnedData(); require(skin != null, "Unskinned mesh: " + mesh.getName());
            require(mesh.getBoneNames().size() <= 256, "Skin exceeds the GPU bone palette");
            float[] weights = skin.getBoneWeights(); int stride = weights.length / skin.getVertexCount();
            require(stride <= 4, "Skin contains more than four influences");
            for (int vertex = 0; vertex < skin.getVertexCount(); vertex++) {
                float sum = 0; int count = 0;
                for (int i = 0; i < stride; i++) {
                    float weight = weights[vertex * stride + i];
                    require(Float.isFinite(weight) && weight >= 0, "Invalid skin weight");
                    sum += weight; if (weight > 0) count++;
                }
                maxWeightError = Math.max(maxWeightError, Math.abs(sum - 1)); influences = Math.max(influences, count);
                require(Math.abs(sum - 1) < .00002f, "Unnormalized skin vertex: " + mesh.getName() + "/" + vertex);
                vertices++;
            }
        }
        var report = new JsonObject(); report.addProperty("vertices", vertices); report.addProperty("maximumInfluences", influences);
        report.addProperty("maximumWeightSumError", maxWeightError); return report;
    }

    private static PlayerActionState action(String use, boolean water) {
        return new PlayerActionState("standing", PlayerActionState.AirPhase.GROUNDED,
                false, water, water, false, false, false, 0, use, false, true);
    }

    private record Scenario(String name, String base, String right, String use, DefaultMotionController.Items items) {
        PlayerVisualInput input(int tick) {
            var builder = PlayerVisualInput.builder(tick, tick * .05);
            switch (name) {
                case "walk" -> builder.motion(4, 0, 0);
                case "run" -> builder.motion(5.6, 0, 0).sprinting(true);
                case "crouch" -> builder.crouching(true);
                case "crouch_walk" -> builder.crouching(true).motion(1.3, 0, 0);
                case "jump" -> builder.onGround(false).jumping(true).motion(0, 0, 2);
                case "water_idle" -> builder.actionState(action("none", true));
                case "swimming" -> builder.swimming(true).onGround(false).motion(2, 0, 0);
                case "attack_sword" -> builder.swingAction(tick / 6, true, (tick % 6) / 5f);
                case "bow_draw", "crossbow_draw" -> builder.usingItem(true).actionState(action(use, false));
            }
            return builder.build();
        }
    }

    private static JsonObject verifyDefaultSimulation(AssimpScene scene, JsonObject definition, JsonObject profileJson, Path rigFile) throws Exception {
        var profile = CharacterProfile.parse(profileJson);
        var items = DefaultMotionController.Items.EMPTY;
        var scenarios = List.of(
                new Scenario("idle", "stand", "", "none", items),
                new Scenario("walk", "walk", "", "none", items),
                new Scenario("run", "run", "", "none", items),
                new Scenario("crouch", "crouch", "", "none", items),
                new Scenario("crouch_walk", "crouch_walk", "", "none", items),
                new Scenario("jump", "jump", "", "none", items),
                new Scenario("water_idle", "swim", "", "none", items),
                new Scenario("swimming", "swimming", "", "none", items),
                new Scenario("attack_sword", "stand", "attack_sword", "none", new DefaultMotionController.Items("empty", "sword", "right", 0, false, false)),
                new Scenario("bow_draw", "stand", "bow_draw", "bow", new DefaultMotionController.Items("empty", "bow", "right", .5f, false, false)),
                new Scenario("crossbow_draw", "stand", "crossbow_draw", "crossbow", new DefaultMotionController.Items("empty", "crossbow", "right", .5f, false, false)));
        var bindLocals = SkinBindPose.locals(scene);
        var colliders = colliderSpecs(definition.getAsJsonObject("colliders"));
        CharacterRigDefinition rig;
        try (var reader = Files.newBufferedReader(rigFile)) { rig = CharacterRigDefinition.read(reader); }
        var samples = new JsonArray(); var failures = new JsonArray();
        Map<String, Float> maxLag = new LinkedHashMap<>();
        definition.getAsJsonObject("springs").keySet().forEach(id -> maxLag.put(id, 0f));
        int substeps = 0; float tailError = 0, legError = 0, handError = 0;
        for (var scenario : scenarios) {
            float minClearance = Float.POSITIVE_INFINITY, scenarioLag = 0;
            String worstNode = "";
            try (var instance = new ModelInstanceDefinition(definition).instantiate(scene);
                 var controller = new DefaultMotionController(profile, instance.animation())) {
                rig.install(instance.animation());
                for (int tick = 0; tick < TICKS; tick++) {
                    controller.update(scenario.input(tick), scenario.items());
                    require(!controller.lastSelection().nativeFallback(), "DEFAULT calibration failed: " + controller.lastSelection().diagnostic());
                    require(controller.lastSelection().base().equals(scenario.base()), "Incorrect DEFAULT base: " + scenario.name());
                    require(controller.lastSelection().right().equals(scenario.right()), "Incorrect DEFAULT item action: " + scenario.name());
                    instance.animation().update(.05f);
                    var pose = instance.animation().getPoseSnapshot(); var locals = pose.copyLocalTransforms();
                    require(locals.values().stream().allMatch(Matrix4f::isFinite), "Non-finite DEFAULT local pose: " + scenario.name());
                    var graph = new PoseGraph(scene.getRootNode(), locals);
                    legError = Math.max(legError, controller.maxLegError()); handError = Math.max(handError, controller.maxHandError());
                    require(controller.maxLegError() < .001f, "DEFAULT ankle target error: " + scenario.name());
                    require(controller.maxHandError() < .001f, "DEFAULT hand target error: " + scenario.name());
                    var volumes = colliders.stream().map(collider -> collider.at(graph)).toList();
                    for (String id : maxLag.keySet()) {
                        var spring = (SpringBoneModifier) instance.animation().getModifier(id);
                        var tail = spring.getTailPosition(); require(tail != null && tail.isFinite(), "Unevaluated spring: " + id);
                        substeps = Math.max(substeps, spring.getLastSubsteps());
                        require(spring.getLastSubsteps() <= 8, "Spring exceeded its work budget");
                        var posed = pose.getNodeTransform(spring.settings().node()).transformPosition(spring.settings().tail());
                        float error = posed.distance(tail); tailError = Math.max(tailError, error);
                        require(error < .00002f, "Spring local tail differs from simulated tail: " + id + " error=" + error);
                        var baseline = pose.getNodeTransform(spring.settings().node()).mul(graph.local(spring.settings().node()).invert())
                                .mul(bindLocals.get(spring.settings().node()));
                        float lag = baseline.transformPosition(spring.settings().tail()).distance(tail);
                        maxLag.compute(id, (key, before) -> Math.max(before, lag)); scenarioLag = Math.max(scenarioLag, lag);
                        for (var volume : volumes) {
                            float clearance = volume.closestPoint(posed).distance(posed) - volume.radius() - spring.settings().radius();
                            if (clearance < minClearance) { minClearance = clearance; worstNode = spring.settings().node(); }
                        }
                    }
                    verifyPoseGeometry(scene, pose, scenario.name(), tick);
                }
            }
            var item = new JsonObject(); item.addProperty("scenario", scenario.name()); item.addProperty("ticks", TICKS);
            item.addProperty("base", scenario.base()); item.addProperty("right", scenario.right());
            item.addProperty("maximumTipLagMetres", scenarioLag); item.addProperty("minimumParticleClearanceMetres", minClearance);
            item.addProperty("minimumClearanceNode", worstNode); samples.add(item);
            if (minClearance < -.002f) failures.add("Deep spring contact: " + scenario.name() + "/" + worstNode + " clearance=" + minClearance);
        }
        require(maxLag.values().stream().anyMatch(lag -> lag > .0001f), "Secondary motion never moved");
        var lags = new JsonObject(); maxLag.forEach(lags::addProperty);
        var report = new JsonObject(); report.addProperty("passed", failures.isEmpty()); report.add("failures", failures);
        report.addProperty("simulationTicks", TICKS * scenarios.size()); report.addProperty("secondsPerScenario", TICKS * .05);
        report.addProperty("maximumSubstepsObserved", substeps); report.addProperty("maximumPoseTailErrorMetres", tailError);
        report.addProperty("maximumAnkleTargetErrorMetres", legError); report.addProperty("maximumHandTargetErrorMetres", handError);
        report.add("maximumTipLagMetres", lags); report.add("scenarios", samples); return report;
    }

    private static void verifyPoseGeometry(AssimpScene scene, PoseSnapshot pose, String scenario, int tick) {
        for (var mesh : scene.getMeshes()) {
            for (float weight : pose.getMorphWeights(mesh)) require(weight == 0, "Neutral face was overridden");
            if (tick % 13 != 0) continue;
            for (float coordinate : CpuSkinning.geometry(pose, mesh).positions())
                require(Float.isFinite(coordinate), "Non-finite CPU-skinned vertex: " + scenario);
        }
    }
}
