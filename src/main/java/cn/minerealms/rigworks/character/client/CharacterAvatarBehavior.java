package cn.minerealms.rigworks.character.client;

import cn.minerealms.rigworks.RigWorks;
import cn.minerealms.rigworks.character.core.CharacterVisualPackage;
import cn.minerealms.rigworks.character.script.*;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Dedicated author repository and per-owner verified script publication. No global KubeJS reload. */
@Mod.EventBusSubscriber(modid=RigWorks.MODID,value=Dist.CLIENT)
public final class CharacterAvatarBehavior {
    private static final AvatarPackageRepository REPOSITORY=AvatarPackageRepository.forGame(FMLPaths.GAMEDIR.get());
    private static final class Prepared implements AutoCloseable {
        private final AvatarScriptBundle source;
        private AvatarScriptRuntime.Session session;
        private Prepared(AvatarScriptBundle source,AvatarScriptRuntime.Session session){this.source=source;this.session=session;}
        private AvatarScriptRuntime.Session take(String hash) {
            if(session==null||!session.bundleHash().equals(hash))return null;
            var result=session;session=null;return result;
        }
        @Override public void close(){if(session!=null){session.close();session=null;}}
    }
    private static final CharacterAvatarPublication<Prepared> PUBLICATION=new CharacterAvatarPublication<>(Prepared::close);
    private static final Map<UUID,String> FAILURES=new HashMap<>();
    private record Observation(String sourceHash,String controller,long invocations,boolean available,boolean running){}
    private static final Map<UUID,Observation> OBSERVATIONS=new HashMap<>();
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(task->{Thread t=new Thread(task,"rigworks-avatar-discovery");t.setDaemon(true);return t;});
    private static boolean initialized,refreshing,forced;
    private static long nextPoll;
    private static String fingerprint="",repositoryFailure="";
    private CharacterAvatarBehavior(){}
    public static AvatarPackageRepository repository(){return REPOSITORY;}
    public static Map<UUID,String> failures(){return Map.copyOf(FAILURES);}
    public static String repositoryFailure(){return repositoryFailure;}
    public static Map<String,Object> diagnostics(UUID owner) {
        var observed=OBSERVATIONS.get(owner);
        return Map.of("sourceHash",observed==null?"":observed.sourceHash,"controller",observed==null?"":observed.controller,
                "invocations",observed==null?0L:observed.invocations,"available",observed!=null&&observed.available,
                "running",observed!=null&&observed.running,"failure",FAILURES.getOrDefault(owner,""));
    }
    static void observed(UUID owner,AvatarScriptBundle source,boolean available,boolean running) {
        var previous=OBSERVATIONS.get(owner);long calls=previous==null?0:previous.invocations;
        OBSERVATIONS.put(owner,new Observation(source.hash(),source.controller(),calls+1,available,running));
    }
    static void stopped(UUID owner) {
        var old=OBSERVATIONS.get(owner);if(old!=null)OBSERVATIONS.put(owner,new Observation(old.sourceHash,old.controller,old.invocations,old.available,false));
    }
    static AvatarScriptBundle source(UUID owner,String profile) {
        var local=Minecraft.getInstance().player;
        if(local!=null&&local.getUUID().equals(owner)) {
            var authored=REPOSITORY.forProfile(profile);if(authored.isPresent())return authored.get();
        }
        var active=PUBLICATION.active(owner);var received=active==null?null:active.source;
        return received!=null&&received.profile().equals(profile)?received:null;
    }
    static AvatarScriptRuntime.Session session(UUID owner,AvatarScriptBundle source) {
        var active=PUBLICATION.active(owner);var prepared=active==null?null:active.take(source.hash());
        if(prepared!=null)return prepared;
        return AvatarScriptRuntime.open(source);
    }
    static void failure(UUID owner,String text){if(text==null||text.isBlank())FAILURES.remove(owner);else FAILURES.put(owner,text);}
    public static void refresh(){forced=true;poll();}
    private static void initialize() {
        if(initialized)return;initialized=true;
        CharacterVisualSharing.packageFactory(visual->REPOSITORY.forProfile(LocalPlayerCharacter.settings().profile()).map(bundle->bundle.toPackage(visual)).orElse(null));
        CharacterPackageClient.installer(CharacterAvatarBehavior::install,CharacterAvatarBehavior::activate,PUBLICATION::cancel);
        RigWorksWorkbenchExtension.register();
        refresh();
    }
    private static void install(UUID owner,CharacterVisualPackage bundle) {
        if(bundle==null) {
            PUBLICATION.remove(owner);FAILURES.remove(owner);return;
        }
        if(!bundle.hasScripts()){PUBLICATION.stage(owner,bundle.hash(),new Prepared(null,null));return;}
        AvatarScriptBundle source=AvatarScriptBundle.fromPackage(bundle);
        AvatarScriptRuntime.Session prepared=AvatarScriptRuntime.open(source);
        if(!prepared.available()&&!prepared.failure().contains("Rhino is not installed")) {
            String failure=prepared.failure();prepared.close();throw new IllegalArgumentException(failure);
        }
        PUBLICATION.stage(owner,bundle.hash(),new Prepared(source,prepared));
    }
    private static boolean activate(UUID owner,String hash) {
        if(!PUBLICATION.activate(owner,hash))return false;
        var active=PUBLICATION.active(owner);
        if(active.session==null||active.session.available())FAILURES.remove(owner);else FAILURES.put(owner,active.session.failure());
        return true;
    }
    private static void poll() {
        if(refreshing)return;refreshing=true;boolean force=forced;forced=false;
        IO.execute(()-> {
            String next="",failure="";boolean changed=false;
            try {
                next=fingerprint(REPOSITORY.directory());
                if(force||!fingerprint.equals(next)){
                    var entries=REPOSITORY.refresh();changed=true;
                    failure=entries.stream().filter(entry->!entry.error().isBlank()).map(entry->entry.id()+": "+entry.error()).findFirst().orElse("");
                }
            }catch(Exception error){failure=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
            String result=next,problem=failure;boolean modified=changed;
            Minecraft.getInstance().execute(()-> {
                refreshing=false;fingerprint=result;repositoryFailure=problem;
                if(modified)CharacterVisualSharing.changed();
                if(forced)poll();
            });
        });
    }
    private static String fingerprint(Path root)throws java.io.IOException {
        if(!Files.exists(root))return "missing";
        var metadata=new StringBuilder();int count=0;
        try(var entries=Files.list(root)) {
            for(Path directory:entries.filter(path->Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))
                    .filter(path->!path.getFileName().toString().startsWith(".")&&!path.getFileName().toString().equals("cache")).sorted().toList()) {
                try(var paths=Files.walk(directory,8)) {
                    for(Path file:paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                        if(++count>4096)throw new java.io.IOException("Avatar source repository exceeds 4096 files");
                        metadata.append(root.relativize(file)).append(':').append(Files.size(file)).append(':').append(Files.getLastModifiedTime(file).toMillis()).append('\n');
                    }
                }
            }
        }
        return cn.minerealms.rigworks.character.core.CharacterVisualConfig.hash(metadata.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END)return;initialize();
        long now=System.nanoTime()/50_000_000;
        if(now>=nextPoll){nextPoll=now+40;poll();}
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        PUBLICATION.clear();FAILURES.clear();OBSERVATIONS.clear();nextPoll=0;
    }
    @Mod.EventBusSubscriber(modid=RigWorks.MODID,value=Dist.CLIENT,bus=Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        private Registration(){}
        @SubscribeEvent public static void setup(FMLClientSetupEvent event){event.enqueueWork(CharacterAvatarBehavior::initialize);}
    }
}
