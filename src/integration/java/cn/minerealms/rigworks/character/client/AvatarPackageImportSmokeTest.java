package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.AssimpLoader;
import cn.minerealms.assimploader.client.ui.LookDevNavigation;
import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.core.ModelImportMode;
import cn.minerealms.assimploader.workbench.client.ModelWorkbenchScreen;
import cn.minerealms.assimploader.workbench.client.WorkbenchAssets;
import cn.minerealms.assimploader.workbench.client.WorkbenchExtensions;
import cn.minerealms.assimploader.workbench.io.WorkbenchProjectStore;
import cn.minerealms.assimploader.workbench.model.WorkbenchSession;
import cn.minerealms.assimploader.util.ResourceLocations;
import cn.minerealms.rigworks.RigWorks;
import cn.minerealms.rigworks.character.script.AvatarPackageRepository;
import cn.minerealms.rigworks.character.script.AvatarScriptBundle;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipFile;

/** Opt-in disposable-world acceptance of actual import dialogs, authoring files and player binding. */
@Mod.EventBusSubscriber(modid=RigWorks.MODID,value=Dist.CLIENT)
public final class AvatarPackageImportSmokeTest {
    private static final boolean ENABLED=Boolean.getBoolean("avatarpackagetest.enabled");
    private static final long START=System.currentTimeMillis();
    private static final Map<String,Object> RESULT=new LinkedHashMap<>();
    private static boolean opening,done;private static int stage;private static long changed;
    private static ModelWorkbenchScreen editor;
    private static CompletableFuture<WorkbenchProjectStore.Project> prepared;
    private static CompletableFuture<?> saving;
    private static AvatarScriptBundle original;
    private static UUID copy;
    private static Path archive,script,beforeCancel;
    private static WorkbenchSession beforeSession;
    private static long scriptModified;private static int sourceCount,projectCount;
    private static final String SOURCE="rigworks:models/character/block_player/block_player.fbx";
    private static final String EXTERNAL_ASSET=System.getProperty("avatarpackagetest.asset","");
    private static String importedProfile;
    private static cn.minerealms.rigworks.character.core.CharacterVisualConfig importedVisual;
    private static boolean importedScriptsIncluded;
    private static cn.minerealms.rigworks.RigWorksConfig.CharacterAnimationMode expectedMotionMode;
    private static boolean externalImportStarted;
    private AvatarPackageImportSmokeTest(){}

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
        if(!ENABLED||done||event.phase!=TickEvent.Phase.END)return;
        var mc=Minecraft.getInstance();
        try{
            require(System.currentTimeMillis()-START<600_000,"Timed out at stage "+stage);
            if(mc.player==null||mc.level==null){
                if(!opening&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null){opening=true;SmokeCompat.openWorld(mc,System.getProperty("avatarpackagetest.world"));}
                return;
            }
            if(mc.getOverlay()!=null)return;
            if(!EXTERNAL_ASSET.isBlank()) { externalTick(mc); return; }
            if(stage==0){
                if(mc.screen!=null)return;
                mc.options.pauseOnLostFocus=false;
                RigWorksWorkbenchExtension.register();
                var resources=mc.getResourceManager();
                var store=new WorkbenchProjectStore(mc.gameDirectory.toPath().resolve("config/assimploader/workbench"));
                prepared=CompletableFuture.supplyAsync(()->{
                    try{
                        var scene=AssimpModelLoader.loadCachedResource(resources,ResourceLocations.parse(SOURCE),ModelImportMode.DYNAMIC);
                        return WorkbenchAssets.importLoaded(store,scene,resources);
                    }catch(Exception error){throw new java.util.concurrent.CompletionException(error);}
                });
                LookDevNavigation.open(LookDevNavigation.EDIT);editor=(ModelWorkbenchScreen)mc.screen;advance();return;
            }
            if(System.currentTimeMillis()-changed<500)return;
            if(stage==1){
                if(editor.busy()||!prepared.isDone())return;
                var project=prepared.join();
                Path model=project.scene().getSourcePath();
                require(Files.isRegularFile(model.resolveSibling("profile.json")),"Resource import omitted profile");
                require(Files.isRegularFile(model.resolveSibling("rig.json")),"Resource import omitted rig definition");
                require(Files.isRegularFile(model.resolveSibling("equipment.json")),"Resource import omitted equipment");
                require(Files.isRegularFile(model.resolveSibling("physics.json")),"Resource import omitted physics");
                require(Files.isRegularFile(model.resolveSibling("block_player.instance.json")),"Resource import omitted instance sidecar");
                editor.importPath(model,"rigworks");advance();return;
            }
            if(stage==2){
                if(editor.busy())return;
                require(editor.session()!=null&&editor.session().document().extensions().has("rigworks"),"FBX import did not initialize avatar");
                var config=RigWorksWorkbenchExtension.visual(context());
                try(var input=mc.getResourceManager().openAsReader(ResourceLocations.parse("rigworks:models/character/block_player/profile.json"))){
                    var expected=cn.minerealms.rigworks.character.core.CharacterProfile.read(input);
                    require(config.profile().humanoid().equals(expected.humanoid())&&config.profile().animations().equals(expected.animations()),"Authored rig/action bindings were lost");
                }
                require(config.rig()!=null&&config.physics()!=null&&config.equipment().rightHand()!=null,"Authored solvers or equipment were lost");
                original=bundle();require(original.files().containsKey("scripts/controller.js"),"New FBX has no editable controller");
                RESULT.put("authoredProfileEquipmentRigPhysicsPreserved",true);RESULT.put("originalInstance",original.instanceId());
                saving=context().saveProject();advance();return;
            }
            if(stage==3){
                if(!saved()||editor.busy())return;
                if(LocalPlayerCharacter.currentInstance()==null)return;
                require(LocalPlayerCharacter.currentInstance().attachments().containsKey("main_hand"),"File-backed instance attachments are inactive");
                RESULT.put("fileBackedInstanceApplied",true);
                archive=editor.projectPath().getParent().getParent().resolve("exports").resolve(editor.projectPath().getParent().getFileName()+".zip");
                editor.export();advance();return;
            }
            if(stage==4){
                if(!title(mc,"Export asset package"))return;
                click(mc.screen,"Export");advance();return;
            }
            if(stage==5){
                if(editor.busy())return;
                require(Files.isRegularFile(archive),"Export dialog did not write ZIP");
                try(var zip=new ZipFile(archive.toFile())){
                    require(zip.getEntry("extensions/rigworks/behavior/scripts/controller.js")!=null,"ZIP omitted opted-in scripts");
                    require(zip.stream().anyMatch(e->e.getName().endsWith("block_player.instance.json")),"ZIP omitted instance sidecar");
                }
                editor.importPath(archive);advance();return;
            }
            if(stage==6){
                if(!title(mc,"Import avatar"))return;
                screenshot(mc,"avatar-package-import-choice.png");click(mc.screen,"Create an independent copy");advance();return;
            }
            if(stage==7){
                if(editor.busy())return;
                var current=bundle();copy=current.instanceId();require(!copy.equals(original.instanceId()),"Generic Import reused archive identity");
                require(!current.profile().equals(original.profile()),"New copy shares original profile");
                RESULT.put("genericImportCreatesIndependentIdentity",true);RESULT.put("copyInstance",copy);
                saving=context().saveProject();advance();return;
            }
            if(stage==8){
                if(!saved()||editor.busy())return;
                editor.open(archive);advance();return;
            }
            if(stage==9){
                if(!title(mc,"Import avatar"))return;
                click(mc.screen,"Create an independent copy");advance();return;
            }
            if(stage==10){
                if(editor.busy())return;
                var current=bundle();require(!current.instanceId().equals(copy)&&!current.instanceId().equals(original.instanceId()),"Open ZIP reused another copy");
                RESULT.put("openZipCreatesIndependentIdentity",true);saving=context().saveProject();advance();return;
            }
            if(stage==11){
                if(!saved()||editor.busy())return;
                var entry=repository().findInstance(original.instanceId()).orElseThrow();
                script=entry.directory().resolve("scripts/controller.js");scriptModified=Files.getLastModifiedTime(script).toMillis();
                sourceCount=repository().entries().size();editor.onFilesDrop(List.of(archive));advance();return;
            }
            if(stage==12){
                if(!title(mc,"Import avatar"))return;
                click(mc.screen,"Update the archive's existing copy");advance();return;
            }
            if(stage==13){
                if(editor.busy())return;
                require(bundle().instanceId().equals(original.instanceId()),"Drop update lost archive identity");
                require(repository().entries().size()==sourceCount,"Identical update created another script copy");
                require(Files.getLastModifiedTime(script).toMillis()==scriptModified,"Identical update rewrote scripts");
                RESULT.put("dropUpdateReusesIdenticalScriptsWithoutWrites",true);
                saving=context().saveProject();advance();return;
            }
            if(stage==14){
                if(!saved()||editor.busy())return;
                Files.writeString(script,"// acceptance local edit\n"+original.files().get("scripts/controller.js"));repository().refresh();
                editor.importPath(archive);advance();return;
            }
            if(stage==15){if(!title(mc,"Import avatar"))return;click(mc.screen,"Update the archive's existing copy");advance();return;}
            if(stage==16){if(!title(mc,"Avatar script conflict"))return;screenshot(mc,"avatar-package-script-conflict.png");click(mc.screen,"Keep the existing scripts");advance();return;}
            if(stage==17){
                if(editor.busy())return;
                require(Files.readString(script).startsWith("// acceptance local edit"),"Keep Existing overwrote local scripts");
                RESULT.put("conflictKeepExisting",true);saving=context().saveProject();advance();return;
            }
            if(stage==18){if(!saved()||editor.busy())return;editor.importPath(archive);advance();return;}
            if(stage==19){if(!title(mc,"Import avatar"))return;click(mc.screen,"Update the archive's existing copy");advance();return;}
            if(stage==20){if(!title(mc,"Avatar script conflict"))return;click(mc.screen,"Replace this copy's scripts");advance();return;}
            if(stage==21){
                if(editor.busy())return;
                require(Files.readString(script).equals(original.files().get("scripts/controller.js")),"Replace did not restore archived source");
                RESULT.put("conflictReplaceOnlyChosenInstance",true);saving=context().saveProject();advance();return;
            }
            if(stage==22){
                if(!saved()||editor.busy())return;
                beforeCancel=editor.projectPath();beforeSession=editor.session();projectCount=projects(mc).listProjects().size();
                editor.importPath(archive);advance();return;
            }
            if(stage==23){if(!title(mc,"Import avatar"))return;click(mc.screen,"Cancel");advance();return;}
            if(stage==24){
                if(editor.busy())return;
                require(editor.session()==beforeSession&&editor.projectPath().equals(beforeCancel),"Cancel did not restore previous edit session");
                require(projects(mc).listProjects().size()==projectCount,"Canceled import left an extra actionable project");
                RESULT.put("cancelRestoresPreviousProjectAndRemovesNewFiles",true);
                editor.open(beforeCancel);advance();return;
            }
            if(stage==25){
                if(editor.busy())return;
                require(bundle().instanceId().equals(original.instanceId()),"Reopen changed avatar identity");
                require(!editor.previewFailed(),"Editor rendering failed");if(!editor.previewRendered())return;
                RESULT.put("reopenUsesSavedIdentity",true);RESULT.put("archive",archive.toString());
                RESULT.put("scriptRoot",script.getParent().getParent().toString());
                screenshot(mc,"avatar-package-final.png");advance();return;
            }
            if(stage==26){
                Path file=mc.gameDirectory.toPath().resolve("screenshots/avatar-package-final.png");if(!Files.isRegularFile(file))return;
                RESULT.put("screenshot",file.toString());
                LookDevNavigation.open(RigWorksClientSetup.CHARACTERS);
                require(mc.screen instanceof CharacterSelectionScreen selection
                        && selection.containsProfile(ResourceLocations.parse(original.profile())),"Saved avatar is absent from Characters");
                RESULT.put("savedProfileListed",true);
                finish(mc,null);
            }
        }catch(Exception|LinkageError error){finish(mc,error);}
    }

    private static void externalTick(Minecraft mc)throws Exception {
        if(stage==0) {
            if(mc.screen!=null)return;
            mc.options.pauseOnLostFocus=false;
            RigWorksWorkbenchExtension.register();
            LookDevNavigation.open(LookDevNavigation.EDIT);editor=(ModelWorkbenchScreen)mc.screen;
            archive=Path.of(EXTERNAL_ASSET);
            externalImportStarted=false;advance();return;
        }
        if(System.currentTimeMillis()-changed<500)return;
        if(stage==1) {
            if(editor==null)return;
            if(!externalImportStarted) {
                if(editor.busy())return;
                externalImportStarted=true;
                editor.importPath(archive,"rigworks");
                changed=System.currentTimeMillis();
                return;
            }
            if(!title(mc,"Import avatar"))return;
            click(mc.screen,"Create an independent copy");advance();return;
        }
        if(stage==2) {
            if(editor.busy())return;
            importedProfile=RigWorksWorkbenchExtension.profileId(context());
            importedVisual=RigWorksWorkbenchExtension.visual(context());
            var extension=editor.session().document().extensions().getAsJsonObject("rigworks");
            importedScriptsIncluded=extension.has("scriptsIncluded")?extension.get("scriptsIncluded").getAsBoolean():extension.has("behavior");
            var behavior=repository().forInstance(RigWorksWorkbenchExtension.instanceId(context()));
            require(!importedScriptsIncluded||behavior.isPresent(),"Archive declared behavior scripts but no source bundle was installed");
            require(importedScriptsIncluded||behavior.isEmpty(),"Script-free archive unexpectedly installed a behavior bundle");
            require(behavior.isEmpty()||behavior.get().profile().equals(importedProfile),"Imported script identity does not match the profile");
            require(!importedVisual.profile().humanoid().isEmpty(),"Archive lost authored humanoid bindings");
            RESULT.put("archive",archive.toString());RESULT.put("importedProfile",importedProfile);
            RESULT.put("model",importedVisual.profile().model());RESULT.put("behaviorFiles",behavior.map(value->value.files().keySet()).orElse(java.util.Set.of()));
            saving=context().saveProject();advance();return;
        }
        if(stage==3) {
            if(!saved()||editor.busy())return;
            var preparedVisual=RigWorksWorkbenchExtension.visual(context());
            requireVisual(importedVisual.withAssetEdits(preparedVisual.assetEdits()),preparedVisual,
                    "Preparing the saved avatar changed its authored configuration");
            importedVisual=preparedVisual;
            require(LocalPlayerCharacter.diagnostics().status()!=LocalPlayerCharacter.Status.FAILED,
                    LocalPlayerCharacter.diagnostics().toString());
            if(LocalPlayerCharacter.diagnostics().status()!=LocalPlayerCharacter.Status.READY)return;
            requireAppliedVisual("Imported visual configuration did not apply");
            require(importedProfile.equals(LocalPlayerCharacter.diagnostics().activeProfile()),"A previous avatar is still displayed");
            require(importedVisual.modelHash().equals(LocalPlayerCharacter.diagnostics().modelHash()),"Loaded model is not the imported FBX");
            RESULT.put("loadedModelHash",LocalPlayerCharacter.diagnostics().modelHash());
            RESULT.put("loadedSource",LocalPlayerCharacter.diagnostics().source());
            RESULT.put("loadedClips",LocalPlayerCharacter.currentAnimation().getScene().getAnimations().stream().map(value->value.getName()).toList());
            CharacterVisualOverrides.invalidate();
            requireVisual(importedVisual,CharacterVisualOverrides.load(importedProfile),"Saved profile was not readable from disk");
            RESULT.put("savedProfileReloadedFromDisk",true);
            cn.minerealms.rigworks.RigWorksConfig.update(edit->edit.set(cn.minerealms.rigworks.RigWorksConfig.CLIENT.profile,
                    "rigworks:models/character/block_player/profile.json"));
            LocalPlayerCharacter.refreshConfiguration();LocalPlayerCharacter.reload();advance();return;
        }
        if(stage==4) {
            require(LocalPlayerCharacter.diagnostics().status()!=LocalPlayerCharacter.Status.FAILED,
                    LocalPlayerCharacter.diagnostics().toString());
            if(LocalPlayerCharacter.diagnostics().status()!=LocalPlayerCharacter.Status.READY)return;
            LookDevNavigation.open(RigWorksClientSetup.CHARACTERS);
            require(mc.screen instanceof CharacterSelectionScreen selection
                    && selection.containsProfile(ResourceLocations.parse(importedProfile)),"Imported avatar is absent from Characters");
            require(((CharacterSelectionScreen)mc.screen).containsProfile(ResourceLocations.parse("rigworks:models/character/block_player/profile.json")),
                    "Local profiles replaced resource-pack profiles");
            RESULT.put("importedAndBuiltinProfilesListed",true);
            expectedMotionMode=importedScriptsIncluded?cn.minerealms.rigworks.RigWorksConfig.CharacterAnimationMode.SCRIPTED
                    :editor.scene().getAnimations().isEmpty()?cn.minerealms.rigworks.RigWorksConfig.CharacterAnimationMode.DEFAULT
                    :LocalPlayerCharacter.animationMode();
            LocalPlayerCharacter.setAnimationMode(expectedMotionMode);
            mc.screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow().setValue(importedProfile);
            click(mc.screen,"Apply");advance();return;
        }
        if(stage==5) {
            require(LocalPlayerCharacter.diagnostics().status()!=LocalPlayerCharacter.Status.FAILED,
                    LocalPlayerCharacter.diagnostics().toString());
            if(LocalPlayerCharacter.diagnostics().status()!=LocalPlayerCharacter.Status.READY)return;
            require(importedProfile.equals(LocalPlayerCharacter.settings().profile()),"Profile selection did not persist");
            requireAppliedVisual("Reselecting the saved avatar lost its configuration");
            require(LocalPlayerCharacter.currentAnimation()!=null,"Selected avatar has no animation owner");
            RESULT.put("reselectSavedProfileApplied",true);
            LookDevNavigation.open(LookDevNavigation.EDIT);editor.open(editor.projectPath());advance();return;
        }
        if(stage==6) {
            if(editor.busy())return;
            require(importedProfile.equals(RigWorksWorkbenchExtension.profileId(context())),"Reopening the project changed its profile");
            require(!editor.previewFailed(),"Imported model preview failed");if(!editor.previewRendered())return;
            if(System.currentTimeMillis()-changed<10_000)return;
            RESULT.put("importedModelPreviewRendered",true);advance();return;
        }
        if(stage==7) {
            RESULT.put("screenshotCapture","Rosetta clientdebug ACTION required");mc.setScreen(null);advance();return;
        }
        if(stage==8) {
            if(System.currentTimeMillis()-changed<10_000)return;
            var diagnostic=LocalPlayerCharacter.diagnostics();
            require(diagnostic.status()==LocalPlayerCharacter.Status.READY&&diagnostic.failure().isEmpty(),diagnostic.toString());
            require(importedProfile.equals(diagnostic.activeProfile()),"The active avatar reverted to a previous copy");
            requireAppliedVisual("The settled avatar lost its configuration");
            require(LocalPlayerCharacter.animationMode()==expectedMotionMode,"The selected motion mode changed after reselecting the avatar");
            var behavior=CharacterAvatarBehavior.diagnostics(mc.player.getUUID());
            if(importedScriptsIncluded) {
                require(repository().forInstance(RigWorksWorkbenchExtension.instanceId(context())).isPresent(),"Imported behavior source bundle disappeared");
                require(Boolean.TRUE.equals(behavior.get("available")),"Avatar interpreter unavailable: "+behavior);
                require(Boolean.TRUE.equals(behavior.get("running")),"Avatar script did not handle a live tick: "+behavior);
            }
            RESULT.put("liveDiagnostics",diagnostic);RESULT.put("liveBehavior",behavior);
            RESULT.put("animationMode",LocalPlayerCharacter.animationMode().name());
            RESULT.put("settledInWorld",true);finish(mc,null);
        }
    }
    private static void requireAppliedVisual(String message)throws java.io.IOException {
        var equipment=cn.minerealms.rigworks.character.core.CharacterEquipmentDefinition.resolve(
                importedVisual.profile(),importedVisual.equipment(),reference->{
                    throw new java.io.IOException("Embedded avatar equipment unexpectedly requested "+reference);
                });
        requireVisual(importedVisual.withEquipment(equipment),LocalPlayerCharacter.currentVisualConfig(),message);
        RESULT.put("runtimeVisualConfigurationMatched",true);
    }
    private static void requireVisual(cn.minerealms.rigworks.character.core.CharacterVisualConfig expected,
                                      cn.minerealms.rigworks.character.core.CharacterVisualConfig actual,String message) {
        require(actual!=null,message+": no visual configuration");
        // Compare every serialized field structurally, allowing equivalent numeric JSON spellings.
        var before=cn.minerealms.rigworks.character.core.CharacterVisualConfig.parseBounded(expected.json()).getAsJsonObject();
        var after=cn.minerealms.rigworks.character.core.CharacterVisualConfig.parseBounded(actual.json()).getAsJsonObject();
        var keys=new java.util.LinkedHashSet<String>(before.keySet());keys.addAll(after.keySet());
        var changed=keys.stream().filter(key->!java.util.Objects.equals(before.get(key),after.get(key))).toList();
        require(changed.isEmpty(),message+": changed fields="+changed+" expected="+expected.digest()+" actual="+actual.digest());
    }
    private static WorkbenchExtensions.Context context(){return new WorkbenchExtensions.Context(editor.scene(),editor.session(),editor);}
    private static AvatarPackageRepository repository(){return CharacterAvatarBehavior.repository();}
    private static AvatarScriptBundle bundle(){return repository().forInstance(RigWorksWorkbenchExtension.instanceId(context())).orElseThrow();}
    private static WorkbenchProjectStore projects(Minecraft mc){return new WorkbenchProjectStore(mc.gameDirectory.toPath().resolve("config/assimploader/workbench"));}
    private static boolean saved(){if(!saving.isDone())return false;saving.join();return true;}
    private static boolean title(Minecraft mc,String title){return mc.screen!=null&&mc.screen.getTitle().getString().equals(title);}
    private static void click(Screen screen,String text){
        var button=screen.children().stream().filter(child->child instanceof Button b&&b.getMessage().getString().equals(text)).map(Button.class::cast).findFirst().orElseThrow();
        require(button.active&&button.visible,"Button is unavailable: "+text);button.onPress();
    }
    private static void screenshot(Minecraft mc,String name){Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),message->{});}
    private static void advance(){stage++;changed=System.currentTimeMillis();AssimpLoader.LOGGER.info("[AvatarPackageAcceptance] stage {}",stage);}
    private static void finish(Minecraft mc,Throwable error){
        done=true;RESULT.put("passed",error==null);RESULT.put("failure",error==null?"":error.toString());RESULT.put("stage",stage);RESULT.put("clientLeftOpen",true);
        try{Files.writeString(mc.gameDirectory.toPath().resolve("avatar-package-import-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(RESULT));}
        catch(Exception failure){AssimpLoader.LOGGER.error("Cannot write avatar import result",failure);}
        if(error==null)AssimpLoader.LOGGER.info("[AvatarPackageAcceptance] PASS; client remains open");
        else AssimpLoader.LOGGER.error("[AvatarPackageAcceptance] FAILED stage="+stage,error);
    }
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
