package cn.minerealms.rigworks.mmd;

import cn.minerealms.rigworks.character.importing.AvatarImportContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Optional adjacent author configuration; all per-character choices remain data. */
public record MmdImportSettings(double height, int sampleRate, int maxFrames,
                                List<Motion> motions, Map<String, String> animations) {
    public record Motion(Path path, String name) {
        public Motion {
            path = path.toAbsolutePath().normalize();
            if (name == null || name.isBlank() || name.length() > 128 || name.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Invalid MMD clip name");
        }
    }
    public MmdImportSettings {
        if (!Double.isFinite(height) || height < .25 || height > 8) throw new IllegalArgumentException("MMD height must be in [0.25,8]");
        if (sampleRate < 15 || sampleRate > 120) throw new IllegalArgumentException("MMD sampleRate must be in [15,120]");
        if (maxFrames < 2 || maxFrames > 18000) throw new IllegalArgumentException("MMD maxFrames must be in [2,18000]");
        motions = List.copyOf(motions);
        animations = Map.copyOf(animations);
        if (motions.size() > 64 || motions.stream().map(Motion::name).distinct().count() != motions.size())
            throw new IllegalArgumentException("MMD import accepts up to 64 uniquely named motions");
    }

    public static MmdImportSettings read(AvatarImportContext source) throws IOException {
        JsonObject config = new JsonObject();
        if (source.sourcePath() != null) {
            String name = source.name();
            int dot = name.lastIndexOf('.');
            Path sidecar = source.sourcePath().resolveSibling((dot < 0 ? name : name.substring(0, dot)) + ".mmd-import.json");
            if (Files.exists(sidecar)) {
                source.resolve(sidecar.getFileName().toString());
                try (var input = Files.newInputStream(sidecar)) {
                    byte[] bytes = input.readNBytes(65537);
                    if (bytes.length > 65536) throw new IOException("MMD import settings exceed 64 KiB");
                    config = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                } catch (RuntimeException invalid) { throw new IOException("Invalid MMD import settings: " + sidecar, invalid); }
            }
        }
        try {
            if (!Set.of("height", "sampleRate", "maxFrames", "motions", "animations").containsAll(config.keySet()))
                throw new IllegalArgumentException("Unknown MMD import setting");
            var motions = new ArrayList<Motion>();
            if (config.has("motions")) {
                for (var value : config.getAsJsonArray("motions")) {
                    var entry = value.getAsJsonObject();
                    if (!Set.of("path", "name").containsAll(entry.keySet()) || !entry.has("path"))
                        throw new IllegalArgumentException("Expected MMD motion path and optional name");
                    Path path = source.resolve(entry.get("path").getAsString());
                    requireVmd(path);
                    motions.add(new Motion(path, entry.has("name") ? entry.get("name").getAsString() : stem(path)));
                }
            } else if (source.sourcePath() != null) {
                try (var files = Files.list(source.sourcePath().getParent())) {
                    for (Path file : files.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".vmd"))
                            .sorted(Comparator.comparing(path -> path.getFileName().toString())).limit(65).toList())
                        motions.add(new Motion(source.resolve(file.getFileName().toString()), stem(file)));
                }
            }
            var animations = new LinkedHashMap<String, String>();
            if (config.has("animations")) config.getAsJsonObject("animations").entrySet()
                    .forEach(entry -> animations.put(entry.getKey(), entry.getValue().getAsString()));
            return new MmdImportSettings(config.has("height") ? config.get("height").getAsDouble() : 1.85,
                    integer(config, "sampleRate", 60), integer(config, "maxFrames", 18000), motions, animations);
        } catch (RuntimeException invalid) { throw new IOException("Invalid MMD import settings for " + source.name() + ": " + invalid.getMessage(), invalid); }
    }

    public JsonObject options(List<Motion> stagedMotions) {
        var json = new JsonObject();
        json.addProperty("height", height); json.addProperty("sampleRate", sampleRate); json.addProperty("maxFrames", maxFrames);
        var entries = new JsonArray();
        for (var motion : stagedMotions) {
            var value = new JsonObject(); value.addProperty("path", motion.path().toString()); value.addProperty("name", motion.name()); entries.add(value);
        }
        json.add("motions", entries);
        var mapping = new JsonObject(); animations.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> mapping.addProperty(entry.getKey(), entry.getValue()));
        json.add("animations", mapping);
        return json;
    }
    private static int integer(JsonObject root, String key, int fallback) {
        if (!root.has(key)) return fallback;
        double number = root.get(key).getAsDouble();
        if (!Double.isFinite(number) || number != Math.rint(number)) throw new IllegalArgumentException(key + " must be an integer");
        return Math.toIntExact((long) number);
    }
    private static void requireVmd(Path path) throws IOException {
        if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".vmd")) throw new IOException("Expected a VMD motion: " + path);
    }
    private static String stem(Path path) { String name = path.getFileName().toString(); return name.substring(0, name.length() - 4); }
}
