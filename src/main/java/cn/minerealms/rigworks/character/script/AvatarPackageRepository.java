package cn.minerealms.rigworks.character.script;

import cn.minerealms.rigworks.character.core.CharacterVisualConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Explicit per-avatar reloads. No KubeJS global manager reload and no downloaded-file writes. */
public final class AvatarPackageRepository {
    public record Entry(String id,Path directory,AvatarScriptBundle bundle,String error) {
        public boolean valid(){return bundle!=null;}
    }
    private final Path directory;
    private volatile Map<String,Entry> entries=Map.of();
    private volatile long revision;
    public AvatarPackageRepository(Path directory){this.directory=directory.toAbsolutePath().normalize();}
    public static AvatarPackageRepository forGame(Path gameDirectory){return new AvatarPackageRepository(gameDirectory.resolve("kubejs/rigworks"));}
    public Path directory(){return directory;} public long revision(){return revision;}
    public List<Entry> entries(){return entries.values().stream().sorted(java.util.Comparator.comparing(Entry::id)).toList();}
    public Optional<AvatarScriptBundle> find(String id){var value=entries.get(id);return Optional.ofNullable(value==null?null:value.bundle());}
    public Optional<AvatarScriptBundle> forProfile(String profile){return entries().stream().filter(Entry::valid).map(Entry::bundle).filter(bundle->bundle.profile().equals(profile)).findFirst();}
    public synchronized List<Entry> refresh() throws IOException {
        Files.createDirectories(directory);var replacement=new LinkedHashMap<String,Entry>();
        try(var paths=Files.list(directory)) {
            for(Path folder:paths.filter(path->Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))
                    .filter(path->!path.getFileName().toString().startsWith(".")&&!path.getFileName().toString().equals("cache")).sorted().toList()) {
                if(replacement.size()>=128)break;
                Entry entry=read(folder);
                if(!entry.valid()) {
                    Entry previous=entries.values().stream().filter(old->old.directory().equals(folder)&&old.valid()).findFirst().orElse(null);
                    if(previous!=null)entry=new Entry(previous.id(),folder,previous.bundle(),entry.error());
                }
                if(replacement.containsKey(entry.id()))entry=new Entry(folder.getFileName().toString(),folder,null,"Duplicate avatar ID: "+entry.id());
                replacement.put(entry.id(),entry);
            }
        }
        entries=Map.copyOf(replacement);revision++;return entries();
    }
    public synchronized Entry reload(String id) throws IOException {
        Entry existing=entries.get(id);
        if(existing==null)throw new IOException("Unknown avatar package: "+id);
        Entry updated=read(existing.directory());
        if(!updated.valid())return updated;
        if(!updated.id().equals(id)&&entries.containsKey(updated.id()))throw new IOException("Duplicate avatar ID: "+updated.id());
        var next=new LinkedHashMap<>(entries);next.remove(id);next.put(updated.id(),updated);entries=Map.copyOf(next);revision++;return updated;
    }

    /** Publishes only explicitly imported local sources. Existing author files are never replaced. */
    public synchronized Entry importLocal(AvatarScriptBundle source) throws IOException {
        refresh();
        for(Entry entry:entries())if(entry.valid()&&entry.error().isEmpty()&&entry.bundle().hash().equals(source.hash()))return entry;
        AvatarScriptBundle candidate=source;int suffix=0;
        while(conflicts(candidate)||Files.exists(folder(candidate.profile()),LinkOption.NOFOLLOW_LINKS)) {
            String key=CharacterVisualConfig.hash((source.profile()+"\n"+source.hash()+"\n"+(++suffix)).getBytes(StandardCharsets.UTF_8)).substring(0,24);
            candidate=rebind(source,"rigworks:avatar/"+key,"rigworks:workbench/"+key+"/profile.json");
            for(Entry entry:entries())if(entry.valid()&&entry.error().isEmpty()&&entry.bundle().hash().equals(candidate.hash()))return entry;
        }
        if(entries.size()>=128)throw new IOException("At most 128 local avatar source folders");
        validateScript(candidate);
        Path target=folder(candidate.profile()),staging=Files.createTempDirectory(directory,".authoring-");
        try {
            for(var entry:candidate.files().entrySet()) {
                Path file=staging.resolve(entry.getKey());Files.createDirectories(file.getParent());
                Files.writeString(file,entry.getValue(),StandardCharsets.UTF_8);
            }
            try{Files.move(staging,target,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(staging,target);}
        } finally {
            if(Files.exists(staging,LinkOption.NOFOLLOW_LINKS))try(var paths=Files.walk(staging)) {
                for(Path file:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(file);
            }
        }
        var published=new Entry(candidate.id(),target,candidate,"");
        var next=new LinkedHashMap<>(entries);next.put(published.id(),published);entries=Map.copyOf(next);revision++;
        return published;
    }
    private boolean conflicts(AvatarScriptBundle bundle) {
        return entries.containsKey(bundle.id())||entries.values().stream().anyMatch(entry->entry.valid()&&entry.bundle().profile().equals(bundle.profile()));
    }
    private Path folder(String profile) {return directory.resolve("avatar_"+CharacterVisualConfig.hash(profile.getBytes(StandardCharsets.UTF_8)).substring(0,24));}
    public static AvatarScriptBundle rebind(AvatarScriptBundle source,String id,String profile) {
        var files=new LinkedHashMap<>(source.files());var manifest=source.manifest();
        manifest.addProperty("version",1);manifest.addProperty("id",id);manifest.addProperty("profile",profile);manifest.addProperty("controller",source.controller());
        files.put("manifest.json",manifest.toString());return new AvatarScriptBundle(id,profile,source.controller(),files);
    }

    /** Reads portable local archive contents without publishing them to the author repository. */
    public static AvatarScriptBundle readBundle(Path folder) throws IOException {
        if(!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS))throw new IOException("Avatar source folder must be a directory");
        Path realFolder=folder.toRealPath();var manifest=CharacterVisualConfig.parseBounded(readFile(folder.resolve("manifest.json"))).getAsJsonObject();
        String id=manifest.has("id")?manifest.get("id").getAsString():folder.getFileName().toString();
        String profile=manifest.get("profile").getAsString(),controller=manifest.get("controller").getAsString();
        var files=new LinkedHashMap<String,String>();
        try(var paths=Files.walk(folder,8)) {
            for(Path path:paths.sorted().toList()) {
                if(Files.isSymbolicLink(path))throw new IOException("Avatar source cannot contain symbolic links");
                if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)&&folder.relativize(path).getNameCount()>=8) {
                    try(var children=Files.list(path)){if(children.findAny().isPresent())throw new IOException("Avatar source folder depth exceeds eight levels");}
                }
                if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))continue;
                String name=path.getFileName().toString();if(!name.endsWith(".js")&&!name.endsWith(".json"))continue;
                if(files.size()>=32)throw new IOException("At most 32 JSON/JS files per avatar");
                if(!path.toRealPath().startsWith(realFolder))throw new IOException("Avatar source escapes through symbolic link");
                files.put(folder.relativize(path).toString().replace('\\','/'),readFile(path));
            }
        }
        return new AvatarScriptBundle(id,profile,controller,files);
    }
    private Entry read(Path folder) {
        String fallback=folder.getFileName().toString();
        try {
            Path realRoot=directory.toRealPath();
            if(!folder.toRealPath().startsWith(realRoot))throw new IOException("Avatar package escapes source folder");
            var bundle=readBundle(folder);Entry previous=entries.get(bundle.id());
            if(previous==null||!previous.valid()||!previous.bundle().hash().equals(bundle.hash()))validateScript(bundle);
            return new Entry(bundle.id(),folder,bundle,"");
        } catch(IOException|RuntimeException failure){return new Entry(fallback,folder,null,shortError(failure));}
    }
    private static void validateScript(AvatarScriptBundle bundle) throws IOException {
        try(var session=AvatarScriptRuntime.open(bundle)) {
            if(!session.available()&&!session.failure().startsWith("Rhino is not installed"))throw new IOException(session.failure());
        }
    }
    private static String readFile(Path path) throws IOException {
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Avatar source must be a regular file: "+path.getFileName());
        if(Files.size(path)>65536)throw new IOException("Avatar file exceeds 64 KiB: "+path.getFileName());
        return Files.readString(path,StandardCharsets.UTF_8);
    }
    private static String shortError(Throwable error){String text=error.getMessage();return text==null?error.getClass().getSimpleName():text.substring(0,Math.min(text.length(),512));}
}
