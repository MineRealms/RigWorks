package cn.minerealms.rigworks.ysm.runtime;

import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.rigworks.character.client.CharacterVisualOverrides;
import cn.minerealms.rigworks.character.client.RigWorksWorkbenchExtension;
import cn.minerealms.rigworks.character.core.CharacterProfile;
import cn.minerealms.rigworks.character.core.CharacterVisualConfig;
import cn.minerealms.rigworks.debug.RigWorksDiagnostics;
import cn.minerealms.rigworks.ysm.asset.RwymAssetCache;
import cn.minerealms.rigworks.ysm.asset.RwymAssetModels;
import cn.minerealms.rigworks.ysm.asset.RwymNative;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
                            AssimpScene scene, YsmModelSource.YsmModelView model) {}

    private static synchronized Prepared prepare(byte[] source) throws IOException {
        long started = System.nanoTime();
        if (source == null || source.length == 0 || source.length > RwymNative.MAX_SOURCE_BYTES)
            throw new IOException("YSM input must be between 1 byte and 256 MiB");
        String hash = RwymAssetCache.hash(source);
        if (pending != null && pending.sourceHash().equals(hash)) return pending;
        Path directory = FMLPaths.CONFIGDIR.get().resolve("rigworks").resolve("ysmodels");
        RwymAssetCache.Imported asset = new RwymAssetCache(directory).importYsm(source);
        var model = asset.asset().mainModel();
        AssimpScene scene = RwymAssetModels.loadRenderCache(asset.file(), asset.assetHash(), model);
        var view = YsmModelSource.reflective().view(model);
        YsmRuntimeBridge.registerScene(scene, view);
        RigWorksDiagnostics.debug("YSM prepared source={} cached={} format={} bones={} meshes={} clips={} controllers={} textures={} elapsedMs={}",
                hash, asset.reused(), asset.asset().sourceFormat(), view.bones().size(), scene.getMeshes().size(),
                view.animations().size(), view.controllers().size(), asset.asset().textures().size(),
                (System.nanoTime() - started) / 1_000_000);
        pending = new Prepared(asset.file(), asset.sourceHash(), asset.assetHash(), asset.asset().sourceFormat(), scene, view);
        return pending;
    }

    public static AssimpScene loadScene(String name, byte[] source) throws IOException { return prepare(source).scene(); }

    public static Result installFromYsmBytes(String name, byte[] source) throws IOException {
        return install(name, prepare(source));
    }

    public static Result installPrepared(String name, AssimpScene scene) throws IOException {
        Prepared prepared;
        synchronized (YsmAvatarInstaller.class) { prepared = pending; }
        if (prepared == null || prepared.scene() != scene)
            throw new IOException("The prepared YSM import was superseded; import it again");
        return install(name, prepared);
    }

    private static Result install(String name, Prepared prepared) throws IOException {
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
        RwymAssetCache.writeAtomic(prepared.file().resolveSibling(prepared.sourceHash() + ".manifest.json"),
                provenance.toString().getBytes(StandardCharsets.UTF_8));
        CharacterVisualOverrides.asset(modelId.toString(), prepared.file());
        AssimpModelLoader.registerModel(modelId, prepared.scene());
        // The render cache keeps the source YSM/Blockbench axes (face at -Z); AssimpLoader imports
        // .glb as OpenGL right-handed and converts that to engine space, so the imported scene
        // already faces +Z and must not inherit the workbench's 180 degree default yaw.
        CharacterVisualConfig config = RigWorksWorkbenchExtension.discover(prepared.scene(), modelId.toString(), prepared.assetHash(),
                CharacterProfile.ModelOrientation.FORWARD_PLUS_Z);
        RigWorksWorkbenchExtension.applyCharacter(profileId, config, null);
        RigWorksDiagnostics.debug("YSM installed profile={} model={} yaw={} rightHand={} leftHand={} sockets={}",
                profileId, modelId, config.profile().modelYawDegrees(), config.equipment().handMount(false),
                config.equipment().handMount(true), config.equipment().sockets().keySet());
        synchronized (YsmAvatarInstaller.class) { if (pending == prepared) pending = null; }
        return new Result(new Installed(config, profileId, modelId.toString(), prepared.assetHash()), prepared.model());
    }

    private static String stem(String name) {
        String filename = name == null ? "ysavatar" : name;
        int dot = filename.lastIndexOf('.');
        String raw = (dot < 0 ? filename : filename.substring(0, dot)).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (raw.isBlank()) raw = "ysavatar";
        return raw.length() > 40 ? raw.substring(0, 40) : raw;
    }
}
