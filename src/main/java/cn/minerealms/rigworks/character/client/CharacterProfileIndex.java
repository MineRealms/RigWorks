package cn.minerealms.rigworks.character.client;

import cn.minerealms.rigworks.character.core.CharacterVisualConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Small, file-only registry for locally saved RigWorks profile IDs.
 *
 * The visual configuration is still authoritative: an ID is returned only when
 * its content-addressed character JSON exists and parses successfully.  The
 * index is only a discoverability hint and can therefore be rebuilt safely.
 */
final class CharacterProfileIndex {
    private static final String INDEX = "profiles.json";
    private static final int MAX_PROFILES = 256;
    private static final int MAX_INDEX_BYTES = 64 * 1024;
    private final Path root;
    private final Path index;

    CharacterProfileIndex(Path configRigWorksRoot) {
        if (configRigWorksRoot == null) throw new IllegalArgumentException("Profile index root is required");
        root = configRigWorksRoot.toAbsolutePath().normalize();
        index = root.resolve(INDEX);
        if (!index.startsWith(root)) throw new IllegalArgumentException("Profile index escapes its root");
    }

    /**
     * Reads valid indexed IDs and merges legacy hints supplied by the caller.
     * Hints are accepted only when their saved visual JSON is present and valid.
     */
    synchronized List<String> profiles(Collection<String> knownProfiles) {
        IndexState state = readIndex();
        Set<String> candidates = new TreeSet<>(state.ids());
        if (knownProfiles != null) {
            for (String profile : knownProfiles) {
                if (validProfileId(profile)) candidates.add(profile);
            }
        }
        Set<String> valid = new TreeSet<>();
        for (String profile : candidates) {
            if (valid.size() >= MAX_PROFILES) break;
            if (validConfig(profile)) valid.add(profile);
        }
        // An active profile supplied as a legacy hint is enough to rebuild a missing
        // index. The index is only a discoverability cache, so replacing a corrupt
        // cache with the validated IDs cannot alter the authoritative profile JSON.
        if ((state.readable() || !valid.isEmpty()) && !valid.equals(new TreeSet<>(state.ids()))) {
            try { writeIndex(valid); }
            catch (IOException ignored) { /* listing must remain usable when the index is read-only */ }
        }
        return List.copyOf(valid);
    }

    /** Records a profile after its configuration has been written. Repeated calls are cheap. */
    synchronized void remember(String profile) throws IOException {
        if (!validProfileId(profile)) throw new IOException("Invalid RigWorks profile ID");
        if (!validConfig(profile)) throw new IOException("Saved RigWorks profile is missing or invalid: " + profile);
        IndexState state = readIndex();
        Set<String> next = new TreeSet<>(state.ids());
        if (!next.add(profile)) {
            if (!state.readable()) writeIndex(next);
            return;
        }
        if (next.size() > MAX_PROFILES) throw new IOException("At most " + MAX_PROFILES + " local character profiles");
        writeIndex(next);
    }

    /** Removes one saved configuration; model files and other copies are separate assets. */
    synchronized Removal remove(String profile) throws IOException { return remove(profile, List.of()); }

    synchronized Removal remove(String profile, Collection<String> knownProfiles) throws IOException {
        if (!validProfileId(profile)) throw new IOException("Invalid RigWorks profile ID");
        CharacterVisualConfig removed = configuration(profile);
        Path file = profileFile(profile);
        confined(file);
        Files.deleteIfExists(file);
        IndexState state = readIndex();
        Set<String> next = new TreeSet<>(state.ids());
        if (next.remove(profile)) writeIndex(next);
        Set<String> remaining = new TreeSet<>(profiles(knownProfiles));
        return new Removal(profile, removed == null ? null : removed.profile().model(), Set.copyOf(remaining), modelReferences());
    }

    synchronized Set<String> modelReferences() throws IOException {
        Set<String> models = new TreeSet<>();
        Path characters = root.resolve("characters");
        confined(characters);
        // A valid legacy copy may still need its profile index rebuilt.
        if (Files.isDirectory(characters, LinkOption.NOFOLLOW_LINKS)) try (var files = Files.newDirectoryStream(characters, "*.json")) {
            for (Path saved : files) {
                var config = configurationFile(saved);
                if (config != null) models.add(config.profile().model());
            }
        }
        return Set.copyOf(models);
    }

    record Removal(String profile, String model, Set<String> profiles, Set<String> modelReferences) {
        boolean keepAsset(String id) {
            if (id.equals("rigworks:workbench_sources/" + CharacterVisualConfig.hash(profile.getBytes(StandardCharsets.UTF_8)))
                    && !modelReferences.contains(id))
                return false;
            return !(id.equals(model) || id.startsWith("rigworks:models/imported/ysm/")) || modelReferences.contains(id);
        }
    }

    private boolean validConfig(String profile) { return configuration(profile) != null; }

    private CharacterVisualConfig configuration(String profile) { return configurationFile(profileFile(profile)); }

    private CharacterVisualConfig configurationFile(Path file) {
        try {
            confined(file);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > CharacterVisualConfig.MAX_BYTES)
                return null;
            return CharacterVisualConfig.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private IndexState readIndex() {
        try {
            confined(index);
            if (!Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS) || Files.size(index) > MAX_INDEX_BYTES)
                return new IndexState(Set.of(), false);
            var value = CharacterVisualConfig.parseBounded(Files.readString(index, StandardCharsets.UTF_8));
            if (!value.isJsonObject()) return new IndexState(Set.of(), false);
            JsonObject object = value.getAsJsonObject();
            if (!object.has("version") || !object.get("version").isJsonPrimitive()
                    || !object.getAsJsonPrimitive("version").isNumber()
                    || object.get("version").getAsDouble() != 1 || !object.has("profiles")
                    || !object.get("profiles").isJsonArray()) return new IndexState(Set.of(), false);
            JsonArray array = object.getAsJsonArray("profiles");
            if (array.size() > MAX_PROFILES) return new IndexState(Set.of(), false);
            Set<String> ids = new TreeSet<>();
            for (var entry : array) {
                if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) continue;
                String profile = entry.getAsString();
                if (validProfileId(profile)) ids.add(profile);
            }
            return new IndexState(ids, true);
        } catch (IOException | RuntimeException ignored) {
            return new IndexState(Set.of(), false);
        }
    }

    private void writeIndex(Set<String> profiles) throws IOException {
        if (profiles.size() > MAX_PROFILES) throw new IOException("At most " + MAX_PROFILES + " local character profiles");
        Files.createDirectories(root);
        confined(index);
        JsonObject object = new JsonObject();
        object.addProperty("version", 1);
        JsonArray array = new JsonArray();
        profiles.stream().sorted(Comparator.naturalOrder()).forEach(array::add);
        object.add("profiles", array);
        String encoded = object.toString();
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_INDEX_BYTES)
            throw new IOException("RigWorks profile index is too large");
        if (Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS) && Files.size(index) <= MAX_INDEX_BYTES
                && Files.readString(index, StandardCharsets.UTF_8).equals(encoded)) return;
        Path temporary = Files.createTempFile(root, ".profiles-", ".tmp");
        try {
            Files.writeString(temporary, encoded, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, index, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, index, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path profileFile(String profile) {
        return root.resolve("characters").resolve(CharacterVisualConfig.hash(profile.getBytes(StandardCharsets.UTF_8)) + ".json");
    }

    private void confined(Path file) throws IOException {
        if (!file.toAbsolutePath().normalize().startsWith(root)) throw new IOException("Profile path escapes config directory");
        Path current = root;
        if (Files.isSymbolicLink(current)) throw new IOException("Profile config root is a symbolic link");
        for (Path part : root.relativize(file.toAbsolutePath().normalize())) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Profile config contains a symbolic link");
        }
    }

    private static boolean validProfileId(String value) {
        return value != null && value.length() <= 256 && !value.contains("..")
                && value.matches("[a-z0-9_.-]+:[a-z0-9_.-][a-z0-9_./-]*");
    }

    private record IndexState(Set<String> ids, boolean readable) {}
}
