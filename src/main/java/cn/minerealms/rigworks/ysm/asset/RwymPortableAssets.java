package cn.minerealms.rigworks.ysm.asset;

import cn.minerealms.rigworks.ysm.runtime.YsmModelSource;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** The normal engine model remains the ZIP source; RWYM travels as a required RigWorks asset. */
public final class RwymPortableAssets {
    public static final String ASSET_PATH = "extensions/rigworks/ysm/model.rwym";
    public static final int VERSION = 1;

    public record Prepared(Path assetFile, String hash, YsmModelSource.YsmModelView model) {
        public JsonObject metadata() {
            JsonObject metadata = new JsonObject();
            metadata.addProperty("version", VERSION);
            metadata.addProperty("source", "ysm");
            metadata.addProperty("asset", ASSET_PATH);
            metadata.addProperty("sha256", hash);
            return metadata;
        }
    }

    private RwymPortableAssets() {}

    /** Blocking and bounded. A packaged asset takes precedence over an author's external cache. */
    public static Prepared prepare(Path projectDirectory, Path canonicalAsset, String expectedHash) throws IOException {
        requireHash(expectedHash);
        Path packaged = projectDirectory == null ? null : confinedAsset(projectDirectory, false);
        Path input = packaged != null && Files.exists(packaged, LinkOption.NOFOLLOW_LINKS) ? packaged : canonicalAsset;
        if (input == null) throw new IOException("The YSM-derived character has no RWYM asset; import its model again");
        byte[] bytes = RwymAssetCache.read(input);
        if (!expectedHash.equals(RwymAssetCache.hash(bytes)))
            throw new IOException("The portable RWYM asset does not match the character's model hash");
        var model = RwymReader.read(bytes).mainModel();
        var view = YsmModelSource.reflective().view(model);
        if (packaged != null && !Files.exists(packaged, LinkOption.NOFOLLOW_LINKS)) {
            packaged = confinedAsset(projectDirectory, true);
            RwymAssetCache.writeAtomic(packaged, bytes);
        }
        return new Prepared(packaged == null ? input.toAbsolutePath().normalize() : packaged, expectedHash, view);
    }

    public static void validateMetadata(JsonObject metadata, String expectedHash) throws IOException {
        requireHash(expectedHash);
        try {
            if (metadata == null || metadata.size() != 4
                    || !metadata.get("version").isJsonPrimitive()
                    || !metadata.get("version").getAsJsonPrimitive().isNumber()
                    || metadata.get("version").getAsBigDecimal().compareTo(java.math.BigDecimal.valueOf(VERSION)) != 0
                    || !stringEquals(metadata, "source", "ysm")
                    || !stringEquals(metadata, "asset", ASSET_PATH)
                    || !stringEquals(metadata, "sha256", expectedHash))
                throw new IOException("Invalid portable RWYM metadata");
        } catch (IllegalStateException | ClassCastException | NullPointerException | NumberFormatException error) {
            throw new IOException("Invalid portable RWYM metadata", error);
        }
    }

    private static boolean stringEquals(JsonObject metadata, String name, String expected) {
        var value = metadata.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && expected.equals(value.getAsString());
    }

    private static void requireHash(String hash) throws IOException {
        if (hash == null || !hash.matches("[a-f0-9]{64}"))
            throw new IOException("The portable RWYM asset requires its SHA-256 identity");
    }

    private static Path confinedAsset(Path directory, boolean createDirectories) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))
            throw new IOException("The workbench project must be a real directory");
        Path current = root;
        Path relative = Path.of(ASSET_PATH);
        for (int i = 0; i < relative.getNameCount(); i++) {
            current = current.resolve(relative.getName(i));
            if (Files.isSymbolicLink(current)) throw new IOException("Portable RWYM paths cannot contain symbolic links");
            if (i < relative.getNameCount() - 1) {
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Portable RWYM directory conflicts with a file");
                if (createDirectories) Files.createDirectories(current);
            }
        }
        return current;
    }
}
