package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.AssimpLoader;
import cn.minerealms.rigworks.character.core.CharacterVisualConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Local author preferences. Received network packages are stored only by the binary package cache. */
public final class CharacterVisualOverrides {
    private static final Path ROOT=FMLPaths.CONFIGDIR.get().resolve("rigworks").toAbsolutePath().normalize();
    private static final Map<String,CharacterVisualConfig> CONFIGS=new HashMap<>();
    private static final Set<String> READ=new HashSet<>();
    private static final Map<String,Path> ASSETS=new HashMap<>();
    private static final Map<String,String> IMPORTED_PROFILES=new HashMap<>();
    private static boolean assetsRead;
    private static boolean importedProfilesRead;
    public record ImportedProfile(String id,String name) {}
    private CharacterVisualOverrides(){}
    static CharacterVisualConfig load(String profile) {
        new ResourceLocation(profile);
        if(!READ.add(profile))return CONFIGS.get(profile);
        Path file=profileFile(profile);
        try {
            if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)) {
                confined(file);
                CONFIGS.put(profile,CharacterVisualConfig.parse(read(file)));
            }
        }catch(IOException|RuntimeException error){AssimpLoader.LOGGER.warn("Invalid local character configuration {}",file,error);}
        return CONFIGS.get(profile);
    }
    static CharacterVisualConfig readFresh(String profile) {
        READ.remove(profile);CONFIGS.remove(profile);return load(profile);
    }
    static CharacterVisualConfig readNewer(String profile,Path project)throws IOException {
        Path file=profileFile(profile);
        if(project==null||!Files.exists(file,LinkOption.NOFOLLOW_LINKS)
                ||Files.getLastModifiedTime(file).compareTo(Files.getLastModifiedTime(project))<=0)return null;
        return readFresh(profile);
    }
    static synchronized void invalidate() {
        READ.clear();CONFIGS.clear();ASSETS.clear();assetsRead=false;IMPORTED_PROFILES.clear();importedProfilesRead=false;
    }
    static void save(String profile,CharacterVisualConfig config)throws IOException {
        new ResourceLocation(profile);write(profileFile(profile),config.json());
        CONFIGS.put(profile,config);READ.add(profile);
    }
    static synchronized Path asset(String id) {readAssets();return ASSETS.get(id);}
    public static synchronized void asset(String id,Path source)throws IOException {
        new ResourceLocation(id);readAssets();
        if(ASSETS.size()>=128&&!ASSETS.containsKey(id))throw new IOException("At most 128 local character assets");
        Path path=source.toAbsolutePath().normalize();
        if(!Files.isRegularFile(path))throw new IOException("Character asset does not exist: "+path);
        var next=new HashMap<>(ASSETS);next.put(id,path);
        var json=new JsonObject();next.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->json.addProperty(e.getKey(),e.getValue().toString()));
        write(ROOT.resolve("assets.json"),json.toString());ASSETS.clear();ASSETS.putAll(next);
    }
    public static synchronized java.util.List<ImportedProfile> importedProfiles() {
        readImportedProfiles();
        return IMPORTED_PROFILES.entrySet().stream().map(entry->new ImportedProfile(entry.getKey(),entry.getValue()))
                .sorted(java.util.Comparator.comparing(ImportedProfile::id)).toList();
    }
    public static synchronized void rememberImportedYsmProfile(String id)throws IOException {
        validateImportedYsmProfile(id);readImportedProfiles();
        if(IMPORTED_PROFILES.size()>=128&&!IMPORTED_PROFILES.containsKey(id))throw new IOException("At most 128 imported YSM profiles");
        String path=new ResourceLocation(id).getPath();
        String folder=path.substring("imported/ysm/".length(),path.length()-"/profile.json".length());
        String name=folder.replaceFirst("-[a-f0-9]{64}$","").replace('_',' ').trim();
        if(name.isBlank())name="Imported YSM";
        var next=new java.util.TreeMap<>(IMPORTED_PROFILES);next.put(id,name);
        JsonObject root=new JsonObject();root.addProperty("version",1);JsonArray profiles=new JsonArray();
        next.forEach((profileId,displayName)->{JsonObject item=new JsonObject();item.addProperty("id",profileId);item.addProperty("name",displayName);profiles.add(item);});
        root.add("profiles",profiles);write(ROOT.resolve("imported-profiles.json"),root.toString());
        IMPORTED_PROFILES.clear();IMPORTED_PROFILES.putAll(next);
    }
    private static void readImportedProfiles() {
        if(importedProfilesRead)return;importedProfilesRead=true;Path file=ROOT.resolve("imported-profiles.json");
        try {
            if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return;
            confined(file);JsonObject root=CharacterVisualConfig.parseBounded(read(file)).getAsJsonObject();
            if(root.size()!=2||!root.has("version")||root.get("version").getAsInt()!=1||!root.has("profiles"))
                throw new IOException("Unsupported imported profile catalog");
            JsonArray profiles=root.getAsJsonArray("profiles");if(profiles.size()>128)throw new IOException("Too many imported YSM profiles");
            var replacement=new HashMap<String,String>();
            for(var value:profiles) {
                if(!value.isJsonObject())throw new IOException("Invalid imported profile entry");
                JsonObject item=value.getAsJsonObject();if(item.size()!=2||!item.has("id")||!item.has("name"))throw new IOException("Invalid imported profile entry");
                if(!item.get("id").isJsonPrimitive()||!item.getAsJsonPrimitive("id").isString()
                        ||!item.get("name").isJsonPrimitive()||!item.getAsJsonPrimitive("name").isString())
                    throw new IOException("Imported profile fields must be strings");
                String id=item.get("id").getAsString(),name=item.get("name").getAsString();validateImportedYsmProfile(id);
                if(name.isBlank()||name.length()>80||name.indexOf('\0')>=0||replacement.putIfAbsent(id,name)!=null)
                    throw new IOException("Invalid imported profile label");
            }
            IMPORTED_PROFILES.putAll(replacement);
        }catch(IOException|RuntimeException error){AssimpLoader.LOGGER.warn("Invalid local imported character catalog {}",file,error);}
    }
    private static void validateImportedYsmProfile(String id) {
        ResourceLocation location=new ResourceLocation(id);String path=location.getPath();
        if(!location.getNamespace().equals("rigworks")||!path.startsWith("imported/ysm/")||!path.endsWith("/profile.json")||id.length()>512)
            throw new IllegalArgumentException("Not a local imported YSM profile: "+id);
    }
    private static void readAssets() {
        if(assetsRead)return;assetsRead=true;Path file=ROOT.resolve("assets.json");
        try {
            if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return;
            confined(file);var json=CharacterVisualConfig.parseBounded(read(file)).getAsJsonObject();
            if(json.size()>128)throw new IOException("Too many local character assets");
            var replacement=new HashMap<String,Path>();
            for(var e:json.entrySet()){new ResourceLocation(e.getKey());replacement.put(e.getKey(),Path.of(e.getValue().getAsString()).toAbsolutePath().normalize());}
            ASSETS.putAll(replacement);
        }catch(IOException|RuntimeException error){AssimpLoader.LOGGER.warn("Invalid local character asset registry {}",file,error);}
    }
    private static Path profileFile(String profile){return ROOT.resolve("characters").resolve(CharacterVisualConfig.hash(profile.getBytes(StandardCharsets.UTF_8))+".json");}
    private static String read(Path file)throws IOException {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>CharacterVisualConfig.MAX_BYTES)throw new IOException("Local character JSON exceeds 128 KiB");
        return Files.readString(file,StandardCharsets.UTF_8);
    }
    private static void write(Path file,String json)throws IOException {
        if(json.getBytes(StandardCharsets.UTF_8).length>CharacterVisualConfig.MAX_BYTES)throw new IOException("Local character JSON exceeds 128 KiB");
        Files.createDirectories(ROOT);confined(file);Files.createDirectories(file.getParent());confined(file);
        Path temporary=Files.createTempFile(file.getParent(),".character-",".tmp");
        try {Files.writeString(temporary,json,StandardCharsets.UTF_8);Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        finally {Files.deleteIfExists(temporary);}
    }
    private static void confined(Path file)throws IOException {
        if(!file.startsWith(ROOT))throw new IOException("Character preference escapes config directory");
        Path current=ROOT;
        if(Files.isSymbolicLink(current))throw new IOException("Character preferences contain a symbolic link");
        for(Path part:ROOT.relativize(file)){current=current.resolve(part);if(Files.isSymbolicLink(current))throw new IOException("Character preferences contain a symbolic link");}
    }
}
