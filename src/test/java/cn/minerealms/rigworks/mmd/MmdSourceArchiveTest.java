package cn.minerealms.rigworks.mmd;

import cn.minerealms.rigworks.character.importing.AvatarImportContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class MmdSourceArchiveTest {
    @TempDir Path directory;

    @Test void validArchiveRestoresTheModelDependencyDirectoryAndCleansItsOwnedStaging() throws Exception {
        var files = payload();
        files.put("character/textures/髪.png", new byte[]{7, 8});
        byte[] archive = zip(files, manifest("character/model.pmx", files));
        Path extracted;
        try (var source = MmdSourceArchive.extract(archive, directory)) {
            extracted = source.directory();
            assertArrayEquals(new byte[]{1, 2, 3}, source.model().bytes());
            assertArrayEquals(new byte[]{7, 8}, Files.readAllBytes(source.model().resolve("textures/髪.png")));
            assertEquals(hash(archive), source.archiveHash());
            assertTrue(source.model().sourcePath().startsWith(extracted.toRealPath()));
        }
        assertFalse(Files.exists(extracted));
    }

    @Test void cacheReuseDoesNotDependOnAnArchiveFilenameOrAnExpiredTemporaryExtractionPath() throws Exception {
        var files = payload();
        files.put("character/color.png", new byte[]{5});
        files.put("character/walk.vmd", new byte[]{6});
        byte[] archive = zip(files, manifest("character/model.pmx", files));
        var conversions = new AtomicInteger();
        var cache = new MmdAssetCache(directory.resolve("cache"), (bytes, source, output, options) -> {
            conversions.incrementAndGet();
            assertArrayEquals(new byte[]{1, 2, 3}, bytes);
            assertArrayEquals(new byte[]{5}, Files.readAllBytes(source.resolveSibling("color.png")));
            var motions = JsonParser.parseString(options).getAsJsonObject().getAsJsonArray("motions");
            assertEquals(1, motions.size());
            assertArrayEquals(new byte[]{6}, Files.readAllBytes(Path.of(motions.get(0).getAsJsonObject().get("path").getAsString())));
            output(output, true);
        });
        var first = cache.importModel(new AvatarImportContext("first.zip", archive, null));
        assertFalse(first.reused());
        var second = cache.importModel(new AvatarImportContext("renamed.zip", archive, null));
        assertTrue(second.reused()); assertEquals(1, conversions.get());
        assertEquals(first.assetHash(), second.assetHash());
        assertArrayEquals(new byte[]{5}, Files.readAllBytes(second.model().resolveSibling("textures/color.png")));
        try (var paths = Files.list(directory.resolve("cache/source-staging"))) { assertEquals(0, paths.count()); }
        files.put("character/color.png", new byte[]{9});
        byte[] changed = zip(files, manifest("character/model.pmx", files));
        var changedCache = new MmdAssetCache(directory.resolve("cache"), (bytes, source, output, options) -> {
            conversions.incrementAndGet(); output(output, true);
        });
        assertFalse(changedCache.importModel(new AvatarImportContext("first.zip", changed, null)).reused());
        assertEquals(2, conversions.get());
    }

    @Test void barePmdImportStillUsesTheSelectedFileAndItsTextureDirectory() throws Exception {
        Path source = Files.write(directory.resolve("model.pmd"), new byte[]{4});
        Files.write(directory.resolve("color.png"), new byte[]{7});
        var conversions = new AtomicInteger();
        var cache = new MmdAssetCache(directory.resolve("cache"), (bytes, selected, output, options) -> {
            conversions.incrementAndGet(); assertEquals(source.toRealPath(), selected); output(output, true);
        });
        var context = AvatarImportContext.from(source, new byte[]{4});
        assertFalse(cache.importModel(context).reused());
        assertTrue(cache.importModel(context).reused()); assertEquals(1, conversions.get());
    }

    @Test void badManifestsCannotReachTheConverterOrLeaveStaging() throws Exception {
        var files = payload();
        var good = manifest("character/model.pmx", files);
        for (String replacement : new String[]{
                "{}", good.toString().replace(MmdSourceArchive.FORMAT, "assimploader.asset-package"),
                good.toString().replace("\"version\":1", "\"version\":2"),
                good.toString().replace(hash(new byte[]{1, 2, 3}), "f".repeat(64)),
                good.toString().replace("\"model\":\"character/model.pmx\"", "\"model\":\"character/missing.pmx\""),
                good.toString().replace("\"version\":1", "\"version\":1,\"version\":1")}) {
            var counter = new AtomicInteger();
            var cache = new MmdAssetCache(directory.resolve("cache"), (bytes, source, output, options) -> counter.incrementAndGet());
            byte[] archive = zip(files, replacement.getBytes(StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> cache.importModel(new AvatarImportContext("bad.zip", archive, null)), replacement);
            assertEquals(0, counter.get());
            assertNoStaging();
        }
        var extra = new LinkedHashMap<>(files); extra.put("unlisted.txt", new byte[]{8});
        assertThrows(IOException.class, () -> MmdSourceArchive.extract(zip(extra, good), directory));
        assertThrows(IOException.class, () -> MmdSourceArchive.extract(zip(Map.of(), good), directory));
    }

    @Test void unsafeAndConflictingMembersAreRejectedBeforeExtraction() throws Exception {
        for (String name : new String[]{"../escape.pmx", "/absolute.pmx", "C:/model.pmx", "a\\model.pmx", "NUL.pmx", "folder./model.pmx", "a//model.pmx"}) {
            var files = Map.of(name, new byte[]{1});
            assertThrows(IOException.class, () -> MmdSourceArchive.extract(zip(files, manifest(name, files)), directory), name);
        }
        var conflicts = payload(); conflicts.put("CHARACTER/other.png", new byte[]{1});
        assertThrows(IOException.class, () -> MmdSourceArchive.extract(zip(conflicts, manifest("character/model.pmx", conflicts)), directory));
        var collision = payload(); collision.put("character", new byte[]{1});
        assertThrows(IOException.class, () -> MmdSourceArchive.extract(zip(collision, manifest("character/model.pmx", collision)), directory));
        var duplicate = payload(); duplicate.put("character/other.pmx", new byte[]{2});
        byte[] bytes = zip(duplicate, manifest("character/model.pmx", duplicate));
        mutateCentral(bytes, "character/other.pmx", (data, at) -> {
            byte[] first = "character/model.pmx".getBytes(StandardCharsets.UTF_8);
            for (int index = 0; index < first.length; index++) data.put(at + 46 + index, first[index]);
        });
        assertThrows(IOException.class, () -> MmdSourceArchive.extract(bytes, directory));
        assertNoStaging();
    }

    @Test void symlinksAndOversizedOrDishonestlySizedCompressedEntriesAreRejected() throws Exception {
        var files = payload();
        byte[] link = zip(files, manifest("character/model.pmx", files));
        mutateCentral(link, "character/model.pmx", (data, at) -> data.putInt(at + 38, 0120777 << 16));
        assertTrue(assertThrows(IOException.class, () -> MmdSourceArchive.extract(link, directory)).getMessage().contains("symlinks"));
        byte[] oversized = zip(files, manifest("character/model.pmx", files));
        mutateCentral(oversized, "character/model.pmx", (data, at) -> data.putInt(at + 24, MmdNative.MAX_SOURCE_BYTES + 1));
        assertThrows(IOException.class, () -> MmdSourceArchive.extract(oversized, directory));
        files.put("character/color.png", new byte[4096]);
        var manifest = manifest("character/model.pmx", files);
        manifest.getAsJsonObject("files").getAsJsonObject("character/color.png").addProperty("bytes", 1);
        byte[] bomb = zip(files, manifest);
        mutateCentral(bomb, "character/color.png", (data, at) -> data.putInt(at + 24, 1));
        assertThrows(IOException.class, () -> MmdSourceArchive.extract(bomb, directory));
        assertNoStaging();
    }

    @Test void failedConversionRemovesTheExtractedSourceAndDoesNotPublishAnIndex() throws Exception {
        var files = payload(); byte[] bytes = zip(files, manifest("character/model.pmx", files));
        var cache = new MmdAssetCache(directory.resolve("cache"), (data, source, output, options) -> { throw new IOException("native fixture failure"); });
        assertTrue(assertThrows(IOException.class, () -> cache.importModel(new AvatarImportContext("model.zip", bytes, null))).getMessage().contains("native fixture failure"));
        try (var paths = Files.walk(directory.resolve("cache"))) { assertTrue(paths.noneMatch(Files::isRegularFile)); }
        assertNoStaging();
    }

    private void assertNoStaging() throws IOException {
        try (var paths = Files.walk(directory)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith(".mmd-source-")));
        }
    }
    private static LinkedHashMap<String, byte[]> payload() {
        var files = new LinkedHashMap<String, byte[]>(); files.put("character/model.pmx", new byte[]{1, 2, 3}); return files;
    }
    private static JsonObject manifest(String model, Map<String, byte[]> files) throws Exception {
        var value = new JsonObject(); value.addProperty("format", MmdSourceArchive.FORMAT); value.addProperty("version", 1); value.addProperty("model", model);
        var checks = new JsonObject();
        for (var entry : files.entrySet()) {
            var check = new JsonObject(); check.addProperty("bytes", entry.getValue().length); check.addProperty("sha256", hash(entry.getValue())); checks.add(entry.getKey(), check);
        }
        value.add("files", checks); return value;
    }
    private static byte[] zip(Map<String, byte[]> files, JsonObject manifest) throws IOException { return zip(files, manifest.toString().getBytes(StandardCharsets.UTF_8)); }
    private static byte[] zip(Map<String, byte[]> files, byte[] manifest) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("manifest.json")); zip.write(manifest); zip.closeEntry();
            for (var entry : files.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        return output.toByteArray();
    }
    @FunctionalInterface private interface Mutation { void apply(ByteBuffer bytes, int at); }
    private static void mutateCentral(byte[] bytes, String name, Mutation mutation) {
        var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int at = 0; at < bytes.length - 46; at++) if (data.getInt(at) == 0x02014b50) {
            int length = Short.toUnsignedInt(data.getShort(at + 28));
            if (name.equals(new String(bytes, at + 46, length, StandardCharsets.UTF_8))) { mutation.apply(data, at); return; }
        }
        throw new AssertionError("Missing central member " + name);
    }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void output(Path directory, boolean texture) throws IOException {
        Files.createDirectories(directory);
        Files.write(directory.resolve("model.glb"), ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putInt(0x46546c67).putInt(2).putInt(20).array());
        var metadata = JsonParser.parseString("{\"version\":1,\"bones\":[],\"clips\":[{\"name\":\"mmd_neutral\"}],\"warnings\":[],\"textures\":[]}").getAsJsonObject();
        if (texture) metadata.getAsJsonArray("textures").add(JsonParser.parseString("{\"sourcePath\":\"color.png\",\"assetPath\":\"textures/color.png\"}"));
        Files.writeString(directory.resolve("metadata.json"), metadata.toString());
    }
}
