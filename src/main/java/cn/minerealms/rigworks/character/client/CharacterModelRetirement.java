package cn.minerealms.rigworks.character.client;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/** Defers removing pinned cache entries until their last live owner has released them. */
final class CharacterModelRetirement<T> {
    private final Map<String, T> pending = new HashMap<>();

    synchronized void request(String identity, T scene) {
        if (scene != null) pending.put(Objects.requireNonNull(identity), scene);
    }

    synchronized void cancel(String identity) { pending.remove(identity); }
    synchronized void completed(Collection<String> identities) { identities.forEach(pending::remove); }
    synchronized boolean isEmpty() { return pending.isEmpty(); }

    synchronized Map<String, T> ready(Function<String, T> cached, Predicate<T> inUse) {
        // A replacement import owns the identity now; an older deletion must not retire it.
        pending.entrySet().removeIf(entry -> cached.apply(entry.getKey()) != entry.getValue());
        // Removing any entry invalidates the engine's resource generation. Wait until
        // every deleted model is unused so another deferred owner cannot lose its source.
        if (pending.values().stream().anyMatch(inUse)) return Map.of();
        return Map.copyOf(pending);
    }
}
