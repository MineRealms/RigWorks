package cn.minerealms.rigworks.mmd;

import cn.minerealms.rigworks.character.importing.AvatarImportContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MmdAssetCacheTest {
    @TempDir Path directory;

    @Test void conversionIsCachedAndTexturesAreTrackedWithoutRetainingSourcePathsInTheBundle() throws Exception {
        Path source = Files.createDirectories(directory.resolve("source")).resolve("model.pmx");
        Files.write(source, new byte[]{1, 2, 3});
        Path texture = Files.write(source.resolveSibling("color.png"), new byte[]{5});
        var count = new AtomicInteger();
        var cache = new MmdAssetCache(directory.resolve("cache"), (bytes, path, output, options) -> {
            count.incrementAndGet(); output(output, true);
        });
        var context = AvatarImportContext.from(source, Files.readAllBytes(source));
        var first = cache.importModel(context);
        assertFalse(first.reused());
        assertArrayEquals(new byte[]{5}, Files.readAllBytes(first.model().resolveSibling("textures/color.png")));
        assertTrue(cache.importModel(context).reused()); assertEquals(1, count.get());
        Files.write(texture, new byte[]{6});
        var second = cache.importModel(context);
        assertFalse(second.reused()); assertEquals(2, count.get());
        assertNotEquals(first.assetHash(), second.assetHash());
        assertArrayEquals(new byte[]{6}, Files.readAllBytes(second.model().resolveSibling("textures/color.png")));
        String profile = Files.readString(second.model().resolveSibling("profile.json"));
        assertFalse(profile.contains(source.toString()));
        Files.delete(source); Files.delete(texture);
        assertTrue(Files.isRegularFile(first.model()));
        assertTrue(Files.isRegularFile(second.model()));
    }

    @Test void aMissingTextureBecomingAvailableInvalidatesThePreviousConversion() throws Exception {
        Path source = Files.write(directory.resolve("model.pmx"), new byte[]{1});
        var count = new AtomicInteger();
        var cache = new MmdAssetCache(directory.resolve("cache"), (bytes, path, output, options) -> {
            count.incrementAndGet(); output(output, true);
        });
        var context = AvatarImportContext.from(source, new byte[]{1});
        var first = cache.importModel(context);
        assertEquals(1, first.metadata().getAsJsonArray("warnings").size());
        assertTrue(cache.importModel(context).reused());
        Files.write(directory.resolve("color.png"), new byte[]{7});
        var second = cache.importModel(context);
        assertFalse(second.reused()); assertEquals(2, count.get());
        assertEquals(0, second.metadata().getAsJsonArray("warnings").size());
    }

    @Test void vmdContentsAndSettingsParticipateInIdentityAndNativeReceivesPrivateCopies() throws Exception {
        Path source = Files.write(directory.resolve("model.pmx"), new byte[]{1});
        Path motion = Files.write(directory.resolve("walk.vmd"), new byte[]{2});
        var count = new AtomicInteger();
        var cache = new MmdAssetCache(directory.resolve("cache"), (bytes, path, output, options) -> {
            var settings = JsonParser.parseString(options).getAsJsonObject();
            Path staged = Path.of(settings.getAsJsonArray("motions").get(0).getAsJsonObject().get("path").getAsString());
            assertTrue(staged.startsWith(output)); assertNotEquals(motion, staged);
            assertArrayEquals(Files.readAllBytes(motion), Files.readAllBytes(staged));
            count.incrementAndGet(); output(output, false);
        });
        var context = AvatarImportContext.from(source, new byte[]{1});
        var first = cache.importModel(context);
        assertFalse(Files.exists(first.model().resolveSibling("input")));
        Files.write(motion, new byte[]{3});
        assertFalse(cache.importModel(context).reused()); assertEquals(2, count.get());
        Files.writeString(directory.resolve("model.mmd-import.json"), "{\"height\":2,\"motions\":[]}");
        var settings = MmdImportSettings.read(context);
        assertEquals(2, settings.height()); assertTrue(settings.motions().isEmpty());
    }

    @Test void partialNativeFailureCannotPublishAnIndexOrACharacter() throws Exception {
        Path root = directory.resolve("cache");
        var cache = new MmdAssetCache(root, (bytes, path, output, options) -> {
            Files.write(output.resolve("model.glb"), new byte[]{1});
            throw new IOException("Invalid IK link");
        });
        var error = assertThrows(IOException.class,
                () -> cache.importModel(new AvatarImportContext("broken.pmx", new byte[]{1}, null)));
        assertEquals("Invalid IK link", error.getMessage());
        try (var paths = Files.list(root)) { assertEquals(0, paths.count()); }
    }

    @Test void reimportRepairsCorruptImmutableAssetsAndUnrelatedCacheEntriesSurvive() throws Exception {
        Path source = Files.write(directory.resolve("model.pmx"), new byte[]{1});
        var count = new AtomicInteger(); Path root = directory.resolve("cache");
        var cache = new MmdAssetCache(root, (bytes, path, output, options) -> { count.incrementAndGet(); output(output, false); });
        var context = AvatarImportContext.from(source, new byte[]{1});
        var first = cache.importModel(context);
        Path unrelated = Files.write(root.resolve("unrelated.txt"), new byte[]{9});
        Files.write(first.model(), new byte[]{0});
        var repaired = cache.importModel(context);
        assertFalse(repaired.reused()); assertEquals(2, count.get());
        assertEquals(first.assetHash(), repaired.assetHash());
        assertEquals(first.modelHash(), MmdAssetCache.hash(repaired.model()));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(unrelated));
        try (var entries = Files.list(root)) { assertTrue(entries.noneMatch(path -> path.getFileName().toString().startsWith(".mmd-"))); }
        assertTrue(cache.importModel(context).reused());
    }

    @Test void sourceAuthoredMotionPathsAndUnknownSettingsAreRejected() throws Exception {
        Path source = Files.write(directory.resolve("model.pmx"), new byte[]{1});
        var context = AvatarImportContext.from(source, new byte[]{1});
        Path settings = directory.resolve("model.mmd-import.json");
        for (String json : new String[]{"{\"motions\":[{\"path\":\"../escape.vmd\"}]}",
                "{\"sampleRate\":60.5}", "{\"height\":0}", "{\"typo\":1}"}) {
            Files.writeString(settings, json);
            assertThrows(IOException.class, () -> MmdImportSettings.read(context), json);
        }
    }

    @Test void aggregateTextureBudgetFailsBeforePublishingAndLeavesNoPartialBundle() throws Exception {
        Path source = Files.write(directory.resolve("model.pmx"), new byte[]{1});
        Files.write(directory.resolve("color.png"), new byte[2500]);
        Files.write(directory.resolve("second.png"), new byte[2500]);
        Path root = directory.resolve("cache");
        var cache = new MmdAssetCache(root, (bytes, path, output, options) -> {
            output(output, true);
            var metadata = MmdAssetCache.json(output.resolve("metadata.json"));
            metadata.getAsJsonArray("textures").add(JsonParser.parseString("{\"sourcePath\":\"second.png\",\"assetPath\":\"textures/second.png\"}"));
            Files.writeString(output.resolve("metadata.json"), metadata.toString());
        }, 4096);
        var error = assertThrows(IOException.class, () -> cache.importModel(AvatarImportContext.from(source, new byte[]{1})));
        assertTrue(error.getMessage().contains("textures exceed the bundle budget"));
        try (var entries = Files.list(root)) { assertEquals(0, entries.count()); }
        assertEquals(2500, Files.size(directory.resolve("color.png")));
        assertEquals(2500, Files.size(directory.resolve("second.png")));
    }

    private static void output(Path directory, boolean texture) throws IOException {
        Files.createDirectories(directory);
        byte[] header = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46546c67).putInt(2).putInt(20).array();
        Files.write(directory.resolve("model.glb"), header);
        JsonObject metadata = JsonParser.parseString("{\"version\":1,\"bones\":[],\"clips\":[{\"name\":\"mmd_neutral\"}],\"warnings\":[],\"textures\":[]}").getAsJsonObject();
        if (texture) metadata.getAsJsonArray("textures").add(JsonParser.parseString("{\"sourcePath\":\"color.png\",\"assetPath\":\"textures/color.png\"}"));
        Files.writeString(directory.resolve("metadata.json"), metadata.toString());
    }
}
