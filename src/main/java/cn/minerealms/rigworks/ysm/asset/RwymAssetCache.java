package cn.minerealms.rigworks.ysm.asset;

import cn.minerealms.rigworks.debug.RigWorksDiagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Content-addressed disk cache. Failed imports cannot replace the last usable asset. */
public final class RwymAssetCache {
    public static final String CONVERTER_REVISION = "v1-c1";
    @FunctionalInterface public interface Converter { byte[] convert(byte[] source) throws IOException; }
    public record Imported(Path file, String sourceHash, String assetHash, RwymReader.Asset asset, boolean reused) {}
    private final Path root;
    private final Converter converter;

    public RwymAssetCache(Path root) { this(root, RwymNative::convert); }
    public RwymAssetCache(Path root, Converter converter) {
        this.root = root.toAbsolutePath().normalize().resolve(CONVERTER_REVISION);
        this.converter = converter;
    }

    public Imported importYsm(byte[] source) throws IOException {
        long started = System.nanoTime();
        if (source == null || source.length == 0 || source.length > RwymNative.MAX_SOURCE_BYTES)
            throw new IOException("YSM import exceeds the source size limit");
        String sourceHash = hash(source);
        Path file = root.resolve(sourceHash + ".rwym");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("YSM cache entry is not a regular file: " + file);
            try {
                byte[] bytes = read(file);
                var asset = RwymReader.read(bytes);
                RigWorksDiagnostics.debug("YSM cache hit source={} sourceBytes={} assetBytes={} format={} elapsedMs={}",
                        sourceHash, source.length, bytes.length, asset.sourceFormat(), (System.nanoTime() - started) / 1_000_000);
                return new Imported(file, sourceHash, hash(bytes), asset, true);
            } catch (IOException damagedCache) {
                org.apache.logging.log4j.LogManager.getLogger("rigworks.ysm").warn(
                        "Rebuilding invalid YSM asset cache {} from the selected source; existing bytes remain until conversion succeeds",
                        file, damagedCache);
            }
        }
        byte[] bytes = converter.convert(source);
        RwymReader.Asset asset = RwymReader.read(bytes);
        writeAtomic(file, bytes);
        RigWorksDiagnostics.debug("YSM converted source={} sourceBytes={} assetBytes={} format={} elapsedMs={}",
                sourceHash, source.length, bytes.length, asset.sourceFormat(), (System.nanoTime() - started) / 1_000_000);
        return new Imported(file, sourceHash, hash(bytes), asset, false);
    }

    public static byte[] read(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > RwymNative.MAX_ASSET_BYTES)
            throw new IOException("Invalid RigWorks asset file: " + file);
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(RwymNative.MAX_ASSET_BYTES + 1);
            if (bytes.length > RwymNative.MAX_ASSET_BYTES) throw new IOException("RigWorks asset exceeds size limit");
            return bytes;
        }
    }

    public static void writeAtomic(Path file, byte[] bytes) throws IOException {
        Path parent = file.toAbsolutePath().normalize().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".rwym-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
