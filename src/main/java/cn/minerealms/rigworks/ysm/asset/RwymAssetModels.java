package cn.minerealms.rigworks.ysm.asset;

import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.core.ModelImportMode;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.rigworks.ysm.model.YsmGlbWriter;
import cn.minerealms.rigworks.ysm.model.YsmModel;
import cn.minerealms.rigworks.ysm.runtime.YsmModelSource;
import cn.minerealms.rigworks.ysm.runtime.YsmRuntimeBridge;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** RWYM is canonical; a merged/skinned GLB is an internal, disposable engine render cache. */
public final class RwymAssetModels {
    public static final String RENDER_REVISION = "rigid-v1";
    private RwymAssetModels() {}

    public static AssimpScene load(ResourceLocation id, Path file) throws IOException {
        AssimpScene cached = AssimpModelLoader.getCachedModel(AssimpModelLoader.canonicalIdentity(id), ModelImportMode.DYNAMIC);
        if (cached != null && YsmRuntimeBridge.ownsScene(cached)) return cached;
        byte[] bytes = RwymAssetCache.read(file);
        YsmModel model = RwymReader.read(bytes).mainModel();
        AssimpScene scene = loadRenderCache(file, RwymAssetCache.hash(bytes), model);
        YsmRuntimeBridge.registerScene(scene, YsmModelSource.reflective().view(model));
        AssimpModelLoader.registerModel(id, scene);
        return scene;
    }

    public static AssimpScene loadRenderCache(Path assetFile, String assetHash, YsmModel model) throws IOException {
        Path renderFile = assetFile.getParent().resolve("render-cache").resolve(assetHash + "-" + RENDER_REVISION + ".glb");
        Path checksumFile = renderFile.resolveSibling(renderFile.getFileName() + ".sha256");
        if (Files.isRegularFile(renderFile, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(checksumFile, LinkOption.NOFOLLOW_LINKS) && Files.size(checksumFile) == 64) {
            byte[] cached = RwymAssetCache.read(renderFile);
            if (RwymAssetCache.hash(cached).equals(Files.readString(checksumFile))) {
                AssimpScene scene = new AssimpModelLoader().loadModel(renderFile, ModelImportMode.DYNAMIC);
                if (scene != null && !scene.getMeshes().isEmpty()) return scene;
            }
        }
        byte[] render = YsmGlbWriter.toRuntimeGlb(model);
        RwymAssetCache.writeAtomic(renderFile, render);
        AssimpScene scene = new AssimpModelLoader().loadModel(renderFile, ModelImportMode.DYNAMIC);
        if (scene == null || scene.getMeshes().isEmpty()) throw new IOException("Cannot initialize the RigWorks render asset");
        RwymAssetCache.writeAtomic(checksumFile, RwymAssetCache.hash(render).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return scene;
    }
}
