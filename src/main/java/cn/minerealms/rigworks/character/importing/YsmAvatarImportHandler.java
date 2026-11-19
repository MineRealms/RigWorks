package cn.minerealms.rigworks.character.importing;

import cn.minerealms.assimploader.AssimpLoader;
import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.rigworks.character.client.CharacterVisualOverrides;
import cn.minerealms.rigworks.character.client.RigWorksWorkbenchExtension;
import cn.minerealms.rigworks.character.core.CharacterVisualConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** YSM/YSGP avatar format: decode through the YSM API, then run RigWorks' shared apply flow. */
public final class YsmAvatarImportHandler implements AvatarImportHandler {
    private static final int MAX_BYTES = 256 * 1024 * 1024;

    @Override
    public String id() {
        return "ysm";
    }

    @Override
    public List<String> extensions() {
        return List.of("json", "mxc", "ysm");
    }

    @Override
    public AssimpScene load(String name, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IOException("YSM input is empty");
        if (bytes.length > MAX_BYTES) throw new IOException("YSM input exceeds 256 MiB");
        AssimpScene scene = YsmApi.bridge().load(name, bytes);
        if (scene == null) throw new IOException("YSM import failed: " + name);
        return scene;
    }

    @Override
    public void apply(String name, byte[] bytes, AssimpScene scene) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IOException("YSM input is empty");
        if (bytes.length > MAX_BYTES) throw new IOException("YSM input exceeds 256 MiB");
        String stem = stem(name);
        String hash = CharacterVisualConfig.hash(bytes);
        String key = stem + "-" + hash.substring(0, 8);
        ResourceLocation modelId = new ResourceLocation("rigworks", "models/workbench/" + key + "/model.glb");
        persist(modelId, bytes, hash);
        AssimpModelLoader.registerModel(modelId, scene);
        var config = RigWorksWorkbenchExtension.discover(scene, modelId.toString(), hash);
        RigWorksWorkbenchExtension.applyCharacter("rigworks:workbench/" + key + "/profile.json", config, null);
    }

    private static void persist(ResourceLocation modelId, byte[] bytes, String hash) throws IOException {
        byte[] glb;
        try {
            glb = YsmApi.bridge().toGlb(bytes);
        } catch (UnsupportedOperationException unavailable) {
            AssimpLoader.LOGGER.warn("YSM asset not persisted; AssimpLoader YSM API is not merged yet: {}",
                    unavailable.getMessage());
            return;
        }
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
