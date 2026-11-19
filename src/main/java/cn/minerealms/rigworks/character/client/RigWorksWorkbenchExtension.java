package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.core.AsyncModelLoader;
import cn.minerealms.assimploader.lookdev.preview.PreviewSceneData;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.assimploader.workbench.client.WorkbenchExtensions;
import cn.minerealms.assimploader.workbench.model.*;
import cn.minerealms.rigworks.RigWorksConfig;
import cn.minerealms.rigworks.character.core.*;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Character semantics contribute panels to the engine editor; no second viewport or edit session. */
public final class RigWorksWorkbenchExtension implements WorkbenchExtensions.Extension {
    public static final String KEY="rigworks";
    private static boolean registered;
    private static final String[] ROLES={"root","body","hips","spine","chest","neck","head","left_shoulder","left_upper_arm","left_forearm","left_hand","right_shoulder","right_upper_arm","right_forearm","right_hand","left_thigh","left_shin","left_foot","right_thigh","right_shin","right_foot"};
    private static final String[] ACTIONS={"idle","walk","run","walk_backward","strafe_left","strafe_right","crouch","crouch_walk","jump","fall","swim","fly","elytra","ride","sleep","dead","use_item","swing","crawl","crawl_idle","ladder_up","ladder_down","ladder_idle","water_idle","lava","riptide","frozen","hurt","land"};
    private String role=ROLES[0],action=ACTIONS[0];

    public static void register(){if(!registered){registered=true;WorkbenchExtensions.register(new RigWorksWorkbenchExtension());}}
    @Override public String id(){return KEY;}
    @Override public String title(){return "RigWorks";}
    @Override public boolean supports(WorkbenchExtensions.Context context){return context!=null&&context.session()!=null&&context.session().document().extensions().has(KEY);}
    @Override public boolean canImport(){return true;}
    @Override public boolean handlesApplication(){return true;}
    @Override public boolean ownsRootTransform(){return true;}
    @Override public Screen open(WorkbenchExtensions.Context context){return context.parent();}

    @Override public CompletableFuture<Void> initialize(WorkbenchExtensions.Context context){
        if(supports(context))return CompletableFuture.completedFuture(null);
        if(!context.scene().hasSkeleton())return CompletableFuture.failedFuture(new IllegalArgumentException("A player model needs a skeleton"));
        var mc=Minecraft.getInstance();
        String stem=CharacterVisualConfig.hash(context.session().document().assetId().getBytes(StandardCharsets.UTF_8)).substring(0,24);
        ResourceLocation model=context.scene().getSourceResource();
        if(model==null)model=new ResourceLocation("rigworks","models/workbench/"+stem+"/model.fbx");
        final ResourceLocation id=model;
        final CharacterVisualConfig current=LocalPlayerCharacter.currentVisualConfig();
        final String currentId=LocalPlayerCharacter.settings().profile();
        try{CharacterVisualSharing.registerAsset(id,context.scene());}
        catch(IOException error){return CompletableFuture.failedFuture(error);}
        var result=new CompletableFuture<Void>();
        AsyncModelLoader.decodeAsync("rigworks:import/"+stem+"@"+cn.minerealms.assimploader.core.AssimpModelLoader.getCacheGeneration(),()->{
            CharacterVisualSharing.modelHash(mc.getResourceManager(),id.toString());return context.scene();
        }).whenComplete((scene,error)->mc.execute(()->{
            if(error!=null){result.completeExceptionally(error);return;}
            try{
                String hash=CharacterVisualSharing.cachedModelHash(mc.getResourceManager(),id.toString());
                var inherited=CharacterWorkbenchInheritance.resolve(null,null,currentId,current,hash);
                CharacterVisualConfig visual;String profileId;
                if(inherited!=null){visual=inherited.visual();profileId=inherited.profileId();}
                else{
                    visual=discover(scene,id.toString(),hash);
                    profileId="rigworks:workbench/"+stem+"/profile.json";
                }
                store(context,profileId,visual);
                CharacterAuthoringSources.initialize(context,profileId,inherited==null).whenComplete((unused,failure)->mc.execute(()->{
                    if(failure!=null)result.completeExceptionally(failure);else result.complete(null);
                }));
            }catch(RuntimeException failure){result.completeExceptionally(failure);}
        }));
        return result;
    }

    /** Shared bone-role and clip autodiscovery for workbench imports and in-memory YSM imports. */
    public static CharacterVisualConfig discover(AssimpScene scene,String modelId,String hash){
        var metadata=SceneMetadata.inspect(scene);var nodes=metadata.nodes().stream().map(SceneMetadata.NodeInfo::name).distinct().toList();
        var clips=metadata.clips().stream().map(SceneMetadata.ClipInfo::name).toList();
        var bones=new LinkedHashMap<String,CharacterProfile.HumanoidBone>();
        for(String name:ROLES){String node=suggest(nodes,name);if(node!=null)bones.put(name,new CharacterProfile.HumanoidBone(node,CharacterProfile.Rotation.IDENTITY));}
        var actions=new LinkedHashMap<String,String>();for(String name:ACTIONS){String clip=suggest(clips,name);if(clip!=null)actions.put(name,clip);}
        float height=new PreviewSceneData(scene).size().y;
        var profile=new CharacterProfile(modelId,Math.max(.001,Math.min(100,height>0?1.8/height:1)),bones,actions,null,.15,.05,CharacterProfile.HeadLook.defaults());
        return new CharacterVisualConfig(profile,CharacterEquipmentDefinition.defaults(profile),CharacterVisualConfig.Transform.IDENTITY,hash,null,null);
    }

    public static CharacterVisualConfig visual(WorkbenchExtensions.Context context){return CharacterVisualConfig.parse(extension(context).get("visual").toString());}
    public static String profileId(WorkbenchExtensions.Context context){return extension(context).get("profileId").getAsString();}
    private static JsonObject extension(WorkbenchExtensions.Context context){return context.session().document().extensions().getAsJsonObject(KEY);}
    private static void store(WorkbenchExtensions.Context context,String id,CharacterVisualConfig visual){
        var data=extension(context);if(data==null)data=new JsonObject();
        data.addProperty("version",1);data.addProperty("profileId",id);data.add("visual",CharacterVisualConfig.parseBounded(visual.json()));
        var value=data;context.session().edit(document->document.withExtension(KEY,value));
    }
    private static void update(WorkbenchExtensions.Context context,CharacterVisualConfig visual){store(context,profileId(context),visual);}

    @Override public void opened(WorkbenchExtensions.Context context){
        if(!supports(context))return;
        if(Objects.equals(context.session().document().extensions().get(KEY),context.session().savedDocument().extensions().get(KEY))){
            try{var saved=CharacterVisualSharing.newerSaved(profileId(context),context.projectPath());if(saved!=null)update(context,saved);}
            catch(IOException error){throw new IllegalArgumentException("Cannot read newer character edits",error);}
        }
        if(!context.session().document().transform().equals(ModelTransform.IDENTITY)){
            rootTransform(context,context.session().document().transform());context.session().edit(d->d.withTransform(ModelTransform.IDENTITY));
        }
        context.runTask("Reading avatar sources",()->CharacterAuthoringSources.initialize(context,profileId(context),false)).thenRun(context::refresh);
    }
    @Override public ModelTransform rootTransform(WorkbenchExtensions.Context context){return CharacterWorkbenchTransforms.display(visual(context));}
    @Override public void rootTransform(WorkbenchExtensions.Context context,ModelTransform transform){update(context,CharacterWorkbenchTransforms.edit(visual(context),transform));}
    @Override public CompletableFuture<Void> prepareAsync(WorkbenchExtensions.Context context){return CharacterAuthoringSources.prepare(context);}
    @Override public void prepare(WorkbenchExtensions.Context context){
        var document=context.session().document();
        var edits=new WorkbenchDocument(1,"rigworks:instance","assets/model.fbx","",ModelTransform.IDENTITY,document.nodes(),document.materials(),document.sockets(),WorkbenchDocument.AnimationPreview.DEFAULT,new JsonObject());
        update(context,visual(context).withAssetEdits(edits));
    }
    @Override public void saved(WorkbenchExtensions.Context context){activate(context);}
    @Override public void apply(WorkbenchExtensions.Context context){activate(context);}
    @Override public Map<String,byte[]> packageFiles(WorkbenchExtensions.Context context){return CharacterAuthoringSources.packageFiles(context);}
    private void activate(WorkbenchExtensions.Context context){applyCharacter(profileId(context),visual(context),context.scene());}

    /** Shared Save/Apply flow; a null scene means the asset was registered by the importer itself. */
    public static void applyCharacter(String profileId,CharacterVisualConfig config,AssimpScene scene){
        try{
            if(scene!=null)CharacterVisualSharing.registerAsset(new ResourceLocation(config.profile().model()),scene);
            CharacterVisualSharing.save(profileId,config);
            if(scene!=null)CharacterVisualSharing.textureSource(profileId,scene);
            RigWorksConfig.update(edit->edit.set(RigWorksConfig.CLIENT.profile,profileId).set(RigWorksConfig.CLIENT.enabled,true));
            LocalPlayerCharacter.refreshConfiguration();LocalPlayerCharacter.applyVisualConfig(config);
            if(LocalPlayerCharacter.currentAnimation()==null)LocalPlayerCharacter.reload();
            CharacterAvatarBehavior.refresh();
        }catch(IOException|RuntimeException error){throw new IllegalArgumentException("Character apply failed: "+error.getMessage(),error);}
    }
    @Override public List<WorkbenchExtensions.Panel> panels(WorkbenchExtensions.Context context){
        return List.of(panel("rig","Rig",this::rig),panel("actions","Actions",this::actions),panel("equipment","Equipment",this::equipment),panel("behavior","Behavior",this::behavior));
    }
    private WorkbenchExtensions.Panel panel(String id,String title,Consumer<WorkbenchExtensions.Inspector> body){
        return new WorkbenchExtensions.Panel(){public String id(){return id;}public String title(){return title;}public void build(WorkbenchExtensions.Inspector inspector){body.accept(inspector);}};
    }
    private static CharacterProfile copy(CharacterProfile p,Map<String,CharacterProfile.HumanoidBone> bones,Map<String,String> actions){return new CharacterProfile(p.model(),p.scale(),bones,actions,p.physicsDefinition(),p.blendSeconds(),p.movementThreshold(),p.headLook(),p.solverDefinition(),p.modelYawDegrees(),p.equipmentDefinition(),p.timing());}
    private static void profile(WorkbenchExtensions.Context context,CharacterProfile next){
        var visual=visual(context);var defaults=CharacterEquipmentDefinition.defaults(next);var equipment=visual.equipment();
        equipment=equipment.withHands(equipment.rightHand()==null?defaults.rightHand():equipment.rightHand(),equipment.leftHand()==null?defaults.leftHand():equipment.leftHand());
        update(context,new CharacterVisualConfig(next,equipment,visual.transform(),visual.modelHash(),visual.rig(),visual.physics(),visual.options(),visual.assetEdits()));
    }
    private void rig(WorkbenchExtensions.Inspector ui){
        var context=ui.context();var visual=visual(context);var bone=visual.profile().bone(role);
        ui.select("Humanoid role",List.of(ROLES),role,next->role=next);
        ui.text("Bone node",bone==null?"":bone.node(),text->{
            var current=visual(context).profile();var bones=new LinkedHashMap<>(current.humanoid());
            if(text.isBlank())bones.remove(role);else{
                if(SceneMetadata.inspect(context.scene()).nodes().stream().noneMatch(node->node.name().equals(text)))throw new IllegalArgumentException("Unknown bone node");
                var old=bones.get(role);bones.put(role,new CharacterProfile.HumanoidBone(text,old==null?CharacterProfile.Rotation.IDENTITY:old.referenceRotation(),old==null?CharacterProfile.Rotation.IDENTITY:old.nativeRotation()));
            }
            profile(context,copy(current,bones,current.animations()));
        });
        if(bone!=null)for(int kind=0;kind<2;kind++){
            int target=kind;var euler=(kind==0?bone.referenceRotation():bone.nativeRotation()).quaternion().getEulerAnglesXYZ(new Vector3f()).mul((float)(180/Math.PI));
            ui.label(kind==0?"Reference calibration":"Native calibration");
            for(int axis=0;axis<3;axis++){int component=axis;ui.number("XYZ".substring(axis,axis+1),euler.get(axis),value->{
                var current=visual(context).profile();var entry=current.bone(role);var angles=(target==0?entry.referenceRotation():entry.nativeRotation()).quaternion().getEulerAnglesXYZ(new Vector3f());angles.setComponent(component,(float)Math.toRadians(value));
                var q=new Quaternionf().rotationXYZ(angles.x,angles.y,angles.z);var rotation=new CharacterProfile.Rotation(q.x,q.y,q.z,q.w);
                var bones=new LinkedHashMap<>(current.humanoid());bones.put(role,new CharacterProfile.HumanoidBone(entry.node(),target==0?rotation:entry.referenceRotation(),target==1?rotation:entry.nativeRotation()));
                profile(context,copy(current,bones,current.animations()));
            });}
        }
    }
    private void actions(WorkbenchExtensions.Inspector ui){
        var context=ui.context();ui.select("Game action",List.of(ACTIONS),action,next->action=next);
        ui.text("Animation clip",visual(context).profile().animations().getOrDefault(action,""),text->{
            var current=visual(context).profile();var clips=new LinkedHashMap<>(current.animations());
            if(text.isBlank())clips.remove(action);else{
                if(context.scene().getAnimations().stream().noneMatch(clip->clip.getName().equals(text)))throw new IllegalArgumentException("Unknown animation clip");
                clips.put(action,text);
            }
            profile(context,copy(current,current.humanoid(),clips));
        });
        ui.action("Preview action",()->context.previewClip(visual(context).profile().animations().getOrDefault(action,"")));
        ui.action("Next clip",()->{
            var clips=context.scene().getAnimations().stream().map(clip->clip.getName()).toList();if(clips.isEmpty())return;
            var current=visual(context).profile();var mapping=new LinkedHashMap<>(current.animations());mapping.put(action,clips.get((clips.indexOf(mapping.get(action))+1)%clips.size()));
            profile(context,copy(current,current.humanoid(),mapping));context.refresh();
        });
    }
    private void equipment(WorkbenchExtensions.Inspector ui){
        var context=ui.context();
        ui.action("Calibrate held items",()->context.saveProject().thenRun(()->EquipmentTransformScreen.open(context.parent())));
        String[] names={"Hand contacts","Rig physics","Ground placement","Character effects"};
        for(int i=0;i<4;i++){int index=i;ui.toggle(names[i],()->options(visual(context))[index],()->{
            var value=visual(context);var options=options(value);options[index]=!options[index];update(context,value.withOptions(new CharacterVisualConfig.Options(options[0],options[1],options[2],options[3])));
        });}
    }
    private static boolean[] options(CharacterVisualConfig visual){var o=visual.options();return new boolean[]{o.handContacts(),o.physics(),o.groundPlacement(),o.effects()};}
    private void behavior(WorkbenchExtensions.Inspector ui){
        var context=ui.context();ui.label(profileId(context));
        ui.action("Reload avatar sources",()->context.runTask("Reloading avatar sources",()->CharacterAuthoringSources.reload(context)));
        ui.action("Create behavior template",()->context.runTask("Creating avatar sources",()->CharacterAuthoringSources.initialize(context,profileId(context),true)).thenRun(context::refresh));
    }
    private static String suggest(List<String> values,String target){
        String wanted=normalize(target);
        for(String value:values)if(normalize(value).equals(wanted))return value;
        String alias=switch(target){case "body","hips"->"pelvis";case "left_upper_arm"->"leftarm";case "right_upper_arm"->"rightarm";case "left_thigh"->"leftupleg";case "right_thigh"->"rightupleg";default->wanted;};
        for(String value:values)if(normalize(value).endsWith(alias))return value;
        return null;
    }
    private static String normalize(String text){return text.toLowerCase(Locale.ROOT).replace("mixamorig","").replaceAll("[^a-z0-9]","");}
}
