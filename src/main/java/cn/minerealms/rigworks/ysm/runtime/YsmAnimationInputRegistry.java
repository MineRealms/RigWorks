/*
 * Originally derived from YSM's client-side optional compatibility pattern (Apache-2.0).
 * Modified by MineRealms for RigWorks.
 */
package cn.minerealms.rigworks.ysm.runtime;

import net.minecraft.client.player.AbstractClientPlayer;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** Registration point for optional client-only animation input adapters. */
public final class YsmAnimationInputRegistry {
    private static final CopyOnWriteArrayList<YsmAnimationInputProvider> PROVIDERS = new CopyOnWriteArrayList<>();
    private static final Set<String> FAILED_PROVIDERS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static {
        PROVIDERS.add(new TaczYsmAnimationInputProvider());
    }

    private YsmAnimationInputRegistry() {}

    public static void register(YsmAnimationInputProvider provider) {
        if (provider == null) throw new IllegalArgumentException("Animation input provider is required");
        PROVIDERS.removeIf(existing -> existing.id().equals(provider.id()));
        FAILED_PROVIDERS.remove(provider.id());
        PROVIDERS.add(provider);
    }

    public static List<YsmAnimationInputProvider> providers() { return List.copyOf(PROVIDERS); }

    public static YsmAnimationInputProvider.Snapshot sample(AbstractClientPlayer player) {
        if (player == null) return YsmAnimationInputProvider.Snapshot.empty();
        Map<String, Double> queries = null;
        Set<String> functions = null;
        Map<String, String> values = null;
        for (YsmAnimationInputProvider provider : PROVIDERS) {
            try {
                YsmAnimationInputProvider.Snapshot snapshot = provider.sampleOwner(player);
                if (snapshot == null) continue;
                if (snapshot.queries().isEmpty() && snapshot.functions().isEmpty() && snapshot.values().isEmpty()) continue;
                if (queries == null) {
                    queries = new LinkedHashMap<>();
                    functions = new LinkedHashSet<>();
                    values = new LinkedHashMap<>();
                }
                queries.putAll(snapshot.queries());
                functions.addAll(snapshot.functions());
                values.putAll(snapshot.values());
            } catch (RuntimeException error) {
                if (FAILED_PROVIDERS.add(provider.id()))
                    org.apache.logging.log4j.LogManager.getLogger("rigworks.ysm").warn(
                            "YSM animation input provider {} failed; continuing with other inputs (further failures suppressed)",
                            provider.id(), error);
            }
        }
        return queries == null ? YsmAnimationInputProvider.Snapshot.empty()
                : new YsmAnimationInputProvider.Snapshot(queries, functions, values);
    }
}
