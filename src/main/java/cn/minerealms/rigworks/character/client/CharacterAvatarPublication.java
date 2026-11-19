package cn.minerealms.rigworks.character.client;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Owner-local staging; preparing a replacement never changes the currently published behavior. */
final class CharacterAvatarPublication<T> {
    private record Entry<T>(String hash,T value){}
    private final Map<UUID,Entry<T>> active=new HashMap<>(),staged=new HashMap<>();
    private final Consumer<T> release;

    CharacterAvatarPublication(Consumer<T> release){this.release=Objects.requireNonNull(release);}
    T active(UUID owner){var entry=active.get(owner);return entry==null?null:entry.value;}
    void stage(UUID owner,String hash,T value) {
        discard(staged.put(owner,new Entry<>(Objects.requireNonNull(hash),Objects.requireNonNull(value))));
    }
    boolean activate(UUID owner,String hash) {
        var replacement=staged.get(owner);
        if(replacement==null||!replacement.hash.equals(hash))return false;
        staged.remove(owner);discard(active.put(owner,replacement));return true;
    }
    void cancel(UUID owner){discard(staged.remove(owner));}
    void remove(UUID owner){cancel(owner);discard(active.remove(owner));}
    void clear(){staged.values().forEach(this::discard);active.values().forEach(this::discard);staged.clear();active.clear();}
    private void discard(Entry<T> entry){if(entry!=null)release.accept(entry.value);}
}
