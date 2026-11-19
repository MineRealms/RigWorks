package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.AssimpLoader;
import cn.minerealms.assimploader.client.ui.NativeFileDialogs;
import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.rigworks.character.core.CharacterVisualConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** YSM workbench import entry: file bytes -> contract API -> existing discover and Save/Apply flow. */
public final class YsmAvatarImporter {
    private static final int MAX_BYTES = 256 * 1024 * 1024;

    private YsmAvatarImporter() {}

    public static CompletableFuture<Void> importFromDisk() {
        var result = new CompletableFuture<Void>();
        CompletableFuture.runAsync(() -> {
            try {
                var chosen = NativeFileDialogs.openFile("Import YSM avatar", null,
                        List.of("*.json", "*.mxc", "*.ysm"), "YSM / Yes Steve Model files");
                if (chosen.isEmpty()) {
                    result.complete(null);
                    return;
                }
                Path file = chosen.get();
                byte[] bytes = Files.readAllBytes(file);
                Minecraft.getInstance().execute(() -> {
                    try {
                        install(file, bytes);
                        result.complete(null);
                    } catch (Throwable error) {
                        result.completeExceptionally(error);
                    }
                });
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    /** Memory import used by both the file picker and any caller already holding YSM bytes. */
    public static void install(Path source, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IOException("YSM input is empty");
        if (bytes.length > MAX_BYTES) throw new IOException("YSM input exceeds 256 MiB");
        String name = source == null ? "avatar.ysm" : source.getFileName().toString();
        String stem = stem(name);
        String hash = CharacterVisualConfig.hash(bytes);
        String key = stem + "-" + hash.substring(0, 8);
        ResourceLocation modelId = new ResourceLocation("rigworks", "models/workbench/" + key + "/model.glb");
        AssimpScene scene = YsmImportAdapter.load(name, bytes);
        if (scene == null) throw new IOException("YSM import failed: " + name);
        persist(modelId, bytes, hash);
        AssimpModelLoader.registerModel(modelId, scene);
        var config = RigWorksWorkbenchExtension.discover(scene, modelId.toString(), hash);
        RigWorksWorkbenchExtension.applyCharacter("rigworks:workbench/" + key + "/profile.json", config, null);
    }

    private static void persist(ResourceLocation modelId, byte[] bytes, String hash) throws IOException {
        byte[] glb;
        try {
            glb = YsmImportAdapter.toGlb(bytes);
        } catch (UnsupportedOperationException unavailable) {
            AssimpLoader.LOGGER.warn("YSM asset not persisted; AssimpLoader YSM API is not merged yet: {}", unavailable.getMessage());
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
