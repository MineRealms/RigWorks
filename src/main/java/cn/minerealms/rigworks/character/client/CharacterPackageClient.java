package cn.minerealms.rigworks.character.client;

import cn.minerealms.rigworks.RigWorks;
import cn.minerealms.rigworks.character.core.*;
import cn.minerealms.rigworks.network.CharacterPackageNetwork;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/** Only verified packages reach the installer. IO and hash validation run off the client/render thread. */
@Mod.EventBusSubscriber(modid=RigWorks.MODID,value=Dist.CLIENT)
public final class CharacterPackageClient {
    private static final Map<UUID,String> WANTED=new HashMap<>();
    private static final CharacterPackageApplications APPLICATIONS=new CharacterPackageApplications();
    private static final Map<String,CharacterVisualPackage> READY=new HashMap<>();
    private static final Map<String,CharacterPackageTransfer> TRANSFERS=new HashMap<>();
    private static final Map<String,Long> REQUESTED=new HashMap<>(),RETRY=new HashMap<>();
    private static final Set<String> CHECKING=new HashSet<>();
    private static final Set<String> CHECKED=new HashSet<>();
    private static final Map<String,CharacterVisualPackage> LOCAL=new LinkedHashMap<>();
    private static final Map<String,Integer> ATTEMPTS=new HashMap<>();
    private static final Map<UUID,Long> INSTALL_RETRY=new HashMap<>();
    private static final Map<UUID,Integer> INSTALL_FAILURES=new HashMap<>();
    private static final Map<UUID,String> APPLICATION_STATUS=new HashMap<>();
    private static final ThreadPoolExecutor IO=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),task->{Thread t=new Thread(task,"rigworks-package-client");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static BiConsumer<UUID,CharacterVisualPackage> installer=(id,bundle)->{};
    private static BiPredicate<UUID,String> activator=(id,hash)->true;
    private static Consumer<UUID> cancelInstallation=id->{};
    private static long generation,nextRequest;
    private static UUID serverIdentity;
    private static String status="";
    private static volatile Set<String> cachePins=Set.of();
    private static boolean remoteAllowed=true;
    private CharacterPackageClient(){}
    public static void installer(BiConsumer<UUID,CharacterVisualPackage> value) {
        installer(value,(id,hash)->true,id->{});
    }
    public static void installer(BiConsumer<UUID,CharacterVisualPackage> prepare,BiPredicate<UUID,String> activate,Consumer<UUID> cancel) {
        installer=Objects.requireNonNull(prepare);activator=Objects.requireNonNull(activate);cancelInstallation=Objects.requireNonNull(cancel);
        APPLICATIONS.clear();refreshPins();installReady();
    }
    public static String status(){return status;}
    public static String applicationStatus(UUID owner){return APPLICATION_STATUS.getOrDefault(owner,"");}
    public static void applicationReady(UUID owner) {
        String hash=APPLICATIONS.applying(owner);
        if(hash!=null) {
            var bundle=READY.get(hash);var local=Minecraft.getInstance().player;
            if(!enabled(owner)||!hash.equals(WANTED.get(owner))||!matches(owner,bundle))return;
            if(local!=null&&owner.equals(local.getUUID())&&!bundle.visual().equals(LocalPlayerCharacter.currentVisualConfig()))return;
            Object binding=currentBinding(owner);if(!APPLICATIONS.confirmable(owner,hash,binding))return;
            if(!activator.test(owner,hash)){applicationFailed(owner,"Prepared character behavior is no longer current");return;}
            APPLICATIONS.confirm(owner,hash,binding);INSTALL_RETRY.remove(owner);INSTALL_FAILURES.remove(owner);refreshPins();
        }
        APPLICATION_STATUS.put(owner,"Character configuration applied");status="Character configuration applied";
    }
    public static void applicationFailed(UUID owner,String reason) {
        if(APPLICATIONS.reject(owner)!=null) {
            INSTALL_RETRY.put(owner,tick()+100);INSTALL_FAILURES.merge(owner,1,Integer::sum);
            restoreApplied(owner);refreshPins();
        }
        String fallback=usable(owner)?"previous verified character configuration retained":"using vanilla player";
        status=(reason==null?"Character application failed":reason)+"; "+fallback;APPLICATION_STATUS.put(owner,status);
    }
    private static void restoreApplied(UUID owner) {
        var previous=READY.get(APPLICATIONS.applied(owner));
        CharacterVisualSharing.packageFallback(owner,previous);
        cancelInstallation.accept(owner);
    }
    private static boolean usable(UUID owner) {
        var local=Minecraft.getInstance().player;
        return local!=null&&(owner.equals(local.getUUID())?LocalPlayerCharacter.hasUsableBinding():RemotePlayerCharacters.hasUsableBinding(owner));
    }
    private static Object currentBinding(UUID owner) {
        var client=Minecraft.getInstance();
        if(client.player==null)return null;
        if(owner.equals(client.player.getUUID()))return LocalPlayerCharacter.currentAnimation();
        if(client.level==null||!(client.level.getPlayerByUUID(owner) instanceof net.minecraft.client.player.AbstractClientPlayer player))return null;
        var view=RemotePlayerCharacters.view(player,0);return view==null?null:view.animation();
    }
    public static void server(UUID identity) {
        if(serverIdentity!=null&&!serverIdentity.equals(identity))clear();serverIdentity=identity;
    }
    public static String wanted(UUID owner){return WANTED.getOrDefault(owner,"");}
    public static void local(CharacterVisualPackage bundle){LOCAL.put(bundle.hash(),bundle);while(LOCAL.size()>4)LOCAL.remove(LOCAL.keySet().iterator().next());}
    private static boolean enabled(UUID owner) {
        var local=Minecraft.getInstance().player;
        return local!=null&&(owner.equals(local.getUUID())||CharacterVisualSharing.remoteEnabled());
    }
    private static boolean wanted(String hash){return WANTED.entrySet().stream().anyMatch(e->e.getValue().equals(hash)&&enabled(e.getKey()));}
    public static CharacterVisualPackage current(UUID owner) {
        if(!enabled(owner))return null;
        var hash=APPLICATIONS.applied(owner);var bundle=hash==null?null:READY.get(hash);
        return matches(owner,bundle)?bundle:null;
    }
    public static void offer(UUID owner,String hash) {
        if(hash==null||hash.isEmpty()) {
            WANTED.remove(owner);INSTALL_RETRY.remove(owner);INSTALL_FAILURES.remove(owner);APPLICATION_STATUS.remove(owner);
            boolean installed=APPLICATIONS.applied(owner)!=null||APPLICATIONS.applying(owner)!=null;
            APPLICATIONS.forget(owner);refreshPins();if(installed)installer.accept(owner,null);return;
        }
        if(!hash.matches("[a-f0-9]{64}")||WANTED.size()>=512&&!WANTED.containsKey(owner))return;
        String previous=WANTED.put(owner,hash);
        if(!hash.equals(previous)){
            if(APPLICATIONS.reject(owner)!=null) {
                restoreApplied(owner);
                var local=Minecraft.getInstance().player;
                if(local!=null&&owner.equals(local.getUUID()))LocalPlayerCharacter.reloadVisual();
            }
            INSTALL_RETRY.remove(owner);INSTALL_FAILURES.remove(owner);
        }
        refreshPins();
        if(!enabled(owner))return;
        if(LOCAL.containsKey(hash)){READY.put(hash,LOCAL.remove(hash));CHECKED.add(hash);}
        if(READY.containsKey(hash)){installReady();return;}
        if(!CHECKING.add(hash))return;
        CHECKED.add(hash);Path cacheDirectory=root();
        long token=generation;
        try {
            IO.execute(()-> {
                CharacterVisualPackage cached=null;
                try{Path file=cacheDirectory.resolve(hash+".bin");if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&Files.size(file)<=CharacterPackageBinary.MAX_BYTES)cached=decode(hash,Files.readAllBytes(file));}
                catch(Exception ignored){}
                CharacterVisualPackage result=cached;
                Minecraft.getInstance().execute(()-> {
                    if(token!=generation)return;CHECKING.remove(hash);if(!wanted(hash))return;
                    if(result!=null){READY.put(hash,result);installReady();}else RETRY.put(hash,0L);
                });
            });
        }catch(RejectedExecutionException e){CHECKING.remove(hash);RETRY.put(hash,0L);}
    }
    public static void retry() {ATTEMPTS.clear();RETRY.replaceAll((key,value)->0L);INSTALL_RETRY.clear();INSTALL_FAILURES.clear();}
    public static void remotePolicyChanged() {
        remoteAllowed=CharacterVisualSharing.remoteEnabled();
        for(UUID owner:APPLICATIONS.owners())if(!enabled(owner)) {
            APPLICATIONS.forget(owner);INSTALL_RETRY.remove(owner);INSTALL_FAILURES.remove(owner);APPLICATION_STATUS.remove(owner);
            installer.accept(owner,null);
        }
        for(String hash:List.copyOf(REQUESTED.keySet()))if(!wanted(hash)){REQUESTED.remove(hash);TRANSFERS.remove(hash);}
        CHECKED.removeIf(hash->!wanted(hash));
        refreshPins();
    }
    public static void begin(String hash,String wireHash,int size) {
        if(!REQUESTED.containsKey(hash)||TRANSFERS.size()>=2||!wanted(hash))return;
        try{TRANSFERS.put(hash,new CharacterPackageTransfer(wireHash,size));}
        catch(RuntimeException e){failed(hash,e.getMessage());}
    }
    public static void part(String hash,int index,byte[] bytes) {
        var transfer=TRANSFERS.get(hash);if(transfer==null)return;
        if(!wanted(hash)){TRANSFERS.remove(hash);REQUESTED.remove(hash);return;}
        try {
            if(!transfer.append(index,bytes))return;
            TRANSFERS.remove(hash);byte[] source=transfer.finish();long token=generation;Path cacheDirectory=root();
            CHECKING.add(hash);REQUESTED.remove(hash);
            IO.execute(()-> {
                try {
                    CharacterVisualPackage bundle=decode(hash,source);writeCache(cacheDirectory,hash,source);
                    Minecraft.getInstance().execute(()-> {
                        if(token!=generation)return;CHECKING.remove(hash);if(!wanted(hash))return;
                        READY.put(hash,bundle);ATTEMPTS.remove(hash);RETRY.remove(hash);installReady();
                    });
                }catch(Exception error){Minecraft.getInstance().execute(()->{if(token==generation)failed(hash,error.getMessage());});}
            });
        }catch(RuntimeException e){failed(hash,e.getMessage());}
    }
    public static void failed(String hash,String reason) {
        if(!wanted(hash)&&!REQUESTED.containsKey(hash)&&!CHECKING.contains(hash))return;
        TRANSFERS.remove(hash);REQUESTED.remove(hash);CHECKING.remove(hash);
        long tick=tick();RETRY.put(hash,tick+100);status="Character package: "+(reason==null?"download failed":reason);
        String problem=status;
        WANTED.forEach((owner,wanted)->{if(wanted.equals(hash)&&enabled(owner))applicationFailed(owner,problem);});
        cn.minerealms.assimploader.AssimpLoader.LOGGER.warn("{} ({})",status,hash);
    }
    private static CharacterVisualPackage decode(String hash,byte[] bytes) {
        try {
            var bundle=CharacterPackageBinary.decode(bytes);
            if(!bundle.hash().equals(hash))throw new IllegalArgumentException("Downloaded package identity is not canonical");return bundle;
        }catch(java.io.IOException e){throw new IllegalArgumentException("Invalid compressed character package",e);}
    }
    private static boolean matches(UUID owner,CharacterVisualPackage bundle) {
        return CharacterVisualSharing.acceptsPackage(owner,bundle);
    }
    private static void installReady() {
        for(var entry:WANTED.entrySet()) {
            if(!enabled(entry.getKey()))continue;
            if(entry.getValue().equals(APPLICATIONS.applied(entry.getKey()))||entry.getValue().equals(APPLICATIONS.applying(entry.getKey())))continue;
            if(tick()<INSTALL_RETRY.getOrDefault(entry.getKey(),0L)||INSTALL_FAILURES.getOrDefault(entry.getKey(),0)>=3)continue;
            CharacterVisualPackage bundle=READY.get(entry.getValue());if(bundle==null)continue;
            if(!matches(entry.getKey(),bundle)){applicationFailed(entry.getKey(),"Package configuration differs from accepted revision");continue;}
            try {
                APPLICATIONS.begin(entry.getKey(),entry.getValue(),currentBinding(entry.getKey()));refreshPins();
                status="Character package verified; applying model: "+bundle.profile();APPLICATION_STATUS.put(entry.getKey(),status);
                installer.accept(entry.getKey(),bundle);
                CharacterVisualSharing.packageReady(entry.getKey(),bundle);
                var local=Minecraft.getInstance().player;
                if(local!=null&&local.getUUID().equals(entry.getKey()))LocalPlayerCharacter.reloadVisual();
                else RemotePlayerCharacters.reload(entry.getKey());
            }catch(RuntimeException e){
                applicationFailed(entry.getKey(),"Character package install failed: "+e.getMessage());
            }
        }
        refreshPins();READY.keySet().removeIf(hash->!cachePins.contains(hash));
    }
    private static void refreshPins(){var retained=new HashSet<>(APPLICATIONS.retained());WANTED.forEach((owner,hash)->{if(enabled(owner))retained.add(hash);});cachePins=Set.copyOf(retained);}
    private static long tick(){var player=Minecraft.getInstance().player;return player==null?0:player.tickCount;}
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||Minecraft.getInstance().player==null)return;
        long now=tick();
        if(remoteAllowed!=CharacterVisualSharing.remoteEnabled())remotePolicyChanged();
        installReady();
        for(String hash:List.copyOf(REQUESTED.keySet()))if(!wanted(hash)){REQUESTED.remove(hash);TRANSFERS.remove(hash);}
        for(String hash:List.copyOf(REQUESTED.keySet()))if(now-REQUESTED.get(hash)>600)failed(hash,"download timed out");
        if(now<nextRequest||REQUESTED.size()>=2)return;
        for(String hash:new HashSet<>(WANTED.values())) {
            if(!wanted(hash)||READY.containsKey(hash)||CHECKING.contains(hash)||REQUESTED.containsKey(hash)||now<RETRY.getOrDefault(hash,0L)||ATTEMPTS.getOrDefault(hash,0)>=3)continue;
            if(!CHECKED.contains(hash)){WANTED.entrySet().stream().filter(e->e.getValue().equals(hash)&&enabled(e.getKey())).findFirst().ifPresent(e->offer(e.getKey(),hash));continue;}
            REQUESTED.put(hash,now);ATTEMPTS.merge(hash,1,Integer::sum);CharacterPackageNetwork.request(hash);nextRequest=now+5;break;
        }
    }
    private static Path root(){
        if(serverIdentity==null)throw new IllegalStateException("Server identity has not arrived");
        return FMLPaths.GAMEDIR.get().resolve("kubejs/rigworks/cache").resolve(serverIdentity.toString()).toAbsolutePath().normalize();
    }
    private static void writeCache(Path root,String hash,byte[] bytes)throws java.io.IOException {
        Files.createDirectories(root);Path target=root.resolve(hash+".bin");
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
            if(Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)&&Files.size(target)<=CharacterPackageBinary.MAX_BYTES) {
                try{decode(hash,Files.readAllBytes(target));return;}catch(RuntimeException ignored){}
            }
            Files.delete(target);
        }
        long total=0;var candidates=new ArrayList<Path>();
        try(var list=Files.list(root)){for(Path file:list.filter(p->p.getFileName().toString().matches("[a-f0-9]{64}\\.bin")).toList())
            if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)){total+=Files.size(file);candidates.add(file);}}
        candidates.sort(Comparator.comparingLong(file->{try{return Files.getLastModifiedTime(file).toMillis();}catch(java.io.IOException e){return 0L;}}));
        for(Path file:candidates){if(total+bytes.length<=67108864)break;if(cachePins.contains(file.getFileName().toString().substring(0,64)))continue;long size=Files.size(file);Files.delete(file);total-=size;}
        if(total+bytes.length>67108864)throw new java.io.IOException("Character package client cache reached 64 MiB");
        Path temporary=Files.createTempFile(root,".download-",".tmp");
        try {
            Files.write(temporary,bytes);try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,target);}
        }finally{Files.deleteIfExists(temporary);}
    }
    private static void clear() {
        for(UUID owner:APPLICATIONS.owners())installer.accept(owner,null);
        generation++;WANTED.clear();APPLICATIONS.clear();cachePins=Set.of();READY.clear();LOCAL.clear();TRANSFERS.clear();REQUESTED.clear();RETRY.clear();CHECKING.clear();CHECKED.clear();ATTEMPTS.clear();INSTALL_RETRY.clear();INSTALL_FAILURES.clear();APPLICATION_STATUS.clear();nextRequest=0;status="";
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {clear();serverIdentity=null;}
}
