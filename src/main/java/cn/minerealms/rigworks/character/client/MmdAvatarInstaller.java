package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.AssimpLoader;
import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.core.ModelImportMode;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.rigworks.RigWorksConfig;
import cn.minerealms.rigworks.character.core.CharacterVisualConfig;
import cn.minerealms.rigworks.character.importing.AvatarImportContext;
import cn.minerealms.rigworks.debug.RigWorksDiagnostics;
import cn.minerealms.rigworks.mmd.MmdAssetCache;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;

/** Publishes ordinary GLB/profile assets; no MMD-specific evaluator survives the import. */
public final class MmdAvatarInstaller {
    private record Prepared(AssimpScene scene, MmdAssetCache.Imported asset, CharacterVisualConfig visual, String profileId) {}
    private static Prepared pending;
    private MmdAvatarInstaller() {}

    public static synchronized AssimpScene prepare(AvatarImportContext source) throws IOException {
        pending = null;
        long started = System.nanoTime();
        var root = FMLPaths.CONFIGDIR.get().resolve("rigworks").resolve("mmd-assets");
        var asset = new MmdAssetCache(root).importModel(source);
        var scene = new AssimpModelLoader().loadModel(asset.model(), ModelImportMode.DYNAMIC);
        if (scene == null || scene.getMeshes().isEmpty() || !scene.hasSkeleton()) throw new IOException("Converted MMD asset has no skinned scene");
        var authored = CharacterWorkbenchSources.loadLocal(asset.model(), "rigworks:models/mmd/pending.glb", asset.modelHash());
        if (authored == null) throw new IOException("Converted MMD asset has no profile");
        String modelId = MmdAssetCache.json(asset.model().resolveSibling("profile.json")).get("model").getAsString();
        var visual = CharacterWorkbenchSources.rebind(authored.visual(), modelId);
        cn.minerealms.rigworks.mmd.MmdConvertedAssets.validate(scene, visual);
        String key = new ResourceLocation(modelId).getPath().split("/")[3];
        String profileId = "rigworks:imported/mmd/" + key + "/profile.json";
        if (asset.metadata().has("warnings")) for (var warning : asset.metadata().getAsJsonArray("warnings"))
            AssimpLoader.LOGGER.warn("MMD import source={} warning={}", source.name(), warning.getAsString());
        RigWorksDiagnostics.debug("MMD prepared source={} reused={} meshes={} clips={} asset={} elapsedMs={}",
                source.name(), asset.reused(), scene.getMeshes().size(), scene.getAnimations().size(), asset.assetHash(),
                (System.nanoTime() - started) / 1_000_000);
        pending = new Prepared(scene, asset, visual, profileId);
        return scene;
    }

    public static synchronized void install(String name, AssimpScene scene) throws IOException {
        Prepared prepared = pending;
        if (prepared == null || prepared.scene() != scene) throw new IOException("The prepared MMD import was superseded");
        pending = null;
        var model = new ResourceLocation(prepared.visual().profile().model());
        var snapshot = CharacterVisualOverrides.snapshotImportedAvatar(prepared.profileId(), model.toString());
        String identity = AssimpModelLoader.canonicalIdentity(model);
        var oldScene = AssimpModelLoader.getCachedModel(identity, ModelImportMode.DYNAMIC);
        String oldProfile = RigWorksConfig.CLIENT.profile.get();
        boolean oldEnabled = RigWorksConfig.CLIENT.enabled.get();
        var oldMode = RigWorksConfig.CLIENT.animationMode.get();
        try {
            var saved = savedConfiguration(snapshot.profileJson(), model.toString(), prepared.asset().modelHash());
            var visual = saved == null ? prepared.visual() : saved;
            CharacterVisualOverrides.asset(model.toString(), prepared.asset().model());
            if (oldScene == null || !prepared.asset().model().equals(oldScene.getSourcePath())) AssimpModelLoader.registerModel(model, scene);
            RigWorksWorkbenchExtension.applyCharacter(prepared.profileId(), visual, null);
            boolean hasMotions = prepared.asset().metadata().getAsJsonArray("clips").size() > 1;
            var mode = saved != null ? oldMode : hasMotions ? RigWorksConfig.CharacterAnimationMode.CLIPS : RigWorksConfig.CharacterAnimationMode.DEFAULT;
            if (!RigWorksConfig.tryUpdate(edit -> edit.set(RigWorksConfig.CLIENT.animationMode, mode)))
                throw new IOException(RigWorksConfig.lastUpdateError());
            LocalPlayerCharacter.refreshConfiguration();
            CharacterVisualOverrides.rememberImportedMmdProfile(prepared.profileId(), name);
        } catch (IOException | RuntimeException failure) {
            try { CharacterVisualOverrides.restoreYsmImport(snapshot); }
            catch (IOException | RuntimeException rollback) { failure.addSuppressed(rollback); }
            try {
                if (oldScene == null) AssimpModelLoader.removeCachedModel(identity); else AssimpModelLoader.registerModel(model, oldScene);
                if (!RigWorksConfig.tryUpdate(edit -> edit.set(RigWorksConfig.CLIENT.profile, oldProfile)
                        .set(RigWorksConfig.CLIENT.enabled, oldEnabled).set(RigWorksConfig.CLIENT.animationMode, oldMode)))
                    throw new IOException(RigWorksConfig.lastUpdateError());
                LocalPlayerCharacter.refreshConfiguration(); LocalPlayerCharacter.reloadVisual();
            } catch (IOException | RuntimeException rollback) { failure.addSuppressed(rollback); }
            AssimpLoader.LOGGER.warn("MMD apply failed source={} profile={}; previous selection restored", name, prepared.profileId(), failure);
            throw failure;
        }
    }

    static CharacterVisualConfig savedConfiguration(String json, String model, String hash) throws IOException {
        if (json == null) return null;
        try {
            var visual = CharacterVisualConfig.parse(json);
            return visual.profile().model().equals(model) && visual.modelHash().equals(hash) ? visual : null;
        } catch (RuntimeException invalid) {
            throw new IOException("Saved MMD character configuration is invalid; previous selection retained", invalid);
        }
    }
}
