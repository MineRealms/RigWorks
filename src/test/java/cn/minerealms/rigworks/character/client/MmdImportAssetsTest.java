package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.animation.AnimationState;
import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.core.ModelImportMode;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.assimploader.rendering.CpuSkinning;
import cn.minerealms.assimploader.workbench.io.WorkbenchProjectStore;
import cn.minerealms.rigworks.character.importing.AvatarImportContext;
import cn.minerealms.rigworks.mmd.MmdAssetCache;
import cn.minerealms.rigworks.mmd.MmdConvertedAssets;
import cn.minerealms.rigworks.mmd.MmdNative;
import cn.minerealms.rigworks.mmd.MmdTestSources;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MmdImportAssetsTest {
    @TempDir Path directory;

    @Test void convertedSkinMorphsAndVmdPlayInTheRealEngineAndThePackageSurvivesSourceRemoval() throws Exception {
        assertTrue(MmdNative.available(), MmdNative::failure);
        Path source = MmdTestSources.write(directory.resolve("source"));
        var cache = new MmdAssetCache(directory.resolve("cache"));
        var asset = cache.importModel(AvatarImportContext.from(source, Files.readAllBytes(source)));
        var scene = new AssimpModelLoader().loadModel(asset.model(), ModelImportMode.DYNAMIC);
        validate(scene, asset.model());
        var neutral = bounds(scene, "mmd_neutral", 0);
        var moving = bounds(scene, "walk", .5f);
        assertEquals(0, neutral[0].y, 1e-5); assertEquals(1.85, neutral[1].y, 1e-5);
        assertTrue(moving[1].x > neutral[1].x + .1, "Blended bone weights must affect the rendered vertices");
        assertTrue(moving[1].y > neutral[1].y + .03, "VMD Morph must survive engine import and playback");

        var store = new WorkbenchProjectStore(directory.resolve("projects"));
        var project = store.importAsset(asset.model());
        Path zip = store.exportZip(project, project.document(), directory.resolve("avatar.rig.zip"));
        try (var archive = new ZipFile(zip.toFile())) {
            for (String name : new String[]{"assets/model.glb", "assets/profile.json", "assets/rig.json", "assets/physics.json", "assets/equipment.json"})
                assertNotNull(archive.getEntry(name), name);
            assertTrue(archive.stream().noneMatch(entry -> entry.getName().endsWith(".vmd") || entry.getName().endsWith(".pmd")));
        }
        Files.delete(source); Files.delete(source.resolveSibling("walk.vmd"));
        // Remove the converted author cache too: the ZIP must be sufficient by itself.
        try (var files = Files.walk(asset.model().getParent())) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
        }
        var restored = new WorkbenchProjectStore(directory.resolve("another-install")).importZip(zip);
        validate(restored.scene(), restored.scene().getSourcePath());
        assertEquals(moving[1].x, bounds(restored.scene(), "walk", .5f)[1].x, 1e-5);
    }

    @Test void optionalRealModelLoadsWithTheOrdinaryRigAndPhysics() throws Exception {
        String configured = System.getProperty("rigworks.mmd.modelFixture", "");
        assumeTrue(!configured.isBlank(), "Pass -PmmdModelFixture=<local PMD/PMX> to verify a non-redistributable source");
        Path source = Path.of(configured);
        var asset = new MmdAssetCache(directory.resolve("real-cache")).importModel(AvatarImportContext.from(source, Files.readAllBytes(source)));
        var scene = new AssimpModelLoader().loadModel(asset.model(), ModelImportMode.DYNAMIC);
        validate(scene, asset.model());
        var neutral = bounds(scene, "mmd_neutral", 0);
        assertEquals(0, neutral[0].y, .001); assertEquals(1.85, neutral[1].y, .001);
        for (var clip : scene.getAnimations()) {
            var posed = bounds(scene, clip.getName(), .25f);
            assertTrue(posed[0].isFinite() && posed[1].isFinite());
            assertTrue(posed[1].distance(posed[0]) < 8, "Exploded skin: " + clip.getName());
        }
        for (var item : asset.metadata().getAsJsonArray("textures")) {
            Path texture = asset.model().resolveSibling(item.getAsJsonObject().get("assetPath").getAsString());
            assertTrue(Files.isRegularFile(texture), "Missing captured texture " + texture);
            var image = javax.imageio.ImageIO.read(texture.toFile());
            assertNotNull(image); assertTrue(image.getWidth() > 0 && image.getHeight() > 0); image.flush();
        }
        var projects = new WorkbenchProjectStore(directory.resolve("real-projects"));
        var project = projects.importAsset(asset.model());
        Path archive = projects.exportZip(project, project.document(), directory.resolve("real-avatar.rig.zip"));
        var reopened = projects.importZip(archive);
        validate(reopened.scene(), reopened.scene().getSourcePath());
        for (var material : reopened.scene().getMaterials())
            if (material.getDiffuseTexture() != null)
                assertTrue(Files.isRegularFile(reopened.scene().getSourcePath().resolveSibling(material.getDiffuseTexture())));
        System.out.println("MMD fixture="+source.getFileName()+" meshes="+scene.getMeshCount()+" clips="+scene.getAnimations().size()
                +" bounds="+neutral[0]+" / "+neutral[1]+" warnings="+asset.metadata().getAsJsonArray("warnings"));
    }

    private static void validate(AssimpScene scene, Path model) throws Exception {
        assertTrue(scene.hasSkeleton());
        var hash = MmdAssetCache.hash(model);
        var authored = CharacterWorkbenchSources.loadLocal(model, "rigworks:models/mmd/test.glb", hash);
        assertNotNull(authored);
        MmdConvertedAssets.validate(scene, authored.visual());
        assertEquals(authored.visual(), MmdAvatarInstaller.savedConfiguration(authored.visual().json(), "rigworks:models/mmd/test.glb", hash));
        assertNull(MmdAvatarInstaller.savedConfiguration(authored.visual().json(), "rigworks:models/mmd/test.glb", "f".repeat(64)));
    }

    private static Vector3f[] bounds(AssimpScene scene, String clip, float seconds) {
        var low = new Vector3f(Float.POSITIVE_INFINITY); var high = new Vector3f(Float.NEGATIVE_INFINITY);
        try (var state = new AnimationState("mmd-asset-test", scene)) {
            state.initialize(); assertTrue(state.transitionTo(clip, 0)); state.update(seconds);
            for (var mesh : scene.getMeshes()) {
                var positions = CpuSkinning.geometry(state, mesh).positions();
                for(int i=0;i<positions.length;i+=3) {
                    var p = new Vector3f(positions[i],positions[i+1],positions[i+2]); assertTrue(p.isFinite()); low.min(p); high.max(p);
                }
            }
        }
        return new Vector3f[]{low, high};
    }
}
