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
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Only explicit local projects publish editable sources. Network packages never use this service. */
final class CharacterAuthoringSources {
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
        Path project=context.projectPath()==null?null:context.projectPath().toAbsolutePath().normalize().getParent();
        var result=new CompletableFuture<Void>();PENDING.put(context.session(),result);
        try {
            CompletableFuture.supplyAsync(()->{
                try{return load(extension,profile,template,ownSources,project);}
                catch(IOException|RuntimeException error){throw new CompletionException(error);}
            },IO).whenComplete((bundle,error)->Minecraft.getInstance().execute(()->{
                try {
                    if(error!=null){result.completeExceptionally(error);return;}
                    if(!asset.equals(context.session().document().assetId()))throw new IllegalStateException("The imported asset changed while its sources were loading");
                    if(bundle!=null){
                        var next=context.session().document().extensions().getAsJsonObject("rigworks");
                        if(next==null||!Objects.equals(profile,next.get("profileId").getAsString()))throw new IllegalStateException("The avatar changed while its sources were loading");
                        next.addProperty("profileId",bundle.profile());next.addProperty("authoringProject",projectToken);
                        next.add("behavior",encode(bundle));context.session().edit(d->d.withExtension("rigworks",next));
                    }
                    INITIALIZED.put(context.session(),true);result.complete(null);
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
    static Map<String,byte[]> packageFiles(WorkbenchExtensions.Context context){
        var pending=PENDING.get(context.session());
        if(pending!=null&&!pending.isDone())throw new IllegalStateException("Avatar sources are still loading");
        if(pending!=null&&pending.isCompletedExceptionally())throw new IllegalStateException("Avatar sources contain errors; reload them before export");
        var extension=context.session().document().extensions().getAsJsonObject("rigworks");
        var result=new LinkedHashMap<String,byte[]>();
        result.put("extensions/rigworks/behavior/",new byte[0]);
        if(extension==null||!extension.has("behavior"))return Map.copyOf(result);
        var bundle=decode(extension.getAsJsonObject("behavior"),RigWorksWorkbenchExtension.profileId(context));
        bundle.files().forEach((path,source)->result.put("extensions/rigworks/behavior/"+path,source.getBytes(StandardCharsets.UTF_8)));
        return Map.copyOf(result);
    }

    private static AvatarScriptBundle load(JsonObject extension,String profile,boolean template,boolean ownSources,Path project)throws IOException{
        var repository=CharacterAvatarBehavior.repository();repository.refresh();
        if(ownSources&&extension.has("behavior")){
            String id=extension.getAsJsonObject("behavior").get("id").getAsString();
            if(repository.find(id).isPresent()){
                var reloaded=repository.reload(id);if(!reloaded.valid())throw new IOException(reloaded.error());
                return reloaded.bundle();
            }
        }
        AvatarScriptBundle source=ownSources&&extension.has("behavior")?decode(extension.getAsJsonObject("behavior"),profile):project==null?null:readArchive(project);
        if(source==null&&extension.has("behavior"))source=decode(extension.getAsJsonObject("behavior"),profile);
        if(source!=null&&!source.profile().equals(profile))source=AvatarPackageRepository.rebind(source,source.id(),profile);
        if(source==null){
            var existing=repository.forProfile(profile).orElse(null);
            if(existing!=null){var reloaded=repository.reload(existing.id());if(!reloaded.valid())throw new IOException(reloaded.error());return reloaded.bundle();}
            if(template)source=template(profile);
        }
        return source==null?null:repository.importLocal(source).bundle();
    }

    private static AvatarScriptBundle readArchive(Path project)throws IOException{
        Path root=project.resolve("extensions/rigworks/behavior");
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return null;
        if(!root.toRealPath().startsWith(project.toRealPath()))throw new IOException("Avatar archive sources escape the project folder");
        return AvatarPackageRepository.readBundle(root);
    }

    private static AvatarScriptBundle template(String profile){
        String key=CharacterVisualConfig.hash(profile.getBytes(StandardCharsets.UTF_8)).substring(0,24),id="rigworks:avatar/"+key;
        var manifest=new JsonObject();manifest.addProperty("version",1);manifest.addProperty("id",id);manifest.addProperty("profile",profile);manifest.addProperty("controller",id);
        var scripts=new com.google.gson.JsonArray();scripts.add("scripts/controller.js");manifest.add("scripts",scripts);manifest.addProperty("mappings","config/mappings.json");
        return new AvatarScriptBundle(id,profile,id,Map.of("manifest.json",manifest.toString(),"config/mappings.json","{}",
                "scripts/controller.js","RigWorks.register('"+id+"', function(frame) {\n  frame.defaultAnimation();\n});\n"));
    }

    private static String projectToken(WorkbenchExtensions.Context context){
        String key=context.projectPath()==null?context.session().document().assetId():context.projectPath().toAbsolutePath().normalize().toString();
        return CharacterVisualConfig.hash(key.getBytes(StandardCharsets.UTF_8));
    }
    private static JsonObject encode(AvatarScriptBundle bundle){var value=new JsonObject();value.addProperty("id",bundle.id());value.addProperty("controller",bundle.controller());var files=new JsonObject();bundle.files().forEach(files::addProperty);value.add("files",files);return value;}
    private static AvatarScriptBundle decode(JsonObject value,String profile){var files=new LinkedHashMap<String,String>();value.getAsJsonObject("files").entrySet().forEach(e->files.put(e.getKey(),e.getValue().getAsString()));return new AvatarScriptBundle(value.get("id").getAsString(),profile,value.get("controller").getAsString(),files);}
}
