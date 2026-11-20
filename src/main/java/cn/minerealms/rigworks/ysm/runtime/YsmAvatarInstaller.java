/*
 * Originally derived from:
 * Source: https://github.com/YesSteveModel/YesSteveModel (Apache-2.0)
 *        and https://github.com/stevewongv/YesSteveModel (Apache-2.0)
 *
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy of the License at:
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Modified by MineRealms for RigWorks.
 * Changes: Cleanroom reimplementation / package adaptation / API changes for in-mod YSM decode.
 */
package cn.minerealms.rigworks.ysm.runtime;

import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.core.ModelImportMode;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.rigworks.character.client.CharacterVisualOverrides;
import cn.minerealms.rigworks.character.client.RigWorksWorkbenchExtension;
import cn.minerealms.rigworks.character.core.CharacterVisualConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class YsmAvatarInstaller {
    private static final int MAX_BYTES = 256 * 1024 * 1024;
    private static YsmDecodeSource decodeSource = YsmDecodeSource.reflective();
    private static YsmModelSource modelSource = YsmModelSource.reflective();

    private YsmAvatarInstaller() {}

    public record Installed(CharacterVisualConfig config, String profileId, String modelId, String hash) {}

    public record Result(Installed installed, YsmModelSource.YsmModelView model) {
        public CharacterVisualConfig config() { return installed.config(); }
        public String profileId() { return installed.profileId(); }
        public YsmModelSource.YsmModelView model() { return model; }
    }

    public static void decodeSource(YsmDecodeSource source) { decodeSource = source == null ? YsmDecodeSource.reflective() : source; }

    public static void modelSource(YsmModelSource source) { modelSource = source == null ? YsmModelSource.reflective() : source; }

    public static CharacterVisualConfig install(Object model, AssimpScene scene, String name) throws IOException {
        return install(modelSource.toGlb(model), scene, name).config();
    }

    public static Installed install(byte[] glbBytes, AssimpScene scene, String name) throws IOException {
        if (glbBytes == null || glbBytes.length == 0) throw new IOException("Decoded GLB is empty");
        if (glbBytes.length > MAX_BYTES) throw new IOException("Decoded GLB exceeds 256 MiB");
        if (scene == null) throw new IOException("YSM scene is required for install");
        String hash = CharacterVisualConfig.hash(glbBytes);
        String key = stem(name) + "-" + hash.substring(0, 8);
        ResourceLocation modelId = new ResourceLocation("rigworks", "models/workbench/" + key + "/model.glb");
        String profileId = "rigworks:workbench/" + key + "/profile.json";
        persist(modelId, glbBytes, hash);
        AssimpModelLoader.removeCachedModel(AssimpModelLoader.canonicalIdentity(modelId));
        CharacterVisualConfig config = RigWorksWorkbenchExtension.discover(scene, modelId.toString(), hash);
        RigWorksWorkbenchExtension.applyCharacter(profileId, config, null);
        return new Installed(config, profileId, modelId.toString(), hash);
    }

    public static byte[] decodeToGlb(byte[] ysmBytes) throws IOException {
        if (ysmBytes == null || ysmBytes.length == 0) throw new IOException("YSM input is empty");
        if (ysmBytes.length > MAX_BYTES) throw new IOException("YSM input exceeds 256 MiB");
        byte[] payload = decodeSource.decode(ysmBytes);
        Object model = modelSource.decode(payload);
        if (model == null) throw new IOException("YSM binary decode produced no model");
        return modelSource.toGlb(model);
    }

    public static AssimpScene loadScene(String name, byte[] ysmBytes) throws IOException {
        byte[] glb = decodeToGlb(ysmBytes);
        AssimpScene scene = new AssimpModelLoader().loadModelFromMemory("model.glb", glb, ModelImportMode.DYNAMIC);
        if (scene == null) throw new IOException("Assimp could not load the decoded YSM GLB");
        return scene;
    }

    public static Result installFromYsmBytes(String name, byte[] ysmBytes) throws IOException {
        byte[] glb = decodeToGlb(ysmBytes);
        AssimpScene scene = new AssimpModelLoader().loadModelFromMemory("model.glb", glb, ModelImportMode.DYNAMIC);
        if (scene == null) throw new IOException("Assimp could not load the decoded YSM GLB");
        Installed installed = install(glb, scene, name);
        Object model = modelSource.decode(decodeSource.decode(ysmBytes));
        return new Result(installed, modelSource.view(model));
    }

    private static void persist(ResourceLocation modelId, byte[] glb, String hash) throws IOException {
        Path directory = FMLPaths.CONFIGDIR.get().resolve("rigworks").resolve("ysmodels");
        Files.createDirectories(directory);
        Path file = directory.resolve(hash.substring(0, 16) + ".glb");
        Files.write(file, glb);
        CharacterVisualOverrides.asset(modelId.toString(), file);
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        String raw = (dot < 0 ? name : name.substring(0, dot)).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]", "_");
        if (raw.isBlank()) raw = "ysavatar";
        return raw.length() > 40 ? raw.substring(0, 40) : raw;
    }
}
