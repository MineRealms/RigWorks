package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.AssimpLoader;
import cn.minerealms.assimploader.workbench.client.ModelWorkbenchScreen;
import cn.minerealms.assimploader.workbench.client.WorkbenchExtensions;
import cn.minerealms.assimploader.client.ui.LookDevNavigation;
import cn.minerealms.assimploader.workbench.io.WorkbenchCodec;
import cn.minerealms.assimploader.workbench.model.ModelTransform;
import cn.minerealms.assimploader.workbench.model.WorkbenchDocument;
import cn.minerealms.rigworks.RigWorks;
import cn.minerealms.rigworks.character.core.CharacterVisualConfig;
import cn.minerealms.rigworks.character.core.PlayerAnimationController;
import cn.minerealms.rigworks.character.core.PlayerVisualInput;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipFile;

/** Opt-in disposable-world checks for the real extension Save/Apply/open/reload paths. */
@Mod.EventBusSubscriber(modid=RigWorks.MODID,value=Dist.CLIENT)
public final class WorkbenchPersistenceSmokeTest {
    private static final boolean ENABLED=Boolean.getBoolean("rigworkspersistence.enabled");
    private static final long START=System.currentTimeMillis();
    private static final Map<String,Object> RESULT=new LinkedHashMap<>();
    private static boolean opening,done,succeeded,ordinaryImportRequested;
    private static int stage;
    private static long changed;
    private static String profile,script;
    private static CharacterVisualConfig original;
    private static ModelWorkbenchScreen workbench;
    private static Path asset,project,archive;
    private static final String SCRIPT_FILE="extensions/rigworks/roundtrip/probe.js";
    private static final String SCRIPT_SOURCE="// Portable project source; deliberately not an active controller.\nconst workbenchRoundtrip = true;\n";
    private static ModelTransform originalRoot,exportedRoot;
    private static WorkbenchDocument.MaterialOverride exportedMaterial;
    private static Map<String,byte[]> behaviorFiles=Map.of();
    private static CompletableFuture<?> saving;
    private static CompletableFuture<Void> sources;
    private WorkbenchPersistenceSmokeTest(){}
    public static boolean succeeded(){return succeeded;}

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if(!ENABLED||done||event.phase!=TickEvent.Phase.END)return;
        var mc=Minecraft.getInstance();
        try {
            require(System.currentTimeMillis()-START<600_000,"Persistence acceptance timed out at stage "+stage);
            if(mc.player==null||mc.level==null) {
                if(!opening&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null) {
                    opening=true;SmokeCompat.openWorld(mc,System.getProperty("rigworkspersistence.world"));
                }
                return;
            }
            if(mc.getOverlay()!=null||LocalPlayerCharacter.diagnostics().status()!=LocalPlayerCharacter.Status.READY)return;
            if(stage==0) {
                if(mc.screen!=null)return;
                original=LocalPlayerCharacter.currentVisualConfig();profile=LocalPlayerCharacter.settings().profile();script=CharacterScriptRuntime.active();
                require(!original.profile().humanoid().isEmpty(),"Acceptance requires an authored character rig");
                require(script!=null,"Acceptance requires the original KubeJS controller");
                originalRoot=CharacterWorkbenchTransforms.display(original);RESULT.put("originalDisplayScale",originalRoot.sx());
                asset=Path.of(System.getProperty("rigworkspersistence.asset"));
                require(Files.isRegularFile(asset),"Acceptance asset is missing: "+asset);
                mc.options.pauseOnLostFocus=false;
                LookDevNavigation.open(LookDevNavigation.EDIT);workbench=(ModelWorkbenchScreen)mc.screen;
                advance();return;
            }
            if(workbench.busy()||System.currentTimeMillis()-changed<750)return;
            if(stage==1) {
                if(!ordinaryImportRequested){ordinaryImportRequested=true;workbench.importPath(asset);return;}
                require(workbench.session()!=null,"Ordinary model import did not create a project");
                require(!workbench.session().document().extensions().has("rigworks"),"Ordinary skeleton import silently became a player model");
                require(WorkbenchExtensions.available(context()).stream().noneMatch(extension->extension.id().equals("rigworks")),"Ordinary import activated the RigWorks extension");
                RESULT.put("ordinaryImportHasNoRigWorksMarker",true);
                workbench.importPath(asset,"rigworks");advance();return;
            }
            if(stage==2) {
                require(workbench.session().document().extensions().has("rigworks"),"Explicit RigWorks import did not initialize its extension");
                if(!sourcesReady())return;
                var imported=visual();
                require(profile.equals(RigWorksWorkbenchExtension.profileId(context())),"Copied FBX lost original character identity");
                var expected=JsonParser.parseString(original.json()).getAsJsonObject();expected.remove("assetEdits");
                var actual=JsonParser.parseString(imported.json()).getAsJsonObject();actual.remove("assetEdits");
                require(expected.equals(actual),"Copied FBX lost authored rig/action/equipment configuration");
                RESULT.put("inheritedOriginalConfiguration",true);
                var document=workbench.session().document();
                LookDevNavigation.open(RigWorksClientSetup.CHARACTERS);
                require(mc.screen instanceof CharacterSelectionScreen,"RigWorks module did not open Characters");
                LookDevNavigation.open(LookDevNavigation.EDIT);
                require(mc.screen==workbench&&document.equals(workbench.session().document()),"Module navigation lost the shared asset editor or edits");
                RESULT.put("projectRetainedAcrossModuleTabs",true);
                WorkbenchExtensions.get("rigworks").rootTransform(context(),scaledRoot(root(),1.2));
                require(!document.materials().isEmpty(),"Acceptance requires textured materials");
                var material=document.materials().get(0);
                exportedMaterial=new WorkbenchDocument.MaterialOverride(material.r(),material.g(),material.b(),material.a(),.65f,.23f,
                        material.emission(),material.alphaMode(),material.cutoff(),material.doubleSided(),material.textures());
                workbench.session().edit(d->d.withMaterial(0,exportedMaterial));
                Path probe=workbench.projectPath().getParent().resolve(SCRIPT_FILE);
                Files.createDirectories(probe.getParent());Files.writeString(probe,SCRIPT_SOURCE,StandardCharsets.UTF_8);
                workbench.refreshExtension();saving=context().saveProject();advance();return;
            }
            if(stage==3) {
                if(!saved())return;
                require(!workbench.session().dirty(),"Character Save did not persist the owning project");
                require(close(CharacterWorkbenchTransforms.display(LocalPlayerCharacter.currentVisualConfig()).sx(),originalRoot.sx()*1.2),"Save did not apply the edited root scale to the character");
                require(exportedMaterial.equals(LocalPlayerCharacter.currentVisualConfig().assetEdits().materials().get(0)),"Save did not apply material edits to this character instance");
                verifyScript(mc);
                project=workbench.projectPath();require(Files.isRegularFile(project),"Project was not written");
                workbench.open(project);advance();return;
            }
            if(stage==4) {
                if(!sourcesReady())return;
                require(close(root().sx(),originalRoot.sx()*1.2),"Reopened shared editor lost saved root scale");
                require(exportedMaterial.equals(workbench.session().document().materials().get(0)),"Reopened shared editor lost material edits");
                RESULT.put("saveApplyReopenScale",root().sx());
                var file=characterFile();Files.writeString(file,scale(visual(),1.4).json(),StandardCharsets.UTF_8);
                Files.setLastModifiedTime(file,FileTime.fromMillis(Math.max(System.currentTimeMillis(),Files.getLastModifiedTime(project).toMillis()+2000)));
                LocalPlayerCharacter.reloadSaved();advance();return;
            }
            if(stage==5) {
                require(close(CharacterWorkbenchTransforms.display(LocalPlayerCharacter.currentVisualConfig()).sx(),originalRoot.sx()*1.4),"Reload retained cached character JSON");
                verifyScript(mc);workbench.open(project);advance();return;
            }
            if(stage==6) {
                if(!sourcesReady())return;
                require(close(root().sx(),originalRoot.sx()*1.4),"Newer character file was masked by an older project snapshot");
                RESULT.put("manualCharacterJsonReloadScale",root().sx());
                require(workbench.session().dirty(),"Newer character configuration was not staged into the owning project");
                workbench.session().revert();
                var document=WorkbenchCodec.read(Files.readString(project));var ext=document.extensions().getAsJsonObject("rigworks");
                ext.add("visual",JsonParser.parseString(scale(visual(),1.6).json()));
                Files.writeString(project,WorkbenchCodec.write(document.withExtension("rigworks",ext)));
                Files.setLastModifiedTime(project,FileTime.fromMillis(Files.getLastModifiedTime(characterFile()).toMillis()+2000));
                workbench.open(project);advance();return;
            }
            if(stage==7) {
                if(!sourcesReady())return;
                require(close(root().sx(),originalRoot.sx()*1.6),"Manual project edit was hidden by older cached configuration");
                saving=context().saveProject();advance();return;
            }
            if(stage==8) {
                if(!saved())return;
                require(close(CharacterWorkbenchTransforms.display(LocalPlayerCharacter.currentVisualConfig()).sx(),originalRoot.sx()*1.6),"Newest project was not applied");
                verifyScript(mc);RESULT.put("manualProjectReopenScale",root().sx());
                require(WorkbenchCodec.read(Files.readString(project)).equals(workbench.session().document()),"Disk project differs from the saved editor document");
                RESULT.put("diskEqualsSavedEditor",true);RESULT.put("project",project.toString());
                RESULT.put("profile",profile);RESULT.put("controller",script);
                exportedRoot=root();behaviorFiles=WorkbenchExtensions.get("rigworks").packageFiles(context());
                archive=project.getParent().getParent().resolve("exports").resolve(project.getParent().getFileName()+".zip");
                Files.deleteIfExists(archive);workbench.export();advance();return;
            }
            if(stage==9) {
                require(Files.isRegularFile(archive),"Shared toolbar did not export a ZIP");
                try(var zip=new ZipFile(archive.toFile())) {
                    require(zip.getEntry("manifest.json")!=null,"Export omitted the versioned manifest");
                    require(zip.getEntry(SCRIPT_FILE)!=null,"Export omitted extension JS");
                    for(var file:behaviorFiles.entrySet()) {
                        if(file.getKey().endsWith("/"))continue;
                        require(zip.getEntry(file.getKey())!=null,"Export omitted avatar source: "+file.getKey());
                        try(var stream=zip.getInputStream(zip.getEntry(file.getKey()))) {
                            require(java.util.Arrays.equals(file.getValue(),stream.readAllBytes()),"Export changed avatar source: "+file.getKey());
                        }
                    }
                }
                saving=context().saveProject();advance();return;
            }
            if(stage==10) {
                if(!saved())return;
                workbench.importPath(archive);advance();return;
            }
            if(stage==11) {
                require(workbench.session().document().extensions().has("rigworks"),"ZIP import lost the RigWorks marker");
                if(!sourcesReady())return;
                require(!project.equals(workbench.projectPath()),"ZIP import replaced the original project");
                require(profile.equals(RigWorksWorkbenchExtension.profileId(context())),"ZIP import lost the original character identity");
                require(root().matrix().equals(exportedRoot.matrix(),.0001f),"ZIP import changed the character root transform");
                require(exportedMaterial.equals(workbench.session().document().materials().get(0)),"ZIP import lost material edits");
                require(SCRIPT_SOURCE.equals(Files.readString(workbench.projectPath().getParent().resolve(SCRIPT_FILE),StandardCharsets.UTF_8)),"ZIP import changed extension JS bytes");
                for(var file:behaviorFiles.entrySet())if(!file.getKey().endsWith("/"))require(java.util.Arrays.equals(file.getValue(),Files.readAllBytes(workbench.projectPath().getParent().resolve(file.getKey()))),"ZIP import changed avatar source: "+file.getKey());
                RESULT.put("zipMarkerTransformMaterialJsRestored",true);RESULT.put("zip",archive.toString());
                RESULT.put("zipAvatarSourceFiles",behaviorFiles.keySet().stream().filter(path->!path.endsWith("/")).count());
                RESULT.put("zipProject",workbench.projectPath().toString());advance();return;
            }
            if(stage==12) {
                require(mc.screen==workbench,"Acceptance must capture the real shared editor");
                require(!workbench.previewFailed(),"Shared editor preview failed");
                var statuses=workbench.scene().getMaterials().stream()
                        .map(material->cn.minerealms.assimploader.render.ModelTextureCache.status(workbench.scene(),material)).toList();
                require(statuses.stream().noneMatch(cn.minerealms.assimploader.render.ModelTextureCache.TextureStatus::failed),"Preview texture preparation failed");
                if(statuses.stream().anyMatch(status->!status.ready()))return;
                if(!workbench.previewRendered()||workbench.previewPbrTextureCount()==0)return;
                RESULT.put("previewPbrTextureCount",workbench.previewPbrTextureCount());RESULT.put("texturesReady",true);
                Files.deleteIfExists(mc.gameDirectory.toPath().resolve("screenshots/workbench-persistence.png"));
                Screenshot.grab(mc.gameDirectory,"workbench-persistence.png",mc.getMainRenderTarget(),message->{});
                advance();return;
            }
            if(stage==13) {
                Path screenshot=mc.gameDirectory.toPath().resolve("screenshots/workbench-persistence.png");
                if(!Files.isRegularFile(screenshot))return;
                RESULT.put("sharedEditorScreenshot",screenshot.toString());
                finish(mc,null);
            }
        }catch(Exception|LinkageError error){finish(mc,error);}
    }
    private static WorkbenchExtensions.Context context() {
        var value=LookDevNavigation.modelContext();
        require(value!=null&&value.session()==workbench.session(),"The shared model context is unavailable");return value;
    }
    private static CharacterVisualConfig visual(){return RigWorksWorkbenchExtension.visual(context());}
    private static ModelTransform root(){return WorkbenchExtensions.get("rigworks").rootTransform(context());}
    private static boolean sourcesReady() {
        if(sources==null){sources=CharacterAuthoringSources.prepare(context());return false;}
        if(!sources.isDone())return false;sources.join();return true;
    }
    private static Object field(Object value,String name)throws Exception {
        var field=value.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(value);
    }
    private static boolean saved(){if(!saving.isDone())return false;saving.join();return true;}
    private static CharacterVisualConfig scale(CharacterVisualConfig config,double size) {
        return CharacterWorkbenchTransforms.edit(config,scaledRoot(CharacterWorkbenchTransforms.display(config),size));
    }
    private static ModelTransform scaledRoot(ModelTransform root,double factor){return root.withScale((float)(originalRoot.sx()*factor),(float)(originalRoot.sy()*factor),(float)(originalRoot.sz()*factor));}
    private static Path characterFile(){return FMLPaths.CONFIGDIR.get().resolve("rigworks/characters/"+CharacterVisualConfig.hash(profile.getBytes(StandardCharsets.UTF_8))+".json");}
    private static boolean close(double value,double expected){return Math.abs(value-expected)<Math.max(1e-7,Math.abs(expected)*.0001);}
    @SuppressWarnings("unchecked")
    private static void verifyScript(Minecraft mc)throws Exception {
        require(profile.equals(LocalPlayerCharacter.settings().profile()),"Apply switched to a generated profile");
        require(script.equals(CharacterScriptRuntime.active()),"Apply lost the active KubeJS controller");
        var bindingField=LocalPlayerCharacter.class.getDeclaredField("active");bindingField.setAccessible(true);var binding=bindingField.get(null);
        var session=(CharacterScriptRuntime.Session)field(binding,"scripts");
        var controller=(PlayerAnimationController)field(binding,"controller");
        var config=LocalPlayerCharacter.currentVisualConfig();
        for(int i=0;i<3;i++) {
            String intent=i==0?"walk":i==1?"run":"idle";
            var input=PlayerVisualInput.builder(10_000+i,500+i*.05).motion(i==2?0:4.3,0,0).sprinting(i==1).build();
            require(session.run(mc.player,config.profile(),controller,input),"Original script did not accept input");
            var state=(Map<String,Object>)field(session,"state");
            require(intent.equals(String.valueOf(state.get("lusia:intent"))),"Script ignored motion input: "+intent);
        }
        require(CharacterScriptRuntime.failure().isEmpty(),"Script failed: "+CharacterScriptRuntime.failure());
        RESULT.put("syntheticScriptWalkRunIdle",true);
    }
    private static void advance(){stage++;changed=System.currentTimeMillis();sources=null;}
    private static void finish(Minecraft mc,Throwable error) {
        if(original!=null&&profile!=null)try{CharacterVisualSharing.save(profile,original);RESULT.put("initialCharacterRestored",true);}
        catch(Exception restoreError){RESULT.put("initialCharacterRestoreFailure",restoreError.toString());if(error==null)error=restoreError;}
        done=true;succeeded=error==null;RESULT.put("passed",error==null);RESULT.put("failure",error==null?"":error.toString());RESULT.put("stage",stage);RESULT.put("clientLeftOpen",true);
        try{Files.writeString(mc.gameDirectory.toPath().resolve("workbench-persistence-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(RESULT));}
        catch(Exception writeError){AssimpLoader.LOGGER.error("Cannot write persistence acceptance",writeError);}
        if(error!=null)AssimpLoader.LOGGER.error("[WorkbenchPersistence] FAILED stage="+stage,error);
        else AssimpLoader.LOGGER.info("[WorkbenchPersistence] PASS; client remains open");
    }
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
