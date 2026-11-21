package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.client.ui.LookDevNavigation;
import cn.minerealms.assimploader.client.ui.WorkbenchPageRegistry;
import cn.minerealms.assimploader.debug.ClientDiagnostics;
import cn.minerealms.rigworks.RigWorks;
import cn.minerealms.rigworks.character.importing.AvatarImportRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Supplies optional UI and diagnostics through engine extension points. */
@Mod.EventBusSubscriber(modid = RigWorks.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RigWorksClientSetup {
    public static final WorkbenchPageRegistry.Key CHARACTERS = new WorkbenchPageRegistry.Key(RigWorks.MODID, "characters");
    public static final WorkbenchPageRegistry.Key RIG_EDITOR = new WorkbenchPageRegistry.Key(RigWorks.MODID, "rig-editor");
    public static final WorkbenchPageRegistry.Key EQUIPMENT = new WorkbenchPageRegistry.Key(RigWorks.MODID, "equipment");
    private static final AtomicBoolean YSM_IMPORT_BUSY = new AtomicBoolean();
    private RigWorksClientSetup() {}

    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            LookDevNavigation.registerModule(new WorkbenchPageRegistry.Module(RigWorks.MODID, "RigWorks", 100));
            LookDevNavigation.registerPage(new WorkbenchPageRegistry.Page<>(CHARACTERS, "Characters", 0, () -> new CharacterSelectionScreen(null)));
            LookDevNavigation.registerAlias(LookDevNavigation.Page.PLAYER, CHARACTERS);
            ClientDiagnostics.register(RigWorks.MODID, LocalPlayerCharacter::currentPhysics, () -> {
                var physics = LocalPlayerCharacter.currentPhysics();
                String details = physics == null ? "RigPhysics OFF" : "Physics bodies/joints "
                        + physics.statistics().bodies() + "/" + physics.statistics().joints()
                        + "  substeps " + physics.statistics().lastSubsteps();
                return List.of(details, "RigWorks " + LocalPlayerCharacter.animationMode());
            });
        });
    }

    public static void importCharacter() {
        LookDevNavigation.open(LookDevNavigation.EDIT);
        if(net.minecraft.client.Minecraft.getInstance().screen instanceof cn.minerealms.assimploader.workbench.client.ModelWorkbenchScreen editor)
            editor.requestImport(RigWorksWorkbenchExtension.KEY);
    }

    public static void editCurrentYsmCharacter() {
        var client = Minecraft.getInstance();
        if (!client.isSameThread()) throw new IllegalStateException("YSM Workbench import must start on the client thread");
        var animation = LocalPlayerCharacter.currentAnimation();
        var visual = LocalPlayerCharacter.currentVisualConfig();
        if (animation == null || visual == null || !visual.profile().model().endsWith(".rwym")) {
            if (client.player != null) client.player.displayClientMessage(Component.literal("Load a converted YSM avatar before editing it"), false);
            return;
        }
        Path renderCache = animation.getScene().getSourcePath();
        if (renderCache == null || !Files.isRegularFile(renderCache)) {
            if (client.player != null) client.player.displayClientMessage(Component.literal("The YSM render cache is unavailable"), false);
            return;
        }
        String profileId = LocalPlayerCharacter.settings().profile();
        try { RigWorksWorkbenchExtension.beginYsmEdit(profileId, visual, renderCache); }
        catch (RuntimeException error) {
            cn.minerealms.assimploader.AssimpLoader.LOGGER.warn("Cannot open YSM Workbench for profile {} from {}", profileId, renderCache, error);
            if (client.player != null) client.player.displayClientMessage(Component.literal("Cannot open YSM Workbench: " + error.getMessage()), false);
            return;
        }
        LookDevNavigation.open(LookDevNavigation.EDIT);
        Screen screen = client.screen;
        if (screen instanceof cn.minerealms.assimploader.workbench.client.ModelWorkbenchScreen editor) {
            editor.importPath(renderCache, RigWorksWorkbenchExtension.KEY);
        } else {
            RigWorksWorkbenchExtension.cancelYsmEdit();
            if (client.player != null)
            client.player.displayClientMessage(Component.literal("Model Workbench is unavailable"), false);
        }
    }

    /** Opens RigWorks' own picker through the import SPI, scoped to the YSM handler. */
    public static void importYsmCharacter(Consumer<String> onImported) {
        var client = net.minecraft.client.Minecraft.getInstance();
        if (!YSM_IMPORT_BUSY.compareAndSet(false, true)) {
            if (client.screen instanceof CharacterSelectionScreen screen) screen.ysmImportStatus("An import is already running");
            return;
        }
        String activeProfile = LocalPlayerCharacter.settings().profile();
        if (client.screen instanceof CharacterSelectionScreen screen) screen.ysmImportStatus("Selecting YSM file");
        AvatarImportRegistry.get().importFromDisk("ysm").whenComplete((ignored, error) -> client.execute(() -> {
            String status;
            try {
                if (error != null) {
                    status = "YSM import failed: " + (error.getMessage() == null ? error.toString() : error.getMessage());
                    net.minecraft.network.chat.Component message = net.minecraft.network.chat.Component.literal(status);
                    cn.minerealms.assimploader.AssimpLoader.LOGGER.warn("YSM character import failed", error);
                    if (client.player != null) client.player.displayClientMessage(message, false);
                } else {
                    String selected = LocalPlayerCharacter.settings().profile();
                    if (!selected.equals(activeProfile)) {
                        if (selected.startsWith("rigworks:imported/ysm/")) try {
                            CharacterVisualOverrides.rememberImportedYsmProfile(selected);
                        } catch (java.io.IOException | IllegalArgumentException catalogError) {
                            cn.minerealms.assimploader.AssimpLoader.LOGGER.warn("Could not persist imported YSM profile {}", selected, catalogError);
                        }
                        if (onImported != null) onImported.accept(selected);
                    }
                    status = "YSM import complete";
                }
            } catch (RuntimeException callbackError) {
                status = "YSM import completed with a UI error";
                cn.minerealms.assimploader.AssimpLoader.LOGGER.warn("YSM import completion callback failed", callbackError);
            } finally {
                YSM_IMPORT_BUSY.set(false);
            }
            if (client.screen instanceof CharacterSelectionScreen screen) screen.ysmImportStatus(status);
        }));
    }

    public static boolean ysmImportBusy() { return YSM_IMPORT_BUSY.get(); }
}
