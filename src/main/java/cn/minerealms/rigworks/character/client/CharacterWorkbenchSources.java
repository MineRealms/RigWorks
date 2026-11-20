package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.assimploader.resource.LocalModelResources;
import cn.minerealms.assimploader.util.ResourceLocations;
import cn.minerealms.rigworks.character.core.CharacterEquipmentDefinition;
import cn.minerealms.rigworks.character.core.CharacterProfile;
import cn.minerealms.rigworks.character.core.CharacterVisualConfig;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/** Captures authored character definitions on the import worker, before a new copy receives its identity. */
final class CharacterWorkbenchSources {
    record Selection(String profileId, CharacterVisualConfig visual) {}
    @FunctionalInterface interface JsonSource { JsonObject read(String reference) throws IOException; }

    private CharacterWorkbenchSources() {}

    static Selection load(AssimpScene scene, ResourceManager resources, String model, String hash) throws IOException {
        if (scene.getSourcePath() != null) return loadLocal(scene.getSourcePath(), model, hash);
        var source = scene.getSourceResource();
        if (source == null) return null;
        var profile = resourceProfile(resources, source);
        if (profile == null) return null;
        var visual = capture(readResource(resources, profile), model, hash,
                reference -> readResource(resources, ResourceLocations.parse(reference)));
        return new Selection(profile.toString(), visual);
    }

    static Selection loadLocal(Path model, String targetModel, String hash) throws IOException {
        var resources = LocalModelResources.open(model);
        Path profileFile = model.toAbsolutePath().normalize().resolveSibling("profile.json");
        if (!Files.isRegularFile(profileFile, LinkOption.NOFOLLOW_LINKS)) {
            profileFile = null;
            for (var entry : resources.aliases().entrySet().stream()
                    .filter(entry -> entry.getKey().getPath().endsWith("/profile.json"))
                    .sorted(java.util.Map.Entry.comparingByKey()).toList()) {
                var candidate = resources.json(entry.getValue());
                if (matches(candidate, resources.originalModel().map(Object::toString).orElse(null), model)) {
                    if (profileFile != null) throw new IOException("Multiple character profiles reference this imported model");
                    profileFile = entry.getValue();
                }
            }
        }
        if (profileFile == null) return null;
        var json = resources.json(profileFile);
        if (!matches(json, resources.originalModel().map(Object::toString).orElse(null), model)) return null;
        var sourceModel = ResourceLocations.parse(json.get("model").getAsString());
        resources.originalModel(sourceModel);
        Path selected = profileFile;
        var visual = capture(json, targetModel, hash, reference -> resources.json(resources.resolve(reference, selected)));
        String profileId = resources.aliases().entrySet().stream().filter(entry -> entry.getValue().equals(selected))
                .map(entry -> entry.getKey().toString()).sorted().findFirst().orElseGet(() ->
                        sibling(sourceModel, "profile.json").toString());
        return new Selection(profileId, visual);
    }

    static Set<ResourceLocation> dependencies(AssimpScene scene, ResourceManager resources) throws IOException {
        var source = scene.getSourceResource();
        if (source == null) return Set.of();
        var location = resourceProfile(resources, source);
        if (location == null) return Set.of();
        var profile = CharacterProfile.parse(readResource(resources, location));
        var result = new LinkedHashSet<ResourceLocation>();
        result.add(location);
        for (String reference : new String[]{profile.equipmentDefinition(), profile.solverDefinition(), profile.physicsDefinition()})
            if (reference != null) result.add(ResourceLocations.parse(reference));
        return Set.copyOf(result);
    }

    static CharacterVisualConfig capture(JsonObject definition, String model, String hash, JsonSource source) throws IOException {
        try {
            var imported = definition.deepCopy();
            imported.addProperty("model", model);
            var profile = CharacterProfile.parse(imported);
            var equipment = profile.equipmentDefinition() == null ? CharacterEquipmentDefinition.defaults(profile)
                    : CharacterEquipmentDefinition.read(new StringReader(source.read(profile.equipmentDefinition()).toString()));
            String rig = profile.solverDefinition() == null ? null
                    : CharacterVisualConfig.canonicalResource(source.read(profile.solverDefinition()).toString());
            String physics = profile.physicsDefinition() == null ? null
                    : CharacterVisualConfig.canonicalResource(source.read(profile.physicsDefinition()).toString());
            return new CharacterVisualConfig(profile, equipment, CharacterVisualConfig.Transform.IDENTITY, hash, rig, physics);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid imported character configuration: " + invalid.getMessage(), invalid);
        }
    }

    static CharacterVisualConfig rebind(CharacterVisualConfig visual,String model){
        var json=CharacterVisualConfig.parseBounded(visual.json()).getAsJsonObject();
        json.getAsJsonObject("profile").addProperty("model",model);
        return CharacterVisualConfig.parse(json.toString());
    }

    private static boolean matches(JsonObject profile, String originalModel, Path model) {
        if (!profile.has("model") || !profile.get("model").isJsonPrimitive()) return false;
        String reference = profile.get("model").getAsString();
        if (originalModel != null) return originalModel.equals(reference);
        var resource = ResourceLocations.parse(reference);
        return resource.getPath().substring(resource.getPath().lastIndexOf('/') + 1)
                .equals(model.getFileName().toString());
    }

    private static ResourceLocation resourceProfile(ResourceManager resources, ResourceLocation model) throws IOException {
        var adjacent = sibling(model, "profile.json");
        if (resources.getResource(adjacent).isPresent() && references(readResource(resources, adjacent), model)) return adjacent;
        ResourceLocation selected = null;
        var candidates = resources.listResources("models", location -> location.getPath().endsWith("/profile.json"));
        if (candidates.size() > 4096) throw new IOException("Too many character profile resources to inspect");
        for (var location : candidates.keySet().stream().sorted().toList()) {
            if (!references(readResource(resources, location), model)) continue;
            if (selected != null) throw new IOException("Multiple character profiles reference " + model + "; import its profile folder explicitly");
            selected = location;
        }
        return selected;
    }

    private static boolean references(JsonObject profile, ResourceLocation model) {
        return profile.has("model") && profile.get("model").isJsonPrimitive()
                && model.toString().equals(profile.get("model").getAsString());
    }

    private static ResourceLocation sibling(ResourceLocation model, String name) {
        String path = model.getPath();
        return ResourceLocations.of(model.getNamespace(), path.substring(0, path.lastIndexOf('/') + 1) + name);
    }

    private static JsonObject readResource(ResourceManager resources, ResourceLocation location) throws IOException {
        try (var input = resources.open(location)) {
            byte[] bytes = input.readNBytes(CharacterVisualConfig.MAX_BYTES + 1);
            if (bytes.length > CharacterVisualConfig.MAX_BYTES) throw new IOException("Character JSON exceeds 128 KiB: " + location);
            return CharacterVisualConfig.parseBounded(StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()).getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid character JSON: " + location, invalid);
        }
    }
}
