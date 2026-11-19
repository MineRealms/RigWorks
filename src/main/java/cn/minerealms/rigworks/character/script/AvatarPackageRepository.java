package cn.minerealms.rigworks.character.script;

import cn.minerealms.rigworks.character.core.CharacterVisualConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Explicit per-avatar reloads. No KubeJS global manager reload and no downloaded-file writes. */
public final class AvatarPackageRepository {
    public record Entry(String id, Path directory, AvatarScriptBundle bundle, String error) {
        public boolean valid() { return bundle != null; }
    }

    private static final String BACKUP_PREFIX = ".backup-";
    private static final String STAGING_PREFIX = ".staging-";
    private final Path directory;
    private final Path avatarsDirectory;
    private volatile Map<String, Entry> entries = Map.of();
    private volatile long revision;

    public AvatarPackageRepository(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
        this.avatarsDirectory = this.directory.resolve("avatars");
    }

    public static AvatarPackageRepository forGame(Path gameDirectory) {
        return new AvatarPackageRepository(gameDirectory.resolve("kubejs/rigworks"));
    }

    public Path directory() { return directory; }
    public long revision() { return revision; }
    public List<Entry> entries() {
        return entries.values().stream().sorted(Comparator.comparing(Entry::id)).toList();
    }
    public Optional<AvatarScriptBundle> find(String id) {
        Entry value = entries.get(id);
        return Optional.ofNullable(value == null ? null : value.bundle());
    }
    public Optional<Entry> findInstance(UUID instanceId) {
        if (instanceId == null) return Optional.empty();
        return entries.values().stream().filter(Entry::valid)
                .filter(entry -> entry.bundle().instanceId().equals(instanceId)).findFirst();
    }
    public Optional<Entry> findInstance(String instanceId) {
        try { return findInstance(UUID.fromString(instanceId)); }
        catch (RuntimeException invalid) { return Optional.empty(); }
    }
    public Optional<AvatarScriptBundle> forInstance(UUID instanceId) {
        return findInstance(instanceId).map(Entry::bundle);
    }
    public Optional<AvatarScriptBundle> forInstance(String instanceId) {
        return findInstance(instanceId).map(Entry::bundle);
    }
    public Optional<AvatarScriptBundle> forProfile(String profile) {
        return entries().stream().filter(Entry::valid).map(Entry::bundle)
                .filter(bundle -> bundle.profile().equals(profile)).findFirst();
    }

    public synchronized List<Entry> refresh() throws IOException {
        Files.createDirectories(directory);
        Files.createDirectories(avatarsDirectory);
        recoverBackups();

        var replacement = new LinkedHashMap<String, Entry>();
        var seenInstances = new java.util.HashSet<UUID>();
        var canonical = directories(avatarsDirectory).stream()
                .filter(path -> isUuid(path.getFileName().toString())).toList();
        var legacy = directories(directory).stream()
                .filter(path -> !reserved(path.getFileName().toString())).toList();
        var candidates = new ArrayList<Path>(canonical);
        candidates.addAll(legacy);

        for (Path folder : candidates) {
            if (replacement.size() >= 128) break;
            Entry entry = read(folder);
            if (!entry.valid()) {
                Entry previous = entries.values().stream()
                        .filter(old -> old.directory().equals(folder) && old.valid()).findFirst().orElse(null);
                if (previous != null) {
                    entry = new Entry(previous.id(), folder, previous.bundle(), entry.error());
                    seenInstances.add(previous.bundle().instanceId());
                }
                String key = replacement.containsKey(entry.id()) ? folder.getFileName().toString() : entry.id();
                replacement.putIfAbsent(key, entry);
                continue;
            }

            UUID identity = entry.bundle().instanceId();
            if (!seenInstances.add(identity) || replacement.containsKey(entry.id())) continue;
            replacement.put(entry.id(), entry);
        }

        entries = Map.copyOf(replacement);
        revision++;
        return entries();
    }

    public synchronized Entry reload(String id) throws IOException {
        Entry existing = entries.get(id);
        if (existing == null) throw new IOException("Unknown avatar package: " + id);
        Entry updated = read(existing.directory());
        if (!updated.valid()) return updated;
        if (!updated.bundle().instanceId().equals(existing.bundle().instanceId()))
            throw new IOException("Avatar instance ID cannot change during reload");
        if (!updated.id().equals(id) && entries.containsKey(updated.id()))
            throw new IOException("Duplicate avatar ID: " + updated.id());
        var next = new LinkedHashMap<>(entries);
        next.remove(id);
        next.put(updated.id(), updated);
        entries = Map.copyOf(next);
        revision++;
        return updated;
    }

    /** Imports without overwriting: a conflicting package receives a separate copy identity. */
    public synchronized Entry importLocal(AvatarScriptBundle source) throws IOException {
        refresh();
        for (Entry entry : entries()) {
            if (entry.valid() && entry.error().isEmpty() && entry.bundle().hash().equals(source.hash())) return entry;
        }

        AvatarScriptBundle candidate = source;
        int suffix = 0;
        while (conflicts(candidate) || Files.exists(folder(candidate.instanceId()), LinkOption.NOFOLLOW_LINKS)) {
            String key = CharacterVisualConfig.hash((source.profile() + "\n" + source.hash() + "\n" + (++suffix))
                    .getBytes(StandardCharsets.UTF_8)).substring(0, 24);
            UUID forkId = UUID.nameUUIDFromBytes(("fork:" + source.instanceId() + ":" + key)
                    .getBytes(StandardCharsets.UTF_8));
            candidate = rebind(source, "rigworks:avatar/" + key,
                    "rigworks:workbench/" + key + "/profile.json", forkId);
            for (Entry entry : entries()) {
                if (entry.valid() && entry.error().isEmpty() && entry.bundle().hash().equals(candidate.hash())) return entry;
            }
        }

        if (entries.size() >= 128) throw new IOException("At most 128 local avatar source folders");
        validateScript(candidate);
        Path target = folder(candidate.instanceId());
        Path staging = Files.createTempDirectory(avatarsDirectory, STAGING_PREFIX);
        try {
            writeBundle(staging, candidate);
            verifyWritten(staging, candidate);
            move(staging, target);
        } finally {
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) removeTree(staging);
        }

        Entry published = new Entry(candidate.id(), target, candidate, "");
        var next = new LinkedHashMap<>(entries);
        next.put(published.id(), published);
        entries = Map.copyOf(next);
        revision++;
        return published;
    }

    /** Replaces scripts only for the requested existing copy; identity, profile, and id stay fixed. */
    public synchronized Entry replaceLocal(AvatarScriptBundle replacement) throws IOException {
        return replaceLocal(replacement.instanceId(), replacement);
    }

    public synchronized Entry replaceLocal(UUID instanceId, AvatarScriptBundle replacement) throws IOException {
        return replaceLocal(instanceId, null, replacement);
    }

    /** Replaces only if the published revision still matches the value observed by the importer. */
    public synchronized Entry replaceLocal(UUID instanceId, String expectedHash, AvatarScriptBundle replacement) throws IOException {
        if (!instanceId.equals(replacement.instanceId()))
            throw new IOException("Replacement avatar instance ID does not match the selected copy");
        refresh();
        Entry existing = findInstance(instanceId)
                .orElseThrow(() -> new IOException("Unknown avatar instance: " + instanceId));
        if (!existing.error().isBlank())
            throw new IOException("Avatar sources contain local errors; repair or fork them before replacement: " + existing.error());
        if (expectedHash != null && !expectedHash.equals(existing.bundle().hash()))
            throw new IOException("Avatar scripts changed before replacement; reload and retry");
        if (!existing.bundle().id().equals(replacement.id())
                || !existing.bundle().profile().equals(replacement.profile()))
            throw new IOException("Replacing avatar scripts cannot change the copy ID or profile");
        if (existing.bundle().hash().equals(replacement.hash())) return existing;

        validateScript(replacement);
        Path target = folder(instanceId);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !existing.directory().equals(target))
            throw new IOException("Avatar destination already exists: " + target);

        Path staging = Files.createTempDirectory(avatarsDirectory, STAGING_PREFIX);
        Path backup = backupPath(instanceId);
        Path marker = backupMarker(instanceId);
        boolean movedOriginal = false;
        try {
            writeBundle(staging, replacement);
            verifyWritten(staging, replacement);
            if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS) || Files.exists(marker, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Avatar replacement recovery is pending: " + instanceId);

            writeBackupMarker(marker, existing.directory(), replacement.hash());
            move(existing.directory(), backup);
            movedOriginal = true;
            move(staging, target);
            verifyWritten(target, replacement);

            Entry updated = new Entry(replacement.id(), target, replacement, "");
            var next = new LinkedHashMap<>(entries);
            next.remove(existing.id());
            next.put(updated.id(), updated);
            entries = Map.copyOf(next);
            revision++;
            try {
                removeTree(backup);
                Files.deleteIfExists(marker);
            } catch (IOException ignored) {
                // refresh() completes cleanup after an interrupted successful replacement.
            }
            return updated;
        } catch (IOException | RuntimeException failure) {
            if (movedOriginal) {
                try { recoverBackup(backup); }
                catch (IOException rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        } finally {
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) removeTree(staging);
        }
    }

    private Path folder(UUID instanceId) { return avatarsDirectory.resolve(instanceId.toString()); }
    private Path backupPath(UUID instanceId) { return avatarsDirectory.resolve(BACKUP_PREFIX + instanceId); }
    private Path backupMarker(UUID instanceId) { return avatarsDirectory.resolve(BACKUP_PREFIX + instanceId + ".origin"); }

    public static AvatarScriptBundle rebind(AvatarScriptBundle source, String id, String profile) {
        return rebind(source, id, profile, source.instanceId());
    }

    public static AvatarScriptBundle rebind(AvatarScriptBundle source, String id, String profile, UUID instanceId) {
        var files = new LinkedHashMap<>(source.files());
        var manifest = source.manifest();
        manifest.addProperty("version", 1);
        manifest.addProperty("id", id);
        manifest.addProperty("profile", profile);
        manifest.addProperty("controller", source.controller());
        manifest.addProperty("instanceId", instanceId.toString());
        files.put("manifest.json", manifest.toString());
        return new AvatarScriptBundle(id, profile, source.controller(), instanceId, files);
    }

    /** Reads portable local archive contents without publishing them to the author repository. */
    public static AvatarScriptBundle readBundle(Path folder) throws IOException {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Avatar source folder must be a directory");
        Path realFolder = folder.toRealPath();
        var manifest = CharacterVisualConfig.parseBounded(readFile(folder.resolve("manifest.json"))).getAsJsonObject();
        String id = manifest.has("id") ? manifest.get("id").getAsString() : folder.getFileName().toString();
        String profile = manifest.get("profile").getAsString();
        String controller = manifest.get("controller").getAsString();
        var files = new LinkedHashMap<String, String>();
        try (var paths = Files.walk(folder, 8)) {
            for (Path path : paths.sorted().toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Avatar source cannot contain symbolic links");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && folder.relativize(path).getNameCount() >= 8) {
                    try (var children = Files.list(path)) {
                        if (children.findAny().isPresent()) throw new IOException("Avatar source folder depth exceeds eight levels");
                    }
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                String name = path.getFileName().toString();
                if (!name.endsWith(".js") && !name.endsWith(".json")) continue;
                if (files.size() >= 32) throw new IOException("At most 32 JSON/JS files per avatar");
                if (!path.toRealPath().startsWith(realFolder)) throw new IOException("Avatar source escapes through symbolic link");
                files.put(folder.relativize(path).toString().replace('\\', '/'), readFile(path));
            }
        }
        return new AvatarScriptBundle(id, profile, controller, files);
    }

    private Entry read(Path folder) {
        String fallback = folder.getFileName().toString();
        try {
            Path realRoot = directory.toRealPath();
            if (!folder.toRealPath().startsWith(realRoot)) throw new IOException("Avatar package escapes source folder");
            var bundle = readBundle(folder);
            Entry previous = entries.get(bundle.id());
            if (previous == null || !previous.valid() || !previous.bundle().hash().equals(bundle.hash())) validateScript(bundle);
            return new Entry(bundle.id(), folder, bundle, "");
        } catch (IOException | RuntimeException failure) {
            return new Entry(fallback, folder, null, shortError(failure));
        }
    }

    private void recoverBackups() throws IOException {
        for (Path backup : directories(avatarsDirectory).stream()
                .filter(path -> path.getFileName().toString().startsWith(BACKUP_PREFIX)).toList()) {
            recoverBackup(backup);
        }
        List<Path> markers;
        try (var paths = Files.list(avatarsDirectory)) {
            markers = paths.filter(path -> path.getFileName().toString().startsWith(BACKUP_PREFIX)
                    && path.getFileName().toString().endsWith(".origin")).toList();
        }
        for (Path marker : markers) {
            String token = marker.getFileName().toString().substring(BACKUP_PREFIX.length(),
                    marker.getFileName().toString().length() - ".origin".length());
            if (!isUuid(token)) continue;
            Path backup = backupPath(UUID.fromString(token));
            if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                Entry current = read(folder(UUID.fromString(token)));
                if (!current.valid()) throw new IOException("Avatar replacement marker has no recoverable package: " + marker);
                Files.deleteIfExists(marker);
            }
        }
        for (Path staging : directories(avatarsDirectory).stream()
                .filter(path -> path.getFileName().toString().startsWith(STAGING_PREFIX)).toList()) removeTree(staging);
    }

    private void recoverBackup(Path backup) throws IOException {
        String name = backup.getFileName().toString();
        String token = name.substring(BACKUP_PREFIX.length());
        if (!isUuid(token)) throw new IOException("Invalid avatar backup name: " + name);
        UUID instanceId = UUID.fromString(token);
        Path marker = backupMarker(instanceId);
        if (!Files.isDirectory(backup, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(marker);
            return;
        }
        AvatarScriptBundle original = readBundle(backup);
        if (!original.instanceId().equals(instanceId)) throw new IOException("Avatar backup identity mismatch: " + backup);
        String[] markerLines = Files.exists(marker, LinkOption.NOFOLLOW_LINKS)
                ? Files.readString(marker, StandardCharsets.UTF_8).split("\\R", 2) : new String[0];
        String committedHash = markerLines.length > 0 ? markerLines[0] : "";
        Path origin = markerLines.length > 1 ? resolveOrigin(markerLines[1]) : folder(instanceId);
        Path target = folder(instanceId);
        Entry current = Files.exists(target, LinkOption.NOFOLLOW_LINKS) ? read(target) : null;
        if (current != null && current.valid() && current.bundle().instanceId().equals(instanceId)
                && !committedHash.isEmpty() && current.bundle().hash().equals(committedHash)) {
            removeTree(backup);
            Files.deleteIfExists(marker);
            return;
        }

        if (current != null) {
            Path failed = avatarsDirectory.resolve(".failed-" + instanceId + "-" + UUID.randomUUID());
            move(target, failed);
        }
        Path restore = Files.exists(origin, LinkOption.NOFOLLOW_LINKS) ? target : origin;
        if (Files.exists(restore, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Cannot restore avatar backup; destination exists: " + restore);
        move(backup, restore);
        Files.deleteIfExists(marker);
    }

    private Path resolveOrigin(String relative) throws IOException {
        final String decoded;
        try { decoded = new String(Base64.getUrlDecoder().decode(relative), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid avatar backup origin marker", invalid); }
        Path value = Path.of(decoded);
        if (value.isAbsolute()) throw new IOException("Avatar backup origin must be relative");
        Path resolved = directory.resolve(value).normalize();
        if (!resolved.startsWith(directory) || resolved.equals(directory)) throw new IOException("Invalid avatar backup origin");
        return resolved;
    }

    private void writeBackupMarker(Path marker, Path origin, String expectedHash) throws IOException {
        Path relative = directory.relativize(origin.toAbsolutePath().normalize());
        if (relative.isAbsolute() || relative.startsWith("..")) throw new IOException("Avatar backup origin escapes repository");
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(relative.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
        Files.writeString(marker, expectedHash + "\n" + encoded, StandardCharsets.UTF_8);
    }

    private static List<Path> directories(Path parent) throws IOException {
        try (var paths = Files.list(parent)) {
            return paths.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
        }
    }

    private static boolean isUuid(String value) {
        try { UUID.fromString(value); return true; }
        catch (RuntimeException invalid) { return false; }
    }

    private static boolean reserved(String name) {
        return name.startsWith(".") || name.equals("cache") || name.equals("avatars");
    }

    private boolean conflicts(AvatarScriptBundle bundle) {
        return entries.containsKey(bundle.id()) || entries.values().stream().anyMatch(entry -> entry.valid()
                && (entry.bundle().profile().equals(bundle.profile())
                || entry.bundle().instanceId().equals(bundle.instanceId())));
    }

    private static void writeBundle(Path folder, AvatarScriptBundle bundle) throws IOException {
        for (var file : bundle.files().entrySet()) {
            Path target = folder.resolve(file.getKey()).normalize();
            if (!target.startsWith(folder)) throw new IOException("Avatar bundle path escapes staging folder");
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
        }
    }

    private static void verifyWritten(Path folder, AvatarScriptBundle expected) throws IOException {
        AvatarScriptBundle actual = readBundle(folder);
        if (!actual.hash().equals(expected.hash()) || !actual.instanceId().equals(expected.instanceId()))
            throw new IOException("Avatar package verification failed");
    }

    private static void move(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(source, target); }
    }

    private static void removeTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void validateScript(AvatarScriptBundle bundle) throws IOException {
        try (var session = AvatarScriptRuntime.open(bundle)) {
            if (!session.available() && !session.failure().startsWith("Rhino is not installed"))
                throw new IOException(session.failure());
        }
    }

    private static String readFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Avatar source must be a regular file: " + path.getFileName());
        if (Files.size(path) > 65536) throw new IOException("Avatar file exceeds 64 KiB: " + path.getFileName());
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String shortError(Throwable error) {
        String text = error.getMessage();
        return text == null ? error.getClass().getSimpleName() : text.substring(0, Math.min(text.length(), 512));
    }
}
