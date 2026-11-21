package cn.minerealms.rigworks.ysm.asset;

import cn.minerealms.rigworks.ysm.java.YsmJavaDecoder;
import cn.minerealms.rigworks.ysm.model.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RwymAssetPipelineTest {
    private static byte[] source, encoded;
    private static RwymReader.Asset asset;

    @BeforeAll static void convertFixture() throws Exception {
        try (var input = RwymAssetPipelineTest.class.getResourceAsStream("/ysm/Flandre_Scarlet.ysm")) {
            assertNotNull(input);
            source = input.readAllBytes();
        }
        assertTrue(RwymNative.available(), RwymNative::failure);
        encoded = RwymNative.convert(source);
        asset = RwymReader.read(encoded);
    }

    @Test void cppConversionPreservesGeometryUvsTexturesAndAuthoredKeys() throws Exception {
        YsmModel reference = YsmBinaryDecoder.decode(YsmJavaDecoder.decode(source));
        YsmModel actual = asset.mainModel();
        assertEquals(9, asset.sourceFormat());
        assertEquals(2, asset.geometries().size());
        assertEquals(312, actual.bones().size());
        assertEquals(4986, asset.geometries().stream().flatMap(g -> g.bones().stream())
                .flatMap(b -> b.cubes().stream()).mapToInt(c -> c.faces().size()).sum());
        assertEquals(reference.bones().size(), actual.bones().size());
        for (int i = 0; i < reference.bones().size(); i++) {
            YsmBone expected = reference.bones().get(i), bone = actual.bones().get(i);
            assertEquals(expected.name(), bone.name());
            assertEquals(expected.parent(), bone.parent());
            assertEquals(expected.pivot(), bone.pivot());
            assertEquals(expected.rotation(), bone.rotation());
            assertEquals(expected.cubes().size(), bone.cubes().size());
            for (int c = 0; c < expected.cubes().size(); c++) {
                var expectedFaces = expected.cubes().get(c).faces().values().iterator();
                var faces = bone.cubes().get(c).faces().values().iterator();
                while (expectedFaces.hasNext()) {
                    YsmFace e = expectedFaces.next(), f = faces.next();
                    assertArrayEquals(e.positions(), f.positions());
                    assertArrayEquals(e.normal(), f.normal());
                    assertArrayEquals(e.u(), f.u());
                    assertArrayEquals(e.v(), f.v());
                }
                assertFalse(faces.hasNext());
            }
        }
        assertEquals(reference.textures().keySet(), actual.textures().keySet());
        for (var entry : reference.textures().entrySet())
            assertArrayEquals(entry.getValue().pngBytes(), actual.textures().get(entry.getKey()).pngBytes());
        assertEquals(195, asset.clips().size());
        assertEquals(7, asset.clips().stream().map(YsmAnimation::category).distinct().count());
        assertEquals(reference.animationClips().size(), actual.animationClips().size());
        int frames = 0, preValues = 0;
        for (int i = 0; i < reference.animationClips().size(); i++) {
            var expected = reference.animationClips().get(i);
            var clip = actual.animationClips().get(i);
            assertEquals(expected.name(), clip.name());
            assertEquals(expected.category(), clip.category());
            assertEquals(expected.duration(), clip.duration());
            assertEquals(expected.loopMode(), clip.loopMode());
            assertEquals(expected.channels().size(), clip.channels().size());
            for (int c = 0; c < expected.channels().size(); c++) {
                var e = expected.channels().get(c); var channel = clip.channels().get(c);
                assertEquals(e.bone(), channel.bone());
                assertEquals(e.path(), channel.path());
                assertEquals(e.frames().size(), channel.frames().size());
                for (int k = 0; k < e.frames().size(); k++) {
                    var ef = e.frames().get(k); var key = channel.frames().get(k);
                    assertEquals(ef.time(), key.time());
                    assertEquals(ef.interpolationMode(), key.interpolationMode());
                    assertEquals(ef.preValues(), key.preValues());
                    assertEquals(ef.postValues(), key.postValues());
                    frames++; if (key.hasPreValue()) preValues++;
                }
            }
        }
        assertEquals(14319, frames);
        assertEquals(229, preValues);
    }

    @Test void contentCacheConvertsOnceAndCanBeReopenedWithoutAConverter(@TempDir Path directory) throws Exception {
        AtomicInteger conversions = new AtomicInteger();
        var cache = new RwymAssetCache(directory, bytes -> { conversions.incrementAndGet(); return encoded; });
        var first = cache.importYsm(source);
        assertFalse(first.reused());
        assertEquals(1, conversions.get());
        var reopened = new RwymAssetCache(directory, bytes -> { throw new AssertionError("must use the disk asset"); });
        var second = reopened.importYsm(source);
        assertTrue(second.reused());
        assertEquals(first.file(), second.file());
        assertEquals(first.assetHash(), second.assetHash());
        assertEquals(first.assetHash(), RwymAssetCache.hash(RwymAssetCache.read(first.file())));
        assertEquals(asset.mainModel().bones().size(), RwymReader.read(Files.readAllBytes(first.file())).mainModel().bones().size());
    }

    @Test void failedConversionCannotReplaceAWorkingAsset(@TempDir Path directory) throws Exception {
        var cache = new RwymAssetCache(directory, bytes -> encoded);
        var good = cache.importYsm(source);
        var failing = new RwymAssetCache(directory, bytes -> { throw new IOException("fixture conversion failed"); });
        assertThrows(IOException.class, () -> failing.importYsm(new byte[]{1, 2, 3}));
        assertArrayEquals(encoded, Files.readAllBytes(good.file()));
        byte[] corrupted = encoded.clone(); corrupted[corrupted.length - 1] ^= 1;
        Files.write(good.file(), corrupted);
        assertThrows(IOException.class, () -> cache.importYsm(source));
        try (var files = Files.list(good.file().getParent())) {
            assertEquals(1, files.filter(p -> p.toString().endsWith(".rwym")).count());
        }
    }

    @Test void renderCacheUsesAShortModelNameForWorkbenchCopies(@TempDir Path directory) {
        String hash = "a".repeat(64);
        Path asset = directory.resolve(hash + ".rwym");
        Path render = RwymAssetModels.renderCachePath(asset, hash);
        assertEquals("model.glb", render.getFileName().toString());
        assertEquals(RwymAssetModels.RENDER_REVISION, render.getParent().getFileName().toString());
        assertEquals(hash, render.getParent().getParent().getFileName().toString());
        assertThrows(IllegalArgumentException.class, () -> RwymAssetModels.renderCachePath(asset, "../escape"));
    }

    @Test void malformedDirectoryAndCorruptSectionsAreRejected() {
        byte[] corrupt = encoded.clone(); corrupt[corrupt.length - 1] ^= 1;
        assertThrows(IOException.class, () -> RwymReader.read(corrupt));
        byte[] outside = encoded.clone();
        ByteBuffer.wrap(outside).order(ByteOrder.LITTLE_ENDIAN).putLong(32, Long.MAX_VALUE);
        assertThrows(IOException.class, () -> RwymReader.read(outside));
        assertThrows(IOException.class, () -> RwymReader.read(new byte[24]));
    }
}
