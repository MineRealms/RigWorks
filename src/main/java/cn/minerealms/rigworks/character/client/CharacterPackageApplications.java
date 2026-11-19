package cn.minerealms.rigworks.character.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A verified download replaces the last usable package only after its model finishes applying. */
final class CharacterPackageApplications {
    private record Pending(String hash,Object previousBinding){}
    private final Map<UUID,String> applied=new HashMap<>();
    private final Map<UUID,Pending> applying=new HashMap<>();

    String applied(UUID owner){return applied.get(owner);}
    String applying(UUID owner){var pending=applying.get(owner);return pending==null?null:pending.hash;}
    boolean begin(UUID owner,String hash,Object previousBinding) {
        if(hash.equals(applied.get(owner))||hash.equals(applying(owner)))return false;
        applying.put(owner,new Pending(hash,previousBinding));return true;
    }
    boolean confirmable(UUID owner,String hash,Object currentBinding) {
        var pending=applying.get(owner);
        return pending!=null&&pending.hash.equals(hash)&&currentBinding!=null&&currentBinding!=pending.previousBinding;
    }
    boolean confirm(UUID owner,String hash,Object currentBinding) {
        if(!confirmable(owner,hash,currentBinding))return false;
        applying.remove(owner);applied.put(owner,hash);return true;
    }
    String reject(UUID owner){var pending=applying.remove(owner);return pending==null?null:pending.hash;}
    void forget(UUID owner){applying.remove(owner);applied.remove(owner);}
    Set<UUID> owners(){var result=new HashSet<>(applied.keySet());result.addAll(applying.keySet());return Set.copyOf(result);}
    Set<String> retained(){var result=new HashSet<>(applied.values());applying.values().forEach(pending->result.add(pending.hash));return Set.copyOf(result);}
    void clear(){applied.clear();applying.clear();}
}
