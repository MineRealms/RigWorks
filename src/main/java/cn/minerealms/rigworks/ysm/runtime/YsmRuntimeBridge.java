/*
 * Originally derived from YSM's client-side animation semantics (Apache-2.0).
 * Modified by MineRealms for RigWorks.
 */
package cn.minerealms.rigworks.ysm.runtime;

import cn.minerealms.assimploader.animation.AnimationState;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.rigworks.character.client.LocalPlayerCharacter;
import cn.minerealms.rigworks.character.core.PlayerVisualInput;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;

/** Shared source definitions and the legacy local-player facade; mutable state belongs to each binding. */
public final class YsmRuntimeBridge {
    private static final Map<AssimpScene, YsmRuntimeInstance.Definition> DEFINITIONS = new WeakHashMap<>();
    private static final Map<AssimpScene, FirstPersonDefinition> FIRST_PERSON = new WeakHashMap<>();
    private record FirstPersonDefinition(AssimpScene scene, YsmRuntimeInstance.Definition runtime) {}

    private YsmRuntimeBridge() {}

    public static void registerScene(AssimpScene scene, YsmModelSource.YsmModelView view) {
        Objects.requireNonNull(scene);
        Objects.requireNonNull(view);
        synchronized (YsmRuntimeBridge.class) {
            var existing = DEFINITIONS.get(scene);
            if (existing != null && existing.model().equals(view)) return;
        }
        var definition = new YsmRuntimeInstance.Definition(view);
        synchronized (YsmRuntimeBridge.class) { DEFINITIONS.put(scene, definition); }
    }

    public static synchronized boolean ownsScene(AssimpScene scene) { return DEFINITIONS.containsKey(scene); }

    public static void registerFirstPerson(AssimpScene main, AssimpScene arms, YsmModelSource.YsmModelView view) {
        var filtered = new YsmModelSource.YsmModelView(view.animationNames(), firstPersonControllers(view),
                view.animations().stream().filter(clip -> clip.category().equals("fp_arm")).toList(), view.bones());
        var definition = new FirstPersonDefinition(arms, new YsmRuntimeInstance.Definition(filtered, true));
        synchronized (YsmRuntimeBridge.class) { FIRST_PERSON.put(main, definition); }
    }

    public static synchronized YsmModelSource.YsmModelView modelForScene(AssimpScene scene) {
        var definition = DEFINITIONS.get(scene);
        return definition == null ? null : definition.model();
    }

    public static YsmRuntimeInstance create(AnimationState animation, UUID owner) {
        if (animation == null || animation.isClosed()) throw new IllegalArgumentException("A live animation is required");
        Objects.requireNonNull(owner, "YSM animation owner");
        YsmRuntimeInstance.Definition definition;
        synchronized (YsmRuntimeBridge.class) { definition = DEFINITIONS.get(animation.getScene()); }
        if (definition == null) return null;
        var runtime = new YsmRuntimeInstance(definition, owner);
        runtime.attach(animation);
        var minecraft = Minecraft.getInstance();
        var local = minecraft == null ? null : minecraft.player;
        if (local != null && local.getUUID().equals(owner)) {
            FirstPersonDefinition arms;
            synchronized (YsmRuntimeBridge.class) { arms = FIRST_PERSON.get(animation.getScene()); }
            if (arms != null) runtime.attachFirstPerson(arms.scene(), arms.runtime());
        }
        return runtime;
    }

    /** Import publication belongs to the profile transaction; do not retire its valid previous avatar here. */
    public static void installed(YsmAvatarInstaller.Result result) {
        if (result == null) detach();
    }

    public static void update(Minecraft minecraft) { update(minecraft, (PlayerVisualInput) null); }

    public static void update(Minecraft minecraft, LocalPlayerCharacter owner) { update(minecraft); }

    public static void update(Minecraft minecraft, PlayerVisualInput observation) {
        if (minecraft != null && minecraft.player != null) LocalPlayerCharacter.updateCurrentYsm(observation);
    }

    public static boolean active() {
        var runtime = LocalPlayerCharacter.currentYsmRuntime();
        return runtime != null && runtime.active();
    }

    public static void detach() {
        var runtime = LocalPlayerCharacter.currentYsmRuntime();
        if (runtime != null) runtime.close();
    }

    public static YsmModelSource.YsmModelView model() {
        var runtime = LocalPlayerCharacter.currentYsmRuntime();
        return runtime == null || runtime.isClosed() ? null : runtime.model();
    }

    static List<YsmModelSource.YsmControllerView> thirdPersonControllers(YsmModelSource.YsmModelView model) {
        return model.controllers().stream().filter(view -> view.sourceGroup().isBlank()
                        || view.sourceGroup().equals("main") || view.sourceGroup().equals("arm")
                        || view.sourceGroup().equals("tac"))
                .sorted(java.util.Comparator.comparingInt(view -> YsmDefaultAnimationController.slotOrder(view.name())))
                .toList();
    }

    static List<YsmModelSource.YsmControllerView> firstPersonControllers(YsmModelSource.YsmModelView model) {
        return model.controllers().stream().filter(view -> view.sourceGroup().equals("fp_arm"))
                .sorted(java.util.Comparator.comparingInt(view -> YsmDefaultAnimationController.slotOrder(view.name())))
                .toList();
    }

    static java.util.Set<String> defaultOverrides(List<YsmModelSource.YsmControllerView> controllers) {
        var slots = new java.util.HashSet<String>();
        for (var view : controllers) {
            String slot = YsmDefaultAnimationController.controllerSlot(view.name());
            if (view.sourceGroup().isBlank()) slots.add(slot);
            else slots.add(view.sourceGroup() + "/" + slot);
        }
        return java.util.Set.copyOf(slots);
    }
}
