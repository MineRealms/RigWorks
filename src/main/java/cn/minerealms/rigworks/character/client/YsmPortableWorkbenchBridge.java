package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.model.AssimpNode;
import cn.minerealms.assimploader.workbench.client.WorkbenchExtensions;
import cn.minerealms.assimploader.workbench.model.WorkbenchSession;
import cn.minerealms.rigworks.ysm.asset.RwymPortableAssets;
import cn.minerealms.rigworks.ysm.runtime.YsmRuntimeBridge;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Workbench callbacks keep all YSM knowledge inside RigWorks; the engine only opens its GLB. */
public final class YsmPortableWorkbenchBridge {
    private static final ExecutorService IO = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), task -> {
                Thread thread = new Thread(task, "rigworks-rwym-package");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final Map<WorkbenchSession, CompletableFuture<Void>> PENDING = new WeakHashMap<>();
    private static final Map<WorkbenchSession, PreparedState> PREPARED = new WeakHashMap<>();

    private record PreparedState(Path directory, String modelId, String hash) {}

    private YsmPortableWorkbenchBridge() {}

    /** Call from imported/opened and prepareAsync; completion returns to the client thread. */
    public static CompletableFuture<Void> prepare(WorkbenchExtensions.Context context) {
        JsonObject extension = context.session().document().extensions().getAsJsonObject("rigworks");
        if (extension == null) return CompletableFuture.completedFuture(null);
        var visual = RigWorksWorkbenchExtension.visual(context);
        String modelId = visual.profile().model();
        if (!modelId.endsWith(".rwym") && !extension.has("ysm") && !YsmRuntimeBridge.ownsScene(context.scene()))
            return CompletableFuture.completedFuture(null);
        var pending = PENDING.get(context.session());
        if (pending != null && !pending.isDone()) return pending;
        if (extension.has("ysm")) {
            try { RwymPortableAssets.validateMetadata(extension.getAsJsonObject("ysm"), visual.modelHash()); }
            catch (IOException | RuntimeException error) { return CompletableFuture.failedFuture(error); }
        }
        String assetId = context.session().document().assetId();
        String profile = RigWorksWorkbenchExtension.profileId(context);
        String hash = visual.modelHash();
        Path source = CharacterVisualOverrides.asset(modelId);
        Path project = context.projectPath() == null ? null : context.projectPath().toAbsolutePath().normalize().getParent();
        var result = new CompletableFuture<Void>();
        PENDING.put(context.session(), result);
        PREPARED.remove(context.session());
        try {
            CompletableFuture.supplyAsync(() -> {
                try {
                    var prepared = RwymPortableAssets.prepare(project, source, hash);
                    Set<String> nodes = new HashSet<>();
                    collect(context.scene().getRootNode(), nodes);
                    for (var bone : prepared.model().bones()) if (!nodes.contains(bone.name()))
                        throw new IOException("The engine model is missing RWYM bone: " + bone.name());
                    YsmRuntimeBridge.registerScene(context.scene(), prepared.model());
                    return prepared;
                } catch (IOException | RuntimeException error) { throw new CompletionException(error); }
            }, IO).whenComplete((prepared, error) -> Minecraft.getInstance().execute(() -> {
                try {
                    if (error != null) { result.completeExceptionally(error); return; }
                    var current = context.session().document().extensions().getAsJsonObject("rigworks");
                    if (!assetId.equals(context.session().document().assetId()) || current == null
                            || !profile.equals(RigWorksWorkbenchExtension.profileId(context))
                            || !Objects.equals(hash, RigWorksWorkbenchExtension.visual(context).modelHash()))
                        throw new IllegalStateException("The character changed while its RWYM asset was loading");
                    CharacterVisualOverrides.asset(modelId, prepared.assetFile());
                    JsonObject updated = current.deepCopy();
                    updated.add("ysm", prepared.metadata());
                    context.session().edit(document -> document.withExtension("rigworks", updated));
                    PREPARED.put(context.session(), new PreparedState(project, modelId, hash));
                    result.complete(null);
                } catch (IOException | RuntimeException failure) { result.completeExceptionally(failure); }
            }));
        } catch (RuntimeException rejected) { result.completeExceptionally(rejected); }
        return result;
    }

    /** No asset byte arrays are returned: WorkbenchPackageIO streams the staged extension file. */
    public static void requirePrepared(WorkbenchExtensions.Context context) {
        var extension = context.session().document().extensions().getAsJsonObject("rigworks");
        if (extension == null) return;
        var visual = RigWorksWorkbenchExtension.visual(context);
        if (!visual.profile().model().endsWith(".rwym") && !extension.has("ysm")
                && !YsmRuntimeBridge.ownsScene(context.scene())) return;
        var pending = PENDING.get(context.session());
        if (pending == null || !pending.isDone() || pending.isCompletedExceptionally())
            throw new IllegalStateException("Prepare the character's RWYM asset before exporting");
        if (context.projectPath() == null)
            throw new IllegalStateException("Save the workbench project before exporting its RWYM asset");
        var state = PREPARED.get(context.session());
        Path project = context.projectPath().toAbsolutePath().normalize().getParent();
        if (state == null || !project.equals(state.directory())
                || !visual.profile().model().equals(state.modelId()) || !visual.modelHash().equals(state.hash()))
            throw new IllegalStateException("Prepare this project's RWYM asset again before exporting");
        try { RwymPortableAssets.validateMetadata(extension.getAsJsonObject("ysm"), visual.modelHash()); }
        catch (IOException | RuntimeException error) { throw new IllegalStateException("Invalid portable RWYM metadata", error); }
    }

    private static void collect(AssimpNode node, Set<String> names) {
        if (node == null) return;
        names.add(node.getName());
        for (AssimpNode child : node.getChildren()) collect(child, names);
    }
}
