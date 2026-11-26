package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.AssimpLoader;
import cn.minerealms.assimploader.client.ui.LookDevNavigation;
import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.core.ModelImportMode;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.assimploader.workbench.client.ModelWorkbenchScreen;
import cn.minerealms.rigworks.character.core.CharacterVisualConfig;
import cn.minerealms.rigworks.debug.RigWorksDiagnostics;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Local author preferences. Received network packages are stored only by the binary package cache. */
public final class CharacterVisualOverrides {
    private static final Path ROOT=FMLPaths.CONFIGDIR.get().resolve("rigworks").toAbsolutePath().normalize();
    private static final CharacterProfileIndex PROFILES=new CharacterProfileIndex(ROOT);
    private static final Map<String,CharacterVisualConfig> CONFIGS=new HashMap<>();
    private static final Set<String> READ=new HashSet<>();
    private static final Map<String,Path> ASSETS=new HashMap<>();
    private static final Map<String,String> IMPORTED_PROFILES=new HashMap<>();
    private static final CharacterModelRetirement<AssimpScene> RETIRED_MODELS=new CharacterModelRetirement<>();
    private static boolean assetsRead;
    private static boolean importedProfilesRead;
    private static int retirementDelay;
    private static boolean retirementCheckFailed;
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
        RETIRED_MODELS.cancel(cacheKey(config.profile().model()));
        PROFILES.remember(profile);
    }

    static boolean hasSaved(String profile) {
        try {
            new ResourceLocation(profile);
            Path file=profileFile(profile);confined(file);
            return Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS);
        }catch(IOException|RuntimeException error){return false;}
    }

    static synchronized void remove(String profile)throws IOException {
        try {
            readAssets();readImportedProfiles();
            var removed=PROFILES.remove(profile,IMPORTED_PROFILES.keySet());
            var assets=new HashMap<>(ASSETS);
            assets.keySet().removeIf(id->!removed.keepAsset(id));
            if(!assets.equals(ASSETS)) {
                var retired=new HashSet<>(ASSETS.keySet());retired.removeAll(assets.keySet());
                writeAssets(assets);
                for(String id:retired) if(id.startsWith("rigworks:models/imported/ysm/")||id.startsWith("rigworks:models/imported/mmd/")) {
                    String key=cacheKey(id);
                    RETIRED_MODELS.request(key,AssimpModelLoader.getCachedModel(key,ModelImportMode.DYNAMIC));
                }
                retirementDelay=0;retirementCheckFailed=false;
            }
            var imported=new HashMap<>(IMPORTED_PROFILES);
            imported.keySet().retainAll(removed.profiles());
            if(!imported.equals(IMPORTED_PROFILES))writeImportedProfiles(imported);
        }
        finally {READ.remove(profile);CONFIGS.remove(profile);}
    }

    /** Disk assets stay reusable; unreferenced converted character scenes are retired. */
    static synchronized void releaseUnusedModels() {
        if(RETIRED_MODELS.isEmpty())return;
        if(retirementDelay>0){retirementDelay--;return;}
        retirementDelay=19;
        long generation=AssimpModelLoader.getCacheGeneration();
        var ready=RETIRED_MODELS.ready(key->AssimpModelLoader.getCachedModel(key,ModelImportMode.DYNAMIC),
                CharacterVisualOverrides::sceneInUse);
        if(ready.isEmpty())return;
        try {
            // Also protect valid legacy copies that have not rebuilt their profile index.
            for(String model:PROFILES.modelReferences())RETIRED_MODELS.cancel(cacheKey(model));
            readAssets();
            for(String model:ASSETS.keySet())RETIRED_MODELS.cancel(cacheKey(model));
            ready=RETIRED_MODELS.ready(key->AssimpModelLoader.getCachedModel(key,ModelImportMode.DYNAMIC),
                    CharacterVisualOverrides::sceneInUse);
            retirementCheckFailed=false;
            if(!ready.isEmpty()&&AssimpModelLoader.replaceCachedModels(generation,Map.of(),ready.keySet())) {
                RETIRED_MODELS.completed(ready.keySet());
                RigWorksDiagnostics.debug("Retired {} unused converted avatar cache entries; disk assets retained",ready.size());
            }
        } catch(IOException error) {
            if(!retirementCheckFailed)AssimpLoader.LOGGER.warn("Cannot check saved character references; retaining deleted avatar model caches",error);
            retirementCheckFailed=true;
        }
    }

    private static boolean sceneInUse(AssimpScene scene) {
        var animation=LocalPlayerCharacter.currentAnimation();
        if(animation!=null&&!animation.isClosed()&&animation.getScene()==scene)return true;
        if(RemotePlayerCharacters.usesScene(scene))return true;
        var context=LookDevNavigation.modelContext();
        if(context!=null&&context.scene()==scene)return true;
        return Minecraft.getInstance().screen instanceof ModelWorkbenchScreen editor&&editor.scene()==scene;
    }

    private static String cacheKey(String model) {
        return AssimpModelLoader.canonicalIdentity(new ResourceLocation(model))+"#dynamic";
    }

    /** Returns locally saved profiles in addition to resource-pack profiles. */
    static synchronized List<String> savedProfiles() {
        var hints=new HashSet<String>();
        CharacterAvatarBehavior.repository().entries().stream().filter(entry->entry.valid())
                .map(entry->entry.bundle().profile()).forEach(hints::add);
        try {
            hints.add(cn.minerealms.rigworks.RigWorksConfig.CLIENT.profile.get());
        } catch (RuntimeException error) {
            AssimpLoader.LOGGER.warn("Could not read the active character profile",error);
        }
        return PROFILES.profiles(hints);
    }
    static synchronized Path asset(String id) {readAssets();return ASSETS.get(id);}
    public record YsmImportSnapshot(String profileId, String modelId, String profileJson, Path assetPath) {}

    public static synchronized YsmImportSnapshot snapshotYsmImport(String profileId, String modelId) throws IOException {
        validateImportedYsmProfile(profileId);
        return snapshotImportedAvatar(profileId, modelId);
    }

    public static synchronized YsmImportSnapshot snapshotImportedAvatar(String profileId, String modelId) throws IOException {
        validateImportedProfile(profileId);
        new ResourceLocation(modelId);
        Path file = profileFile(profileId);
        confined(file);
        String previous = Files.exists(file, LinkOption.NOFOLLOW_LINKS) ? read(file) : null;
        readAssets();
        return new YsmImportSnapshot(profileId, modelId, previous, ASSETS.get(modelId));
    }

    public static synchronized void restoreYsmImport(YsmImportSnapshot snapshot) throws IOException {
        try {
            Path file = profileFile(snapshot.profileId());
            if (snapshot.profileJson() == null) {
                PROFILES.remove(snapshot.profileId());
                CONFIGS.remove(snapshot.profileId());
                READ.remove(snapshot.profileId());
            } else {
                write(file, snapshot.profileJson());
                CONFIGS.put(snapshot.profileId(), CharacterVisualConfig.parse(snapshot.profileJson()));
                READ.add(snapshot.profileId());
                PROFILES.remember(snapshot.profileId());
            }
            readAssets();
            var next = new HashMap<>(ASSETS);
            if (snapshot.assetPath() == null) next.remove(snapshot.modelId());
            else next.put(snapshot.modelId(), snapshot.assetPath());
            writeAssets(next);
        } finally {
            CharacterVisualSharing.refreshSaved();
        }
    }

    public static synchronized void asset(String id,Path source)throws IOException {
        new ResourceLocation(id);readAssets();
        if(ASSETS.size()>=128&&!ASSETS.containsKey(id))throw new IOException("At most 128 local character assets");
        Path path=source.toAbsolutePath().normalize();
        if(!Files.isRegularFile(path))throw new IOException("Character asset does not exist: "+path);
        var next=new HashMap<>(ASSETS);next.put(id,path);
        writeAssets(next);
        RETIRED_MODELS.cancel(cacheKey(id));
    }

    private static void writeAssets(Map<String,Path> next)throws IOException {
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
        writeImportedProfiles(next);
    }

    private static void writeImportedProfiles(Map<String,String> next)throws IOException {
        JsonObject root=new JsonObject();root.addProperty("version",1);JsonArray profiles=new JsonArray();
        new java.util.TreeMap<>(next).forEach((profileId,displayName)->{JsonObject item=new JsonObject();item.addProperty("id",profileId);item.addProperty("name",displayName);profiles.add(item);});
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
            JsonArray profiles=root.getAsJsonArray("profiles");if(profiles.size()>128)throw new IOException("Too many imported avatar profiles");
            var replacement=new HashMap<String,String>();
            for(var value:profiles) {
                if(!value.isJsonObject())throw new IOException("Invalid imported profile entry");
                JsonObject item=value.getAsJsonObject();if(item.size()!=2||!item.has("id")||!item.has("name"))throw new IOException("Invalid imported profile entry");
                if(!item.get("id").isJsonPrimitive()||!item.getAsJsonPrimitive("id").isString()
                        ||!item.get("name").isJsonPrimitive()||!item.getAsJsonPrimitive("name").isString())
                    throw new IOException("Imported profile fields must be strings");
                String id=item.get("id").getAsString(),name=item.get("name").getAsString();validateImportedProfile(id);
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
    private static void validateImportedProfile(String id) {
        ResourceLocation location=new ResourceLocation(id);String path=location.getPath();
        if(!location.getNamespace().equals("rigworks")||!(path.startsWith("imported/ysm/")||path.startsWith("imported/mmd/"))
                ||!path.endsWith("/profile.json")||id.length()>512)
            throw new IllegalArgumentException("Not a local imported avatar profile: "+id);
    }
    public static synchronized void rememberImportedMmdProfile(String id,String name)throws IOException {
        validateImportedProfile(id);
        if(!new ResourceLocation(id).getPath().startsWith("imported/mmd/"))throw new IllegalArgumentException("Expected imported MMD profile");
        readImportedProfiles();
        if(IMPORTED_PROFILES.size()>=128&&!IMPORTED_PROFILES.containsKey(id))throw new IOException("At most 128 imported profiles");
        if(name==null||name.isBlank())name="Imported MMD";
        if(name.length()>80)name=name.substring(0,80);
        var next=new java.util.TreeMap<>(IMPORTED_PROFILES);next.put(id,name);writeImportedProfiles(next);
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
