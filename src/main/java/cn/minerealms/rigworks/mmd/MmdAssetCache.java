package cn.minerealms.rigworks.mmd;

import cn.minerealms.rigworks.character.importing.AvatarImportContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Immutable converted assets. Native code runs only on a cache miss, never during playback. */
public final class MmdAssetCache {
    public static final String REVISION = "mmd-import-v1";
    private static final long MAX_BUNDLE_BYTES = 512L * 1024 * 1024;
    private static final int MAX_METADATA_BYTES = 8 * 1024 * 1024;
    @FunctionalInterface public interface Converter {
        void convert(byte[] source, Path sourcePath, Path output, String options) throws IOException;
    }
    public record Imported(Path model, JsonObject metadata, String modelHash, String assetHash,
                           MmdImportSettings settings, boolean reused) {}
    private record Dependency(Path path, String hash) {}
    private final Path root;
    private final Converter converter;
    private final long bundleBudget;

    public MmdAssetCache(Path root) { this(root, MmdNative::convert); }
    public MmdAssetCache(Path root, Converter converter) {
        this(root, converter, MAX_BUNDLE_BYTES);
    }
    MmdAssetCache(Path root, Converter converter, long bundleBudget) {
        if (bundleBudget < 1 || bundleBudget > MAX_BUNDLE_BYTES) throw new IllegalArgumentException("Invalid MMD bundle budget");
        this.root = root.toAbsolutePath().normalize(); this.converter = converter;
        this.bundleBudget = bundleBudget;
    }

    public synchronized Imported importModel(AvatarImportContext source) throws IOException {
        if (source.bytes().length == 0 || source.bytes().length > MmdNative.MAX_SOURCE_BYTES)
            throw new IOException("MMD source must be between 1 byte and 256 MiB");
        var settings = MmdImportSettings.read(source);
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("MMD cache root cannot be a symbolic link");
        var digest = digest();
        field(digest, REVISION.getBytes(StandardCharsets.UTF_8)); field(digest, source.bytes());
        field(digest, settings.options(List.of()).toString().getBytes(StandardCharsets.UTF_8));
        // Texture resolution belongs to the selected directory, even when model bytes match.
        field(digest, (source.sourcePath() == null ? source.name() : source.sourcePath().toString()).getBytes(StandardCharsets.UTF_8));
        var motionHashes = new ArrayList<String>();
        long sourceBytes = source.bytes().length;
        for (var motion : settings.motions()) {
            long length = Files.size(motion.path());
            if (length < 1 || length > MmdNative.MAX_SOURCE_BYTES) throw new IOException("VMD input exceeds size limit: " + motion.path());
            sourceBytes += length;
            if (sourceBytes > MAX_BUNDLE_BYTES) throw new IOException("Combined MMD import sources exceed 512 MiB");
            String hash = hash(motion.path()); motionHashes.add(hash);
            field(digest, motion.name().getBytes(StandardCharsets.UTF_8)); field(digest, hash.getBytes(StandardCharsets.US_ASCII));
        }
        String requestHash = HexFormat.of().formatHex(digest.digest());
        Path index = root.resolve(requestHash + ".json");
        var reused = cached(index, settings, source);
        if (reused != null) return reused;
        Path staging = Files.createTempDirectory(root, ".mmd-");
        try {
            var nativeMotions = new ArrayList<MmdImportSettings.Motion>();
            Path inputs = Files.createDirectory(staging.resolve("input"));
            for (int i = 0; i < settings.motions().size(); i++) {
                Path file = inputs.resolve(i + ".vmd"); Files.copy(settings.motions().get(i).path(), file);
                if (!hash(file).equals(motionHashes.get(i))) throw new IOException("VMD source changed during import: " + settings.motions().get(i).path());
                nativeMotions.add(new MmdImportSettings.Motion(file, settings.motions().get(i).name()));
            }
            Path nativeSource = source.sourcePath() == null ? staging.resolve(source.name()) : source.sourcePath();
            var options = settings.options(nativeMotions); options.remove("animations");
            converter.convert(source.bytes(), nativeSource, staging, options.toString());
            removeTree(inputs);
            JsonObject metadata = json(staging.resolve("metadata.json"));
            if (!metadata.has("version") || metadata.get("version").getAsInt() != 1) throw new IOException("Unsupported MMD metadata version");
            var dependencies = captureTextures(source, staging, metadata);
            MmdProfileAuthor.write(staging, metadata, settings, requestHash);
            writeAtomic(staging.resolve("metadata.json"), metadata.toString());
            validateGlb(staging.resolve("model.glb"));
            JsonObject files = fileHashes(staging);
            var bundle = digest(); field(bundle, files.toString().getBytes(StandardCharsets.UTF_8));
            String assetHash = HexFormat.of().formatHex(bundle.digest());
            Path directory = root.resolve(assetHash);
            publish(staging, directory, files);
            var manifest = new JsonObject(); manifest.addProperty("revision", REVISION); manifest.addProperty("assetHash", assetHash);
            manifest.add("files", files);
            var refs = new JsonArray();
            for (var dependency : dependencies) {
                var entry = new JsonObject(); entry.addProperty("path", dependency.path().toString()); entry.addProperty("hash", dependency.hash()); refs.add(entry);
            }
            manifest.add("dependencies", refs); writeAtomic(index, manifest.toString());
            return new Imported(directory.resolve("model.glb"), metadata, files.get("model.glb").getAsString(), assetHash, settings, false);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid MMD conversion output for " + source.name() + ": " + invalid.getMessage(), invalid);
        } finally { removeTree(staging); }
    }

    private Imported cached(Path index, MmdImportSettings settings, AvatarImportContext source) throws IOException {
        if (!Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            var manifest = json(index);
            if (!REVISION.equals(manifest.get("revision").getAsString())) return null;
            String hash = manifest.get("assetHash").getAsString();
            if (!hash.matches("[a-f0-9]{64}")) return null;
            Path directory = root.resolve(hash);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return null;
            for (var value : manifest.getAsJsonArray("dependencies")) {
                var dependency = value.getAsJsonObject(); Path file = Path.of(dependency.get("path").getAsString());
                if (!Files.isRegularFile(file) || !hash(file).equals(dependency.get("hash").getAsString())) return null;
            }
            var metadata = json(directory.resolve("metadata.json"));
            if (metadata.has("textures")) for (var value : metadata.getAsJsonArray("textures")) {
                var texture = value.getAsJsonObject();
                if (Files.isRegularFile(child(directory, texture.get("assetPath").getAsString()))) continue;
                try { source.resolve(texture.get("sourcePath").getAsString()); return null; }
                catch (IOException stillMissing) { /* Preserve the existing missing-texture diagnostic. */ }
            }
            var expected = manifest.getAsJsonObject("files");
            if (!expected.equals(fileHashes(directory))) return null;
            return new Imported(directory.resolve("model.glb"), metadata,
                    expected.get("model.glb").getAsString(), hash, settings, true);
        } catch (IOException | RuntimeException corrupt) {
            org.apache.logging.log4j.LogManager.getLogger("rigworks.mmd").warn("Ignoring invalid MMD cache index {}", index, corrupt);
            return null;
        }
    }

    private List<Dependency> captureTextures(AvatarImportContext source, Path staging, JsonObject metadata) throws IOException {
        var dependencies = new ArrayList<Dependency>();
        var warnings = metadata.has("warnings") ? metadata.getAsJsonArray("warnings") : new JsonArray();
        metadata.add("warnings", warnings);
        if (!metadata.has("textures")) return dependencies;
        var captured = new java.util.HashSet<Path>();
        long total = 0;
        try (var files = Files.walk(staging)) {
            for (Path file : files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList()) total += Files.size(file);
        }
        for (var value : metadata.getAsJsonArray("textures")) {
            var texture = value.getAsJsonObject();
            String asset = texture.get("assetPath").getAsString();
            Path target = child(staging, asset);
            if (!asset.replace('\\', '/').startsWith("textures/") || !captured.add(target))
                throw new IOException("Invalid or duplicate MMD texture output: " + asset);
            String reference = texture.get("sourcePath").getAsString();
            Path original;
            long length;
            try {
                original = source.resolve(reference);
                length = Files.size(original);
                if (length > 64L * 1024 * 1024) throw new IOException("Texture exceeds 64 MiB");
            } catch (IOException missing) {
                warnings.add("Texture unavailable: " + reference + " (" + missing.getMessage() + ")");
                continue;
            }
            if (length > bundleBudget - total) throw new IOException("Converted MMD textures exceed the bundle budget (" + bundleBudget + " bytes)");
            try {
                Files.createDirectories(target.getParent());
                try (var input = Files.newInputStream(original); var output = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[65536]; long copied = 0; int count;
                    while ((count = input.read(buffer)) >= 0) {
                        copied += count;
                        if (copied > length) throw new IOException("Texture changed size during import");
                        output.write(buffer, 0, count);
                    }
                    if (copied != length) throw new IOException("Texture changed size during import");
                }
                dependencies.add(new Dependency(original, hash(target))); total += length;
            } catch (IOException missing) {
                Files.deleteIfExists(target);
                warnings.add("Texture unavailable: " + reference + " (" + missing.getMessage() + ")");
            }
        }
        return dependencies;
    }

    private void publish(Path staging, Path directory, JsonObject files) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) { move(staging, directory, false); return; }
        boolean intact = false;
        try { intact = !Files.isSymbolicLink(directory) && files.equals(fileHashes(directory)); }
        catch (IOException corrupt) { org.apache.logging.log4j.LogManager.getLogger("rigworks.mmd").warn("Replacing unreadable MMD cache {}", directory, corrupt); }
        if (intact) { removeTree(staging); return; }
        Path backup = root.resolve(".mmd-replaced-" + java.util.UUID.randomUUID());
        move(directory, backup, false);
        try { move(staging, directory, false); }
        catch (IOException failure) {
            try { move(backup, directory, false); } catch (IOException restore) { failure.addSuppressed(restore); }
            throw failure;
        }
        org.apache.logging.log4j.LogManager.getLogger("rigworks.mmd").warn("Repaired corrupted MMD cache {}", directory);
        try { removeTree(backup); }
        catch (IOException cleanup) { org.apache.logging.log4j.LogManager.getLogger("rigworks.mmd").warn("Cannot remove replaced MMD cache {}", backup, cleanup); }
    }

    private JsonObject fileHashes(Path directory) throws IOException {
        var files = new JsonObject(); long total = 0;
        try (var paths = Files.walk(directory)) {
            List<Path> ordered = paths.sorted().toList();
            if (ordered.size() > 2048) throw new IOException("MMD conversion produces too many files");
            for (Path file : ordered) {
                if (Files.isSymbolicLink(file)) throw new IOException("MMD cache cannot contain symbolic links");
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                total += Files.size(file); if (total > bundleBudget) throw new IOException("Converted MMD asset exceeds bundle budget (" + bundleBudget + " bytes)");
                files.addProperty(directory.relativize(file).toString().replace('\\', '/'), hash(file));
            }
        }
        return files;
    }
    private static Path child(Path directory, String name) throws IOException {
        String portable = name.replace('\\', '/');
        Path child = directory.resolve(portable).normalize();
        if (portable.isBlank() || portable.startsWith("/") || portable.matches("^[A-Za-z]:.*") || !child.startsWith(directory) || child.equals(directory))
            throw new IOException("MMD output escapes staging directory: " + name);
        return child;
    }
    private static void validateGlb(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) < 20 || Files.size(file) > MAX_BUNDLE_BYTES)
            throw new IOException("MMD conversion produced no valid GLB");
        try (var input = Files.newInputStream(file)) {
            var header = ByteBuffer.wrap(input.readNBytes(12)).order(ByteOrder.LITTLE_ENDIAN);
            if (header.getInt() != 0x46546c67 || header.getInt() != 2 || Integer.toUnsignedLong(header.getInt()) != Files.size(file))
                throw new IOException("MMD conversion produced an invalid GLB header");
        }
    }
    public static JsonObject json(Path file) throws IOException {
        try { return JsonParser.parseString(new String(boundedRead(file, MAX_METADATA_BYTES), StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException invalid) { throw new IOException("Invalid MMD JSON: " + file, invalid); }
    }
    private static byte[] boundedRead(Path file, int maximum) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > maximum) throw new IOException("MMD input exceeds limit or is missing: " + file);
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length == 0 || bytes.length > maximum) throw new IOException("MMD input is empty or exceeds limit: " + file);
            return bytes;
        }
    }
    public static String hash(Path file) throws IOException {
        var digest = digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void field(MessageDigest hash, byte[] bytes) {
        hash.update(ByteBuffer.allocate(8).putLong(bytes.length).array()); hash.update(bytes);
    }
    public static void writeAtomic(Path file, String value) throws IOException {
        Files.createDirectories(file.getParent()); Path temporary = Files.createTempFile(file.getParent(), ".mmd-write-", ".tmp");
        try { Files.writeString(temporary, value, StandardCharsets.UTF_8); move(temporary, file, true); }
        finally { Files.deleteIfExists(temporary); }
    }
    private static void move(Path source, Path target, boolean replace) throws IOException {
        try {
            if (replace) Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            if (replace) Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); else Files.move(source, target);
        }
    }
    private static void removeTree(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
