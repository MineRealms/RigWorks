package cn.minerealms.rigworks.ysm.asset;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RwymPortableAssetsTest {
    private static byte[] encoded;
    private static String hash;

    @BeforeAll static void convertFixture() throws Exception {
        byte[] source;
        try (var input = RwymPortableAssetsTest.class.getResourceAsStream("/ysm/Flandre_Scarlet.ysm")) {
            assertNotNull(input);
            source = input.readAllBytes();
        }
        assertTrue(RwymNative.available(), RwymNative::failure);
        encoded = RwymNative.convert(source);
        hash = RwymAssetCache.hash(encoded);
    }

    @Test void stagesCanonicalAssetAndReopensWithNoAuthorCache(@TempDir Path directory) throws Exception {
        Path canonical = directory.resolve("author-cache.rwym");
        Files.write(canonical, encoded);
        Path project = Files.createDirectory(directory.resolve("project"));
        var prepared = RwymPortableAssets.prepare(project, canonical, hash);
        assertEquals(project.resolve(RwymPortableAssets.ASSET_PATH), prepared.assetFile());
        assertArrayEquals(encoded, Files.readAllBytes(prepared.assetFile()));
        assertEquals(hash, prepared.hash());
        RwymPortableAssets.validateMetadata(prepared.metadata(), hash);

        Files.delete(canonical);
        var reopened = RwymPortableAssets.prepare(project, null, hash);
        assertEquals(prepared.model().animationNames(), reopened.model().animationNames());
        assertEquals(prepared.model().bones(), reopened.model().bones());
        assertEquals(prepared.model().controllers().size(), reopened.model().controllers().size());
        assertEquals(312, reopened.model().bones().size());
        assertEquals(195, reopened.model().animations().size());
        assertTrue(reopened.model().animationNames().contains("tac:reload:rifle"));
        assertEquals(prepared.model().animations().get(0).channels().get(0).frames().get(0).postValues(),
                reopened.model().animations().get(0).channels().get(0).frames().get(0).postValues());
        assertEquals(4, reopened.metadata().size());
    }

    @Test void unsavedProjectCanReadCanonicalAsset(@TempDir Path directory) throws Exception {
        Path canonical = directory.resolve("author-cache.rwym");
        Files.write(canonical, encoded);
        var prepared = RwymPortableAssets.prepare(null, canonical, hash);
        assertEquals(canonical, prepared.assetFile());
        assertFalse(Files.exists(directory.resolve("extensions")));
    }

    @Test void packagedAssetWinsAndCorruptionCannotFallBackToAnAuthorCopy(@TempDir Path project) throws Exception {
        Path canonical = project.resolve("good-author-copy.rwym");
        Files.write(canonical, encoded);
        var prepared = RwymPortableAssets.prepare(project, canonical, hash);
        byte[] corrupt = encoded.clone();
        corrupt[corrupt.length - 1] ^= 1;
        Files.write(prepared.assetFile(), corrupt);
        assertThrows(IOException.class, () -> RwymPortableAssets.prepare(project, canonical, hash));
        assertArrayEquals(corrupt, Files.readAllBytes(prepared.assetFile()));
    }

    @Test void badCanonicalAssetCannotLeaveAPartialPackagedAsset(@TempDir Path project) throws Exception {
        Path canonical = project.resolve("incorrect.rwym");
        Files.write(canonical, new byte[] {1, 2, 3});
        assertThrows(IOException.class, () -> RwymPortableAssets.prepare(project, canonical, hash));
        assertFalse(Files.exists(project.resolve(RwymPortableAssets.ASSET_PATH)));
        assertFalse(Files.exists(project.resolve("extensions")));
    }

    @Test void checksumIsVerifiedEvenWhenTheExpectedHashMatchesCorruptBytes(@TempDir Path directory) throws Exception {
        byte[] corrupt = encoded.clone();
        corrupt[corrupt.length - 1] ^= 1;
        Path canonical = directory.resolve("corrupt.rwym");
        Files.write(canonical, corrupt);
        assertThrows(IOException.class,
                () -> RwymPortableAssets.prepare(null, canonical, RwymAssetCache.hash(corrupt)));
    }

    @Test void rejectsMissingAssetAndDirectoryConflicts(@TempDir Path project) throws Exception {
        assertThrows(IOException.class, () -> RwymPortableAssets.prepare(project, null, hash));
        Files.write(project.resolve("extensions"), new byte[] {1});
        assertThrows(IOException.class, () -> RwymPortableAssets.prepare(project, null, hash));
        assertThrows(IOException.class, () -> RwymPortableAssets.prepare(project.resolve("missing"), null, hash));
    }

    @Test void metadataRequiresTheFixedRelativeAssetPathAndIdentity() throws Exception {
        JsonObject good = new RwymPortableAssets.Prepared(Path.of("model.rwym"), hash, null).metadata();
        RwymPortableAssets.validateMetadata(good, hash);
        JsonObject traversal = good.deepCopy();
        traversal.addProperty("asset", "../../model.rwym");
        assertThrows(IOException.class, () -> RwymPortableAssets.validateMetadata(traversal, hash));
        JsonObject wrongHash = good.deepCopy();
        wrongHash.addProperty("sha256", "0".repeat(64));
        assertThrows(IOException.class, () -> RwymPortableAssets.validateMetadata(wrongHash, hash));
        JsonObject futureVersion = good.deepCopy();
        futureVersion.addProperty("version", 2);
        assertThrows(IOException.class, () -> RwymPortableAssets.validateMetadata(futureVersion, hash));
        JsonObject malformedVersion = good.deepCopy();
        malformedVersion.addProperty("version", "invalid");
        assertThrows(IOException.class, () -> RwymPortableAssets.validateMetadata(malformedVersion, hash));
        JsonObject fractionalVersion = good.deepCopy();
        fractionalVersion.addProperty("version", 1.5);
        assertThrows(IOException.class, () -> RwymPortableAssets.validateMetadata(fractionalVersion, hash));
        JsonObject missing = good.deepCopy();
        missing.remove("sha256");
        assertThrows(IOException.class, () -> RwymPortableAssets.validateMetadata(missing, hash));
        assertThrows(IOException.class, () -> RwymPortableAssets.validateMetadata(null, hash));
        assertThrows(IOException.class, () -> RwymPortableAssets.validateMetadata(good, ""));
    }
}
