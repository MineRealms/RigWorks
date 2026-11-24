package cn.minerealms.rigworks.ysm.runtime;

import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.core.ModelImportMode;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.rigworks.character.client.CharacterVisualOverrides;
import cn.minerealms.rigworks.character.client.LocalPlayerCharacter;
import cn.minerealms.rigworks.character.client.RigWorksWorkbenchExtension;
import cn.minerealms.rigworks.character.core.CharacterProfile;
import cn.minerealms.rigworks.character.core.CharacterVisualConfig;
import cn.minerealms.rigworks.debug.RigWorksDiagnostics;
import cn.minerealms.rigworks.RigWorksConfig;
import cn.minerealms.rigworks.ysm.asset.RwymAssetCache;
import cn.minerealms.rigworks.ysm.asset.RwymAssetModels;
import cn.minerealms.rigworks.ysm.asset.RwymNative;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Converts once, verifies both assets, then applies through RigWorks' existing character transaction. */
public final class YsmAvatarInstaller {
    private static Prepared pending;
    private YsmAvatarInstaller() {}

    public record Installed(CharacterVisualConfig config, String profileId, String modelId, String hash) {}
    public record Result(Installed installed, YsmModelSource.YsmModelView model) {
        public CharacterVisualConfig config() { return installed.config(); }
        public String profileId() { return installed.profileId(); }
    }
    private record Prepared(Path file, String sourceHash, String assetHash, int sourceFormat,
                            AssimpScene scene, YsmModelSource.YsmModelView model, boolean created) {}

    private static synchronized Prepared prepare(byte[] source) throws IOException {
        long started = System.nanoTime();
        if (source == null || source.length == 0 || source.length > RwymNative.MAX_SOURCE_BYTES)
            throw new IOException("YSM input must be between 1 byte and 256 MiB");
        String hash = RwymAssetCache.hash(source);
        if (pending != null && pending.sourceHash().equals(hash)
                && Files.isRegularFile(pending.file(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && pending.scene() != null && !pending.scene().getMeshes().isEmpty()) return pending;
        // A new import invalidates an older staged scene. Otherwise a failed second
        // import could still be applied later through the workbench callback.
        if (pending != null) pending = null;
        Path directory = FMLPaths.CONFIGDIR.get().resolve("rigworks").resolve("ysmodels");
        RwymAssetCache.Imported asset = new RwymAssetCache(directory).importYsm(source);
        try {
            var model = asset.asset().mainModel();
            AssimpScene scene = RwymAssetModels.loadRenderCache(asset.file(), asset.assetHash(), model);
            var view = YsmModelSource.reflective().view(model);
            YsmRuntimeBridge.registerScene(scene, view);
            RwymAssetModels.registerFirstPerson(scene, asset.file(), asset.assetHash(), asset.asset());
            RigWorksDiagnostics.debug("YSM prepared source={} cached={} format={} bones={} meshes={} clips={} controllers={} textures={} elapsedMs={}",
                    hash, asset.reused(), asset.asset().sourceFormat(), view.bones().size(), scene.getMeshes().size(),
                    view.animations().size(), view.controllers().size(), asset.asset().textures().size(),
                    (System.nanoTime() - started) / 1_000_000);
            pending = new Prepared(asset.file(), asset.sourceHash(), asset.assetHash(), asset.asset().sourceFormat(),
                    scene, view, asset.created());
            return pending;
        } catch (IOException | RuntimeException failure) {
            if (asset.created()) try { RwymAssetModels.discardNew(asset.file(), asset.assetHash()); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            org.apache.logging.log4j.LogManager.getLogger("rigworks.ysm").warn(
                    "YSM preparation failed source={} asset={} format={}; previous character retained",
                    hash, asset.file(), asset.asset().sourceFormat(), failure);
            throw failure;
        }
    }

    public static AssimpScene loadScene(String name, byte[] source) throws IOException { return prepare(source).scene(); }

    public static synchronized Result installFromYsmBytes(String name, byte[] source) throws IOException {
        return install(name, prepare(source));
    }

    public static synchronized Result installPrepared(String name, AssimpScene scene) throws IOException {
        Prepared prepared = pending;
        if (prepared == null || prepared.scene() != scene
                || !Files.isRegularFile(prepared.file(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IOException("The prepared YSM import was superseded; import it again");
        return install(name, prepared);
    }

    private static synchronized Result install(String name, Prepared prepared) throws IOException {
        String key = stem(name) + "-" + prepared.sourceHash();
        ResourceLocation modelId = new ResourceLocation("rigworks", "models/imported/ysm/" + key + "/model.rwym");
        String profileId = "rigworks:imported/ysm/" + key + "/profile.json";
        JsonObject provenance = new JsonObject();
        provenance.addProperty("sourceFormat", "ysm");
        provenance.addProperty("sourceHash", prepared.sourceHash());
        provenance.addProperty("sourceVersion", prepared.sourceFormat());
        provenance.addProperty("assetHash", prepared.assetHash());
        provenance.addProperty("converterRevision", RwymAssetCache.CONVERTER_REVISION);
        provenance.addProperty("renderRevision", RwymAssetModels.RENDER_REVISION);
        Path manifest = prepared.file().resolveSibling(prepared.sourceHash() + ".manifest.json");
        CharacterVisualOverrides.YsmImportSnapshot previous = null;
        AssimpScene previousScene = null;
        byte[] previousManifest = null;
        boolean hadManifest = false;
        boolean modelRegistered = false;
        String previousProfile = null;
        boolean previousEnabled = false;
        try {
            previous = CharacterVisualOverrides.snapshotYsmImport(profileId, modelId.toString());
            previousScene = AssimpModelLoader.getCachedModel(AssimpModelLoader.canonicalIdentity(modelId), ModelImportMode.DYNAMIC);
            hadManifest = Files.isRegularFile(manifest);
            if (hadManifest) previousManifest = Files.readAllBytes(manifest);
            previousProfile = RigWorksConfig.CLIENT.profile.get();
            previousEnabled = RigWorksConfig.CLIENT.enabled.get();
            if (previousScene != null && !YsmRuntimeBridge.ownsScene(previousScene))
                throw new IOException("Character model ID is already used by a non-YSM asset: " + modelId);
            AssimpScene installScene = previousScene == null ? prepared.scene() : previousScene;
            CharacterVisualOverrides.asset(modelId.toString(), prepared.file());
            if (previousScene == null) {
                AssimpModelLoader.registerModel(modelId, installScene);
                modelRegistered = true;
            }
            // The render cache already faces +Z after AssimpLoader's coordinate conversion.
            CharacterVisualConfig config = RigWorksWorkbenchExtension.discover(installScene, modelId.toString(),
                    prepared.assetHash(), CharacterProfile.ModelOrientation.FORWARD_PLUS_Z);
            RigWorksWorkbenchExtension.applyCharacter(profileId, config, null);
            RwymAssetCache.writeAtomic(manifest, provenance.toString().getBytes(StandardCharsets.UTF_8));
            RigWorksDiagnostics.debug("YSM installed profile={} model={} yaw={} rightHand={} leftHand={} sockets={}",
                    profileId, modelId, config.profile().modelYawDegrees(), config.equipment().handMount(false),
                    config.equipment().handMount(true), config.equipment().sockets().keySet());
            synchronized (YsmAvatarInstaller.class) { if (pending == prepared) pending = null; }
            return new Result(new Installed(config, profileId, modelId.toString(), prepared.assetHash()), prepared.model());
        } catch (IOException | RuntimeException failure) {
            boolean profileRestored = false;
            if (previous != null) try {
                CharacterVisualOverrides.restoreYsmImport(previous);
                profileRestored = true;
            }
            catch (IOException | RuntimeException rollback) { failure.addSuppressed(rollback); }
            if (modelRegistered) try {
                if (previousScene == null) AssimpModelLoader.removeCachedModel(AssimpModelLoader.canonicalIdentity(modelId));
                else AssimpModelLoader.registerModel(modelId, previousScene);
            } catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
            final String restoreProfile = previousProfile;
            final boolean restoreEnabled = previousEnabled;
            if (restoreProfile != null) try {
                if (!RigWorksConfig.tryUpdate(edit -> edit.set(RigWorksConfig.CLIENT.profile, restoreProfile)
                        .set(RigWorksConfig.CLIENT.enabled, restoreEnabled)))
                    throw new IOException(RigWorksConfig.lastUpdateError());
                LocalPlayerCharacter.refreshConfiguration();
                LocalPlayerCharacter.reloadVisual();
            } catch (IOException | RuntimeException rollback) { failure.addSuppressed(rollback); }
            try {
                if (hadManifest) RwymAssetCache.writeAtomic(manifest, previousManifest);
                else Files.deleteIfExists(manifest);
                if (prepared.created() && (profileRestored || (previous == null && !modelRegistered)))
                    RwymAssetModels.discardNew(prepared.file(), prepared.assetHash());
            } catch (IOException rollback) { failure.addSuppressed(rollback); }
            synchronized (YsmAvatarInstaller.class) { if (pending == prepared) pending = null; }
            org.apache.logging.log4j.LogManager.getLogger("rigworks.ysm").warn(
                    "YSM apply failed name={} source={} asset={} profile={}; restoring previous selection",
                    name, prepared.sourceHash(), prepared.file(), profileId, failure);
            throw failure;
        }
    }

    private static String stem(String name) {
        String filename = name == null ? "ysavatar" : name;
        int dot = filename.lastIndexOf('.');
        String raw = (dot < 0 ? filename : filename.substring(0, dot)).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (raw.isBlank()) raw = "ysavatar";
        return raw.length() > 40 ? raw.substring(0, 40) : raw;
    }
}
