package cn.minerealms.rigworks.character.importing;

import cn.minerealms.assimploader.AssimpLoader;
import cn.minerealms.assimploader.client.ui.NativeFileDialogs;
import cn.minerealms.assimploader.model.AssimpScene;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Ordered registry of {@link AvatarImportHandler}s. This is the single entry point used by
 * RigWorks' own UI: it opens a file picker filtered by the registered handlers' extensions,
 * then dispatches the chosen file to the first handler that accepts its extension.
 */
public final class AvatarImportRegistry {
    private static final AvatarImportRegistry INSTANCE = new AvatarImportRegistry();

    private final List<AvatarImportHandler> handlers = new ArrayList<>();

    /** Creates a fresh registry preloaded with the built-in handlers. */
    public AvatarImportRegistry() {
        register(new YsmAvatarImportHandler());
    }

    public static AvatarImportRegistry get() {
        return INSTANCE;
    }

    /** Registers a handler at the end of the ordered chain. */
    public synchronized void register(AvatarImportHandler handler) {
        if (handler == null) throw new IllegalArgumentException("Import handler is null");
        if (handler.id() == null || handler.id().isBlank())
            throw new IllegalArgumentException("Import handler needs an id");
        if (handler.extensions() == null || handler.extensions().isEmpty())
            throw new IllegalArgumentException("Import handler " + handler.id() + " has no extensions");
        for (AvatarImportHandler existing : handlers)
            if (existing.id().equals(handler.id()))
                throw new IllegalArgumentException("Duplicate import handler id: " + handler.id());
        handlers.add(handler);
    }

    /** Snapshot of the registered handlers, in registration order. */
    public synchronized List<AvatarImportHandler> handlers() {
        return List.copyOf(handlers);
    }

    public boolean accepts(String name) {
        return handlerFor(name) != null;
    }

    /** Decode bytes with the handler matching the file name's extension. */
    public AssimpScene load(String name, byte[] bytes) throws IOException {
        AvatarImportHandler handler = handlerFor(name);
        if (handler == null) throw new IOException("No avatar import handler for: " + name);
        return handler.load(name, bytes);
    }

    /** Apply a decoded scene with the handler matching the file name's extension. */
    public void apply(String name, byte[] bytes, AssimpScene scene) throws IOException {
        AvatarImportHandler handler = handlerFor(name);
        if (handler == null) throw new IOException("No avatar import handler for: " + name);
        handler.apply(name, bytes, scene);
    }

    /**
     * Opens RigWorks' file picker filtered by the extensions of the given handler ids, or by
     * every registered handler when no ids are supplied. Returns a future completed on the
     * client thread after the chosen file has been loaded and applied.
     */
    public CompletableFuture<Void> importFromDisk(String... handlerIds) {
        Set<String> ids = handlerIds == null || handlerIds.length == 0 ? null : Set.of(handlerIds);
        List<String> patterns = new ArrayList<>();
        for (AvatarImportHandler handler : handlers()) {
            if (ids != null && !ids.contains(handler.id())) continue;
            for (String extension : handler.extensions()) {
                String pattern = "*." + extension.toLowerCase(Locale.ROOT);
                if (!patterns.contains(pattern)) patterns.add(pattern);
            }
        }
        var result = new CompletableFuture<Void>();
        CompletableFuture.runAsync(() -> {
            try {
                var chosen = NativeFileDialogs.openFile("Import avatar", null, patterns, "Avatar model files");
                if (chosen.isEmpty()) {
                    result.complete(null);
                    return;
                }
                Path file = chosen.get();
                byte[] bytes = Files.readAllBytes(file);
                Minecraft.getInstance().execute(() -> {
                    try {
                        String name = file.getFileName().toString();
                        AssimpScene scene = load(name, bytes);
                        if (scene == null) throw new IOException("Import produced no scene: " + name);
                        apply(name, bytes, scene);
                        result.complete(null);
                    } catch (Throwable error) {
                        result.completeExceptionally(error);
                    }
                });
            } catch (Throwable error) {
                AssimpLoader.LOGGER.warn("Avatar import failed", error);
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    private synchronized AvatarImportHandler handlerFor(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (AvatarImportHandler handler : handlers)
            for (String candidate : handler.extensions())
                if (extension.equals(candidate.toLowerCase(Locale.ROOT))) return handler;
        return null;
    }
}
