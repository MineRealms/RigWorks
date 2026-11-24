package cn.minerealms.rigworks.ysm.asset;

import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.core.ModelImportMode;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.rigworks.ysm.model.YsmGlbWriter;
import cn.minerealms.rigworks.ysm.model.YsmModel;
import cn.minerealms.rigworks.ysm.runtime.YsmModelSource;
import cn.minerealms.rigworks.ysm.runtime.YsmRuntimeBridge;
import cn.minerealms.rigworks.debug.RigWorksDiagnostics;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** RWYM is canonical; a merged/skinned GLB is an internal, disposable engine render cache. */
public final class RwymAssetModels {
    public static final String RENDER_REVISION = "rigid-v4";
    private RwymAssetModels() {}

    public static AssimpScene load(ResourceLocation id, Path file) throws IOException {
        AssimpScene cached = AssimpModelLoader.getCachedModel(AssimpModelLoader.canonicalIdentity(id), ModelImportMode.DYNAMIC);
        if (cached != null && YsmRuntimeBridge.ownsScene(cached)) return cached;
        byte[] bytes = RwymAssetCache.read(file);
        var asset = RwymReader.read(bytes);
        YsmModel model = asset.mainModel();
        String hash = RwymAssetCache.hash(bytes);
        AssimpScene scene = loadRenderCache(file, hash, model);
        YsmRuntimeBridge.registerScene(scene, YsmModelSource.reflective().view(model));
        registerFirstPerson(scene, file, hash, asset);
        AssimpModelLoader.registerModel(id, scene);
        return scene;
    }

    public static synchronized AssimpScene loadRenderCache(Path assetFile, String assetHash, YsmModel model) throws IOException {
        return loadRenderCache(assetFile, assetHash, model, "main");
    }

    public static void registerFirstPerson(AssimpScene main, Path file, String hash, RwymReader.Asset asset) throws IOException {
        var model = asset.firstPersonModel();
        if (model == null || model.bones().stream().noneMatch(bone -> !bone.cubes().isEmpty())) return;
        var arms = loadRenderCache(file, hash, model, "arms");
        YsmRuntimeBridge.registerFirstPerson(main, arms, YsmModelSource.reflective().view(model));
    }

    public static void registerFirstPerson(AssimpScene main, Path file, String hash) throws IOException {
        registerFirstPerson(main, file, hash, RwymReader.read(RwymAssetCache.read(file)));
    }

    private static synchronized AssimpScene loadRenderCache(Path assetFile, String assetHash, YsmModel model,
                                                           String part) throws IOException {
        long started = System.nanoTime();
        if (assetHash == null || !assetHash.matches("[a-f0-9]{64}"))
            throw new IOException("Invalid YSM render-cache identity");
        Path renderFile = renderCachePath(assetFile, assetHash, part);
        Path checksumFile = renderFile.resolveSibling(renderFile.getFileName() + ".sha256");
        if (Files.isRegularFile(renderFile, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(checksumFile, LinkOption.NOFOLLOW_LINKS) && Files.size(checksumFile) == 64) {
            try {
                byte[] cached = RwymAssetCache.read(renderFile);
                if (RwymAssetCache.hash(cached).equals(Files.readString(checksumFile))) {
                    AssimpScene scene = new AssimpModelLoader().loadModel(renderFile, ModelImportMode.DYNAMIC);
                    if (scene != null && !scene.getMeshes().isEmpty()) {
                        RigWorksDiagnostics.debug("YSM render cache hit asset={} revision={} meshes={} elapsedMs={}",
                                assetHash, RENDER_REVISION, scene.getMeshes().size(), (System.nanoTime() - started) / 1_000_000);
                        return scene;
                    }
                }
            } catch (IOException invalidCache) {
                org.apache.logging.log4j.LogManager.getLogger("rigworks.ysm").warn(
                        "Rebuilding invalid render cache {} from canonical RWYM asset {}; no YSM decryption is required",
                        renderFile, assetFile, invalidCache);
            }
        }
        byte[] render = YsmGlbWriter.toRuntimeGlb(model);
        Files.createDirectories(renderFile.getParent());
        Path stagedRender = Files.createTempFile(renderFile.getParent(), ".rwym-render-", ".glb");
        Path stagedChecksum = Files.createTempFile(renderFile.getParent(), ".rwym-checksum-", ".tmp");
        try {
            Files.write(stagedRender, render);
            AssimpScene scene = new AssimpModelLoader().loadModel(stagedRender, ModelImportMode.DYNAMIC);
            if (scene == null || scene.getMeshes().isEmpty()) throw new IOException("Cannot initialize the RigWorks render asset");
            Files.writeString(stagedChecksum, RwymAssetCache.hash(render), java.nio.charset.StandardCharsets.US_ASCII);
            scene = publishRenderCache(renderFile, checksumFile, stagedRender, stagedChecksum, () -> {
                AssimpScene published = new AssimpModelLoader().loadModel(renderFile, ModelImportMode.DYNAMIC);
                if (published == null || published.getMeshes().isEmpty())
                    throw new IOException("Cannot initialize the published RigWorks render asset");
                return published;
            });
            RigWorksDiagnostics.debug("YSM render cache built asset={} revision={} bytes={} meshes={} elapsedMs={}",
                    assetHash, RENDER_REVISION, render.length, scene.getMeshes().size(), (System.nanoTime() - started) / 1_000_000);
            return scene;
        } finally {
            Files.deleteIfExists(stagedRender);
            Files.deleteIfExists(stagedChecksum);
        }
    }

    @FunctionalInterface
    interface PublishedLoader<T> { T load() throws IOException; }

    static <T> T publishRenderCache(Path renderFile, Path checksumFile, Path stagedRender,
                                    Path stagedChecksum, PublishedLoader<T> loader) throws IOException {
        Path backupRender = Files.createTempFile(renderFile.getParent(), ".rwym-old-render-", ".tmp");
        Path backupChecksum = Files.createTempFile(renderFile.getParent(), ".rwym-old-checksum-", ".tmp");
        boolean renderBackedUp = false, checksumBackedUp = false;
        boolean renderPublished = false, checksumPublished = false;
        boolean success = false;
        try {
            if (Files.isRegularFile(renderFile, LinkOption.NOFOLLOW_LINKS)) {
                Files.move(renderFile, backupRender, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                renderBackedUp = true;
            }
            if (Files.isRegularFile(checksumFile, LinkOption.NOFOLLOW_LINKS)) {
                Files.move(checksumFile, backupChecksum, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                checksumBackedUp = true;
            }
            Files.move(stagedRender, renderFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            renderPublished = true;
            Files.move(stagedChecksum, checksumFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            checksumPublished = true;
            T result = loader.load();
            success = true;
            return result;
        } catch (IOException | RuntimeException failure) {
            try {
                if (checksumPublished) Files.deleteIfExists(checksumFile);
                if (checksumBackedUp) {
                    Files.move(backupChecksum, checksumFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    checksumBackedUp = false;
                }
            } catch (IOException rollback) { failure.addSuppressed(rollback); }
            try {
                if (renderPublished) Files.deleteIfExists(renderFile);
                if (renderBackedUp) {
                    Files.move(backupRender, renderFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    renderBackedUp = false;
                }
            } catch (IOException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        } finally {
            if (success || !renderBackedUp) deleteBackup(backupRender);
            if (success || !checksumBackedUp) deleteBackup(backupChecksum);
        }
    }

    private static void deleteBackup(Path backup) {
        try { Files.deleteIfExists(backup); }
        catch (IOException cleanup) {
            org.apache.logging.log4j.LogManager.getLogger("rigworks.ysm")
                    .warn("Could not remove YSM render-cache backup {}", backup, cleanup);
        }
    }

    public static void discardNew(Path assetFile, String assetHash) throws IOException {
        for (String part : java.util.List.of("main", "arms")) {
            Path render = renderCachePath(assetFile, assetHash, part);
            Files.deleteIfExists(render.resolveSibling(render.getFileName() + ".sha256"));
            Files.deleteIfExists(render);
        }
        Files.deleteIfExists(assetFile);
    }

    static Path renderCachePath(Path assetFile, String assetHash) {
        return renderCachePath(assetFile, assetHash, "main");
    }

    static Path renderCachePath(Path assetFile, String assetHash, String part) {
        if (assetHash == null || !assetHash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid YSM render-cache identity");
        if (!part.equals("main") && !part.equals("arms")) throw new IllegalArgumentException("Unknown YSM model part");
        return assetFile.getParent().resolve("r")
                .resolve(assetHash).resolve(part.equals("main") ? RENDER_REVISION : RENDER_REVISION + "-arms").resolve("model.glb");
    }
}
