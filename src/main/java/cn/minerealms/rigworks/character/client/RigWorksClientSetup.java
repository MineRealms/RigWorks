package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.client.ui.LookDevNavigation;
import cn.minerealms.assimploader.client.ui.WorkbenchPageRegistry;
import cn.minerealms.assimploader.debug.ClientDiagnostics;
import cn.minerealms.rigworks.RigWorks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import java.util.List;

/** Supplies optional UI and diagnostics through engine extension points. */
@Mod.EventBusSubscriber(modid = RigWorks.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RigWorksClientSetup {
    public static final WorkbenchPageRegistry.Key CHARACTERS = new WorkbenchPageRegistry.Key(RigWorks.MODID, "characters");
    public static final WorkbenchPageRegistry.Key RIG_EDITOR = new WorkbenchPageRegistry.Key(RigWorks.MODID, "rig-editor");
    public static final WorkbenchPageRegistry.Key EQUIPMENT = new WorkbenchPageRegistry.Key(RigWorks.MODID, "equipment");
    private RigWorksClientSetup() {}

    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            LookDevNavigation.registerModule(new WorkbenchPageRegistry.Module(RigWorks.MODID, "RigWorks", 100));
            LookDevNavigation.registerPage(new WorkbenchPageRegistry.Page<>(CHARACTERS, "Characters", 0, () -> new CharacterSelectionScreen(null)));
            LookDevNavigation.registerPage(new WorkbenchPageRegistry.Page<>(RIG_EDITOR, "Rig Editor", 10,
                    () -> new RigWorksWorkbenchExtension().open(LookDevNavigation.modelContext()),
                    RigWorksClientSetup::rigEditorAvailable,
                    () -> "Open a skinned model in AssimpLoader / Edit", false));
            LookDevNavigation.registerPage(new WorkbenchPageRegistry.Page<>(EQUIPMENT, "Equipment", 20,
                    EquipmentTransformScreen::createForNavigation,
                    LocalPlayerCharacter::equipmentEditorAvailable,
                    () -> "Enable a character with hand sockets in RigWorks / Characters", false));
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

    private static boolean rigEditorAvailable() {
        var context = LookDevNavigation.modelContext();
        return context != null && (context.scene().hasSkeleton() || context.session().document().extensions().has("rigworks"));
    }
}
