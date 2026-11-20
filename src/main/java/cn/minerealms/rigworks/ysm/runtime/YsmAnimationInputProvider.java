/*
 * Originally derived from the YSM optional client compatibility model (Apache-2.0).
 * Modified by MineRealms for RigWorks.
 */
package cn.minerealms.rigworks.ysm.runtime;

import net.minecraft.client.player.LocalPlayer;

import java.util.Map;
import java.util.Set;

/** Supplies immutable, client-only values that authored YSM expressions can query. */
public interface YsmAnimationInputProvider {
    String id();
    Snapshot sample(LocalPlayer player);

    record Snapshot(Map<String, Double> queries, Set<String> functions, Map<String, String> values) {
        public Snapshot {
            queries = Map.copyOf(queries);
            functions = Set.copyOf(functions);
            values = Map.copyOf(values);
        }

        public static Snapshot empty() { return new Snapshot(Map.of(), Set.of(), Map.of()); }
    }
}
