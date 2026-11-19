package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.workbench.client.WorkbenchExtensions;
import cn.minerealms.assimploader.workbench.model.WorkbenchSession;
import cn.minerealms.rigworks.character.core.CharacterVisualConfig;
import cn.minerealms.rigworks.character.script.AvatarPackageRepository;
import cn.minerealms.rigworks.character.script.AvatarScriptBundle;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Only explicit local projects publish editable sources. Network packages never use this service. */
final class CharacterAuthoringSources {
    private record Loaded(AvatarScriptBundle candidate,AvatarScriptBundle existing){}
    private static final ExecutorService IO=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"rigworks-authoring");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final Map<WorkbenchSession,CompletableFuture<Void>> PENDING=new WeakHashMap<>();
    private static final Map<WorkbenchSession,Boolean> INITIALIZED=new WeakHashMap<>();
    private CharacterAuthoringSources(){}

    /** Archive sources take precedence on import; a reopened project follows its editable source folder. */
    static CompletableFuture<Void> initialize(WorkbenchExtensions.Context context,String profile,boolean template){
        var inProgress=PENDING.get(context.session());if(inProgress!=null&&!inProgress.isDone())return inProgress;
        var extension=context.session().document().extensions().getAsJsonObject("rigworks");
        if(extension==null)return CompletableFuture.failedFuture(new IllegalArgumentException("This project is not a RigWorks avatar"));
        String asset=context.session().document().assetId(),projectToken=projectToken(context);
        boolean ownSources=INITIALIZED.containsKey(context.session())||extension.has("authoringProject")&&projectToken.equals(extension.get("authoringProject").getAsString());
        boolean scriptsIncluded=extension.has("scriptsIncluded")?extension.get("scriptsIncluded").getAsBoolean():extension.has("behavior");
        UUID instanceId=RigWorksWorkbenchExtension.instanceId(context);
        String sourceProfile=extension.has("sourceProfileId")?extension.get("sourceProfileId").getAsString():"";
        Path project=context.projectPath()==null?null:context.projectPath().toAbsolutePath().normalize().getParent();
        var result=new CompletableFuture<Void>();PENDING.put(context.session(),result);
        try {
            CompletableFuture.supplyAsync(()->{
                try{return load(extension,profile,instanceId,sourceProfile,template,ownSources,scriptsIncluded,project);}
                catch(IOException|RuntimeException error){throw new CompletionException(error);}
            },IO).whenComplete((loaded,error)->Minecraft.getInstance().execute(()->{
                try {
                    if(error!=null){result.completeExceptionally(error);return;}
                    if(!asset.equals(context.session().document().assetId()))throw new IllegalStateException("The imported asset changed while its sources were loading");
                    var current=context.session().document().extensions().getAsJsonObject("rigworks");
                    if(current==null||!Objects.equals(profile,current.get("profileId").getAsString()))throw new IllegalStateException("The avatar changed while its sources were loading");
                    resolve(context,profile,loaded).whenComplete((bundle,failure)->Minecraft.getInstance().execute(()->{
                        try{
                            if(failure!=null){result.completeExceptionally(failure);return;}
                            if(bundle!=null){
                                var next=context.session().document().extensions().getAsJsonObject("rigworks");
                                if(next==null||!Objects.equals(profile,next.get("profileId").getAsString()))throw new IllegalStateException("The avatar changed while its scripts were being selected");
                                next.addProperty("profileId",bundle.profile());next.addProperty("instanceId",bundle.instanceId().toString());
                                next.addProperty("authoringProject",projectToken);next.addProperty("scriptsIncluded",true);
                                next.remove("sourceProfileId");next.add("behavior",encode(bundle));
                                context.session().edit(d->d.withExtension("rigworks",next));
                            }else{
                                var next=context.session().document().extensions().getAsJsonObject("rigworks");
                                next.addProperty("scriptsIncluded",false);next.remove("sourceProfileId");next.remove("behavior");
                                context.session().edit(d->d.withExtension("rigworks",next));
                            }
                            INITIALIZED.put(context.session(),true);result.complete(null);
                        }catch(RuntimeException failureDuringApply){result.completeExceptionally(failureDuringApply);}
                    }));
                } catch(RuntimeException failure){result.completeExceptionally(failure);}
            }));
        } catch(RuntimeException rejected){result.completeExceptionally(rejected);}
        return result;
    }

    /** Await before Save/Apply/Export. File reads and interpreter validation run on the authoring worker. */
    static CompletableFuture<Void> prepare(WorkbenchExtensions.Context context){return initialize(context,RigWorksWorkbenchExtension.profileId(context),false);}

    static CompletableFuture<Void> reload(WorkbenchExtensions.Context context){
        return prepare(context).thenRun(()->{CharacterAvatarBehavior.refresh();context.refresh();});
    }

    /** Exports the prepared immutable snapshot; no directory scan occurs on the render thread. */
    static Map<String,byte[]> packageFiles(WorkbenchExtensions.Context context){return packageFiles(context,true);}

    static Map<String,byte[]> packageFiles(WorkbenchExtensions.Context context,boolean includeScripts){
        var pending=PENDING.get(context.session());
        if(pending!=null&&!pending.isDone())throw new IllegalStateException("Avatar sources are still loading");
        if(pending!=null&&pending.isCompletedExceptionally())throw new IllegalStateException("Avatar sources contain errors; reload them before export");
        var extension=context.session().document().extensions().getAsJsonObject("rigworks");
        var result=new LinkedHashMap<String,byte[]>();
        result.put("extensions/rigworks/behavior/",new byte[0]);
        if(!includeScripts||extension==null||!extension.has("behavior"))return Map.copyOf(result);
        var bundle=decode(extension.getAsJsonObject("behavior"),RigWorksWorkbenchExtension.profileId(context));
        bundle.files().forEach((path,source)->result.put("extensions/rigworks/behavior/"+path,source.getBytes(StandardCharsets.UTF_8)));
        return Map.copyOf(result);
    }

    private static Loaded load(JsonObject extension,String profile,UUID instanceId,String sourceProfile,
                               boolean template,boolean ownSources,boolean scriptsIncluded,Path project)throws IOException{
        var repository=CharacterAvatarBehavior.repository();repository.refresh();
        if(ownSources&&scriptsIncluded){
            var existing=repository.findInstance(instanceId).orElse(null);
            if(existing!=null){var reloaded=repository.reload(existing.id());if(!reloaded.valid())throw new IOException(reloaded.error());return new Loaded(reloaded.bundle(),reloaded.bundle());}
        }
        AvatarScriptBundle source=project==null?null:readArchive(project);
        if(source==null&&extension.has("behavior"))source=decode(extension.getAsJsonObject("behavior"),profile);
        if(source==null&&!sourceProfile.isBlank()){
            var inherited=repository.forProfile(sourceProfile).orElse(null);
            if(inherited!=null)source=AvatarPackageRepository.rebind(inherited,"rigworks:avatar/"+instanceId,profile,instanceId);
        }
        if(source==null&&ownSources&&scriptsIncluded)source=repository.forInstance(instanceId).orElse(null);
        if(source==null&&template)source=template(profile,instanceId);
        if(source!=null){
            if(!source.profile().equals(profile)||!source.instanceId().equals(instanceId))
                source=AvatarPackageRepository.rebind(source,"rigworks:avatar/"+instanceId,profile,instanceId);
            var existing=repository.findInstance(source.instanceId()).map(AvatarPackageRepository.Entry::bundle).orElse(null);
            return new Loaded(source,existing);
        }
        return new Loaded(null,null);
    }

    private static CompletableFuture<AvatarScriptBundle> resolve(WorkbenchExtensions.Context context,String profile,Loaded loaded){
        if(loaded.candidate()==null)return CompletableFuture.completedFuture(loaded.existing());
        var candidate=loaded.candidate();var existing=loaded.existing();
        if(existing==null||existing.hash().equals(candidate.hash()))return publish(candidate);
        String message="Different KubeJS scripts already exist for this avatar copy.";
        return context.choose("Avatar script conflict",message,List.of("Replace this copy's scripts","Import as a new copy","Keep the existing scripts"))
                .thenCompose(choice->CompletableFuture.supplyAsync(()->{
                    try{
                        var repository=CharacterAvatarBehavior.repository();
                        if(choice==0){
                            var current=repository.forInstance(existing.instanceId()).orElseThrow(()->new IOException("The existing avatar scripts disappeared"));
                            if(!current.hash().equals(existing.hash()))throw new IOException("Avatar scripts changed while the conflict dialog was open; reload and retry");
                            var replacement=AvatarPackageRepository.rebind(candidate,current.id(),current.profile(),current.instanceId());
                            return repository.replaceLocal(current.instanceId(),existing.hash(),replacement).bundle();
                        }
                        if(choice==1){
                            UUID fork=UUID.randomUUID();String forkProfile="rigworks:avatars/"+fork+"/profile.json";
                            String forkId="rigworks:avatar/"+fork;
                            var copy=AvatarPackageRepository.rebind(candidate,forkId,forkProfile,fork);
                            return repository.importLocal(copy).bundle();
                        }
                        if(choice==2){
                            var reloaded=repository.reload(existing.id());
                            if(reloaded.valid()&&!reloaded.bundle().hash().equals(existing.hash()))
                                throw new IOException("Avatar scripts changed while the conflict dialog was open; reload and retry");
                            return existing;
                        }
                        throw new java.util.concurrent.CancellationException("Avatar import canceled");
                    }catch(IOException error){throw new CompletionException(error);}
                },IO));
    }

    private static CompletableFuture<AvatarScriptBundle> publish(AvatarScriptBundle candidate){
        return CompletableFuture.supplyAsync(()->{
            try{return CharacterAvatarBehavior.repository().importLocal(candidate).bundle();}
            catch(IOException error){throw new CompletionException(error);}
        },IO);
    }

    private static AvatarScriptBundle readArchive(Path project)throws IOException{
        Path root=project.resolve("extensions/rigworks/behavior");
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||!Files.isRegularFile(root.resolve("manifest.json"),LinkOption.NOFOLLOW_LINKS))return null;
        if(!root.toRealPath().startsWith(project.toRealPath()))throw new IOException("Avatar archive sources escape the project folder");
        return AvatarPackageRepository.readBundle(root);
    }

    private static AvatarScriptBundle template(String profile,UUID instanceId){
        String id="rigworks:avatar/"+instanceId;
        var manifest=new JsonObject();manifest.addProperty("version",1);manifest.addProperty("id",id);manifest.addProperty("profile",profile);manifest.addProperty("controller",id);manifest.addProperty("instanceId",instanceId.toString());
        var scripts=new com.google.gson.JsonArray();scripts.add("scripts/controller.js");manifest.add("scripts",scripts);manifest.addProperty("mappings","config/mappings.json");
        return new AvatarScriptBundle(id,profile,id,instanceId,Map.of("manifest.json",manifest.toString(),"config/mappings.json","{}",
                "scripts/controller.js","RigWorks.register('"+id+"', function(frame) {\n  frame.defaultAnimation();\n});\n"));
    }

    private static String projectToken(WorkbenchExtensions.Context context){
        String key=context.projectPath()==null?context.session().document().assetId():context.projectPath().toAbsolutePath().normalize().toString();
        return CharacterVisualConfig.hash(key.getBytes(StandardCharsets.UTF_8));
    }
    private static JsonObject encode(AvatarScriptBundle bundle){var value=new JsonObject();value.addProperty("id",bundle.id());value.addProperty("profile",bundle.profile());value.addProperty("controller",bundle.controller());value.addProperty("instanceId",bundle.instanceId().toString());var files=new JsonObject();bundle.files().forEach(files::addProperty);value.add("files",files);return value;}
    private static AvatarScriptBundle decode(JsonObject value,String profile){
        var files=new LinkedHashMap<String,String>();value.getAsJsonObject("files").entrySet().forEach(e->files.put(e.getKey(),e.getValue().getAsString()));
        String sourceProfile=value.has("profile")?value.get("profile").getAsString():profile;
        if(value.has("instanceId"))return new AvatarScriptBundle(value.get("id").getAsString(),sourceProfile,value.get("controller").getAsString(),UUID.fromString(value.get("instanceId").getAsString()),files);
        return new AvatarScriptBundle(value.get("id").getAsString(),sourceProfile,value.get("controller").getAsString(),files);
    }
}
