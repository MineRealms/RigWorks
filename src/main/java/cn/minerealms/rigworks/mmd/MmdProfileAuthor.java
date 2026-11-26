package cn.minerealms.rigworks.mmd;

import cn.minerealms.rigworks.character.core.CharacterEquipmentDefinition;
import cn.minerealms.rigworks.character.core.CharacterProfile;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Writes ordinary author sidecars, including explicit MMD arm neutral calibration. */
public final class MmdProfileAuthor {
    private record Bone(String name, String source, Vector3f position) {}
    private static final Map<String, List<String>> ALIASES = Map.ofEntries(
            Map.entry("hips", List.of("\u4e0b\u534a\u8eab", "hips", "pelvis")),
            Map.entry("spine", List.of("\u4e0a\u534a\u8eab", "spine")),
            Map.entry("chest", List.of("\u4e0a\u534a\u8eab2", "\u4e0a\u534a\u8eab\uff12", "chest")),
            Map.entry("neck", List.of("\u9996", "neck")), Map.entry("head", List.of("\u982d", "head")),
            Map.entry("left_shoulder", List.of("\u5de6\u80a9", "leftshoulder")),
            Map.entry("right_shoulder", List.of("\u53f3\u80a9", "rightshoulder")),
            Map.entry("left_upper_arm", List.of("\u5de6\u8155", "leftarm", "leftupperarm")),
            Map.entry("right_upper_arm", List.of("\u53f3\u8155", "rightarm", "rightupperarm")),
            Map.entry("left_forearm", List.of("\u5de6\u3072\u3058", "\u5de6\u8098", "leftelbow", "leftforearm")),
            Map.entry("right_forearm", List.of("\u53f3\u3072\u3058", "\u53f3\u8098", "rightelbow", "rightforearm")),
            Map.entry("left_hand", List.of("\u5de6\u624b\u9996", "leftwrist", "lefthand")),
            Map.entry("right_hand", List.of("\u53f3\u624b\u9996", "rightwrist", "righthand")),
            Map.entry("left_thigh", List.of("\u5de6\u8db3", "leftleg", "leftthigh")),
            Map.entry("right_thigh", List.of("\u53f3\u8db3", "rightleg", "rightthigh")),
            Map.entry("left_shin", List.of("\u5de6\u3072\u3056", "\u5de6\u819d", "leftknee", "leftshin")),
            Map.entry("right_shin", List.of("\u53f3\u3072\u3056", "\u53f3\u819d", "rightknee", "rightshin")),
            Map.entry("left_foot", List.of("\u5de6\u8db3\u9996", "leftankle", "leftfoot")),
            Map.entry("right_foot", List.of("\u53f3\u8db3\u9996", "rightankle", "rightfoot")));

    private MmdProfileAuthor() {}

    public static void write(Path directory, JsonObject metadata, MmdImportSettings settings, String identity) throws IOException {
        String base = "rigworks:models/imported/mmd/" + identity + "/";
        var bones = bindings(metadata);
        var actions = new LinkedHashMap<String, String>();
        String neutral = null;
        if (metadata.has("clips")) for (var entry : metadata.getAsJsonArray("clips")) {
            String name = entry.getAsJsonObject().get("name").getAsString();
            if (name.equals("mmd_neutral")) { neutral = name; continue; }
            String intent = intent(name);
            if (intent != null) actions.putIfAbsent(intent, name);
        }
        if (neutral != null) actions.putIfAbsent("idle", neutral);
        actions.putAll(settings.animations());
        var available = new java.util.HashSet<String>();
        if (metadata.has("clips")) metadata.getAsJsonArray("clips").forEach(value -> available.add(value.getAsJsonObject().get("name").getAsString()));
        for (var value : actions.entrySet()) if (!available.contains(value.getValue()))
            throw new IOException("MMD action " + value.getKey() + " refers to missing clip: " + value.getValue());
        // Native GLB uses Saba's reflected basis; AssimpLoader reflects GLB Z once more.
        var orientation = CharacterProfile.ModelOrientation.FORWARD_MINUS_Z;
        var profile = new CharacterProfile(base + "model.glb", 1, bones, actions,
                base + "physics.json", .12, .05, orientation.headLook(), base + "rig.json",
                orientation.modelYawDegrees(), base + "equipment.json");
        var rig = metadata.has("rig") ? metadata.getAsJsonObject("rig").deepCopy() : new JsonObject();
        rig.addProperty("version", 1);
        reflectRig(rig);
        var physics = metadata.has("physics") ? metadata.getAsJsonObject("physics").deepCopy() : new JsonObject();
        physics.addProperty("version", 1);
        reflectPhysics(physics);
        compactNumbers(rig);
        compactNumbers(physics);
        MmdAssetCache.writeAtomic(directory.resolve("rig.json"), rig.toString());
        MmdAssetCache.writeAtomic(directory.resolve("physics.json"), physics.toString());
        MmdAssetCache.writeAtomic(directory.resolve("equipment.json"), CharacterEquipmentDefinition.defaults(profile).toJson().toString());
        MmdAssetCache.writeAtomic(directory.resolve("profile.json"), profile.toJson().toString());
        var index = new JsonObject(); index.addProperty("version", 1); index.addProperty("model", profile.model());
        var resources = new JsonObject();
        for (String file : List.of("profile.json", "equipment.json", "rig.json", "physics.json")) resources.addProperty(base + file, file);
        index.add("resources", resources);
        MmdAssetCache.writeAtomic(directory.resolve("model.resources.json"), index.toString());
    }

    public static Map<String, CharacterProfile.HumanoidBone> bindings(JsonObject metadata) {
        var candidates = new java.util.ArrayList<Bone>();
        for (var value : metadata.getAsJsonArray("bones")) {
            var bone = value.getAsJsonObject(); var position = bone.getAsJsonArray("position");
            candidates.add(new Bone(bone.get("name").getAsString(), bone.has("sourceName") ? bone.get("sourceName").getAsString() : bone.get("name").getAsString(),
                    new Vector3f(position.get(0).getAsFloat(), position.get(1).getAsFloat(), -position.get(2).getAsFloat())));
        }
        var chosen = new LinkedHashMap<String, Bone>();
        ALIASES.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(role -> {
            for (String alias : role.getValue()) {
                var found = candidates.stream().filter(bone -> normalized(bone.source()).equals(normalized(alias))).findFirst();
                if (found.isPresent()) { chosen.put(role.getKey(), found.get()); break; }
            }
        });
        var result = new LinkedHashMap<String, CharacterProfile.HumanoidBone>();
        chosen.forEach((role, bone) -> {
            var correction = new Quaternionf();
            if (role.endsWith("_upper_arm")) {
                Bone elbow = chosen.get(role.replace("_upper_arm", "_forearm"));
                if (elbow != null) {
                    var direction = new Vector3f(elbow.position()).sub(bone.position());
                    if (direction.lengthSquared() > 1e-8f) correction.rotationTo(direction.normalize(), new Vector3f(0, -1, 0));
                }
            }
            result.put(role, new CharacterProfile.HumanoidBone(bone.name(), CharacterProfile.Rotation.IDENTITY,
                    new CharacterProfile.Rotation(correction.x, correction.y, correction.z, correction.w)));
        });
        String root = "RigWorks_MMD";
        if (metadata.has("rootNode")) root = metadata.get("rootNode").getAsString();
        result.put("root", new CharacterProfile.HumanoidBone(root, CharacterProfile.Rotation.IDENTITY));
        return result;
    }

    private static String normalized(String value) { return value.toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "").replace("-", ""); }
    private static String intent(String clip) {
        String name = normalized(clip);
        if (name.startsWith("mc")) name = name.substring(2);
        return switch (name) {
            case "neutral", "mmdneutral", "stand", "idle", "wait" -> "idle";
            case "walk", "walking" -> "walk"; case "run", "running", "sprint" -> "run";
            case "crouch", "sneak", "crouchidle" -> "crouch"; case "crouchwalk", "sneakwalk" -> "crouch_walk";
            case "attack", "swing" -> "swing"; case "jump" -> "jump"; case "fall" -> "fall";
            case "swim" -> "swim"; case "fly" -> "fly"; case "elytra" -> "elytra";
            case "ride", "riding" -> "ride"; case "sleep" -> "sleep"; case "dead", "death" -> "dead";
            default -> null;
        };
    }
    static void reflectPhysics(JsonObject root) {
        if (root.has("gravity")) reflectVector(root.getAsJsonArray("gravity"));
        if (root.has("bodies")) for (var value : root.getAsJsonArray("bodies")) {
            var body = value.getAsJsonObject(); reflectVector(body.getAsJsonArray("position")); reflectQuaternion(body.getAsJsonArray("rotation"));
        }
        if (root.has("joints")) for (var value : root.getAsJsonArray("joints")) {
            var joint = value.getAsJsonObject();
            for (String key : List.of("positionA", "positionB")) reflectVector(joint.getAsJsonArray(key));
            for (String key : List.of("rotationA", "rotationB")) reflectQuaternion(joint.getAsJsonArray(key));
            reflectInterval(joint, "linearLower", "linearUpper", 2);
            reflectInterval(joint, "angularLower", "angularUpper", 0); reflectInterval(joint, "angularLower", "angularUpper", 1);
        }
    }
    static void reflectRig(JsonObject root) {
        if (root.has("boneMorphs")) for (var entry : root.getAsJsonObject("boneMorphs").entrySet())
            for (var value : entry.getValue().getAsJsonArray()) {
                var bone = value.getAsJsonObject();
                if (bone.has("translation")) reflectVector(bone.getAsJsonArray("translation"));
                if (bone.has("rotation")) reflectQuaternion(bone.getAsJsonArray("rotation"));
            }
    }
    private static void reflectVector(JsonArray value) { if (value != null) value.set(2, new com.google.gson.JsonPrimitive(-value.get(2).getAsFloat())); }
    private static void reflectQuaternion(JsonArray value) {
        if (value == null) return;
        value.set(0, new com.google.gson.JsonPrimitive(-value.get(0).getAsFloat())); value.set(1, new com.google.gson.JsonPrimitive(-value.get(1).getAsFloat()));
    }
    private static void reflectInterval(JsonObject root, String lower, String upper, int axis) {
        if (!root.has(lower) || !root.has(upper)) return;
        var a = root.getAsJsonArray(lower); var b = root.getAsJsonArray(upper);
        float first = a.get(axis).getAsFloat(); a.set(axis, new com.google.gson.JsonPrimitive(-b.get(axis).getAsFloat())); b.set(axis, new com.google.gson.JsonPrimitive(-first));
    }
    private static com.google.gson.JsonElement compactNumbers(com.google.gson.JsonElement value) {
        if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            for (String key : List.copyOf(object.keySet())) object.add(key, compactNumbers(object.get(key)));
        } else if (value.isJsonArray()) {
            var array = value.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) array.set(i, compactNumbers(array.get(i)));
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            // Micrometre precision avoids bloating shared physics with float-to-double tails.
            return new com.google.gson.JsonPrimitive(new java.math.BigDecimal(value.getAsString())
                    .setScale(6, java.math.RoundingMode.HALF_EVEN).stripTrailingZeros());
        }
        return value;
    }
}
