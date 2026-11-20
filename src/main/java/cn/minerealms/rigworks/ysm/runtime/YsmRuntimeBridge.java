/*
 * Originally derived from:
 * Source: https://github.com/YesSteveModel/YesSteveModel (Apache-2.0)
 *        and https://github.com/stevewongv/YesSteveModel (Apache-2.0)
 *
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy of the License at:
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Modified by MineRealms for RigWorks.
 * Changes: Cleanroom reimplementation / package adaptation / API changes for in-mod YSM decode.
 */
package cn.minerealms.rigworks.ysm.runtime;

import cn.minerealms.assimploader.animation.AnimationState;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.rigworks.character.client.LocalPlayerCharacter;
import cn.minerealms.rigworks.character.capabilities.BlinkQuery;
import cn.minerealms.rigworks.character.core.CharacterVisualConfig;
import cn.minerealms.rigworks.character.core.PlayerActionState;
import cn.minerealms.rigworks.character.core.PlayerVisualInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

public final class YsmRuntimeBridge {
    private static YsmModelSource.YsmModelView activeModel;
    private static String profileId;
    private static final List<YsmControllerRuntime> controllers = new ArrayList<>();
    private static BlinkQuery closeEyesQuery;
    private static AnimationState boundAnimation;
    private static YsmPoseDriver poseDriver;
    private static YsmDefaultAnimationController defaults;
    private static final Map<AssimpScene, YsmModelSource.YsmModelView> MODELS = new WeakHashMap<>();
    private static final Map<AssimpScene, YsmPoseDriver.Definition> RIGS = new WeakHashMap<>();
    private static long sequence;
    private static int ticks;
    private static final YsmVariableContext variables = new YsmVariableContext();

    private YsmRuntimeBridge() {}

    public static void registerScene(AssimpScene scene, YsmModelSource.YsmModelView view) {
        java.util.Objects.requireNonNull(scene);
        java.util.Objects.requireNonNull(view);
        var definition = new YsmPoseDriver.Definition(view.animations(), view.bones());
        synchronized (YsmRuntimeBridge.class) {
            MODELS.put(scene, view);
            RIGS.put(scene, definition);
        }
    }

    public static synchronized boolean ownsScene(AssimpScene scene) { return MODELS.containsKey(scene); }

    public static synchronized YsmModelSource.YsmModelView modelForScene(AssimpScene scene) { return MODELS.get(scene); }

    private static synchronized YsmPoseDriver driverForScene(AssimpScene scene) {
        var definition = RIGS.get(scene);
        return definition == null ? null : new YsmPoseDriver(definition);
    }

    public static void installed(YsmAvatarInstaller.Result result) {
        detach();
        controllers.clear();
        closeEyesQuery = null;
        boundAnimation = null;
        poseDriver = null;
        if (result == null) {
            activeModel = null;
            profileId = null;
            return;
        }
        activeModel = result.model();
        profileId = result.profileId();
        if (activeModel != null) for (YsmModelSource.YsmControllerView view : activeModel.controllers())
            controllers.add(new YsmControllerRuntime(YsmControllerRuntime.fromView(view), null));
    }

    public static void update(Minecraft minecraft) {
        update(minecraft, (PlayerVisualInput) null);
    }

    public static void update(Minecraft minecraft, LocalPlayerCharacter owner) {
        update(minecraft, (PlayerVisualInput) null);
    }

    public static void update(Minecraft minecraft, PlayerVisualInput observation) {
        if (minecraft == null || minecraft.player == null) { detach(); return; }
        AnimationState animation = LocalPlayerCharacter.currentAnimation();
        if (animation == null || animation.isClosed()) { detach(); return; }
        YsmModelSource.YsmModelView model = modelForScene(animation.getScene());
        if (model == null) { detach(); return; }
        activeModel = model;
        LocalPlayer player = minecraft.player;
        ticks++;

        if (boundAnimation != animation) {
            detach();
            activeModel = model;
            boundAnimation = animation;
            controllers.clear();
            poseDriver = driverForScene(animation.getScene());
            if (poseDriver == null) { detach(); return; }
            animation.addModifier("ysm:pose", poseDriver);
            List<YsmModelSource.YsmControllerView> bodyControllers = bodyControllers(activeModel);
            defaults = new YsmDefaultAnimationController(activeModel.animations(),
                    group -> poseDriver.scoped(group, "default"), "main", defaultOverrides(bodyControllers));
            for (YsmModelSource.YsmControllerView view : bodyControllers) {
                YsmControllerRuntime runtime = new YsmControllerRuntime(YsmControllerRuntime.fromView(view),
                        poseDriver.scoped(view.sourceGroup(), view.name()));
                controllers.add(runtime);
            }
            closeEyesQuery = new BlinkQuery(player.getUUID(), () -> player.tickCount, player::isSleeping);
        }

        CharacterVisualConfig visual = LocalPlayerCharacter.currentVisualConfig();
        var mainItem = YsmItemStateBridge.held(player, player.getMainHandItem(), true);
        var offItem = YsmItemStateBridge.held(player, player.getOffhandItem(), false);
        boolean mainRight = player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
        var item = YsmItemStateBridge.derive(visual == null ? null : visual.equipment(),
                mainRight ? mainItem : offItem, mainRight ? offItem : mainItem, mainRight);
        PlayerVisualInput input = observation == null ? sampleInput(player) : observation;
        YsmExpressionRuntime.MapContext context = YsmExpressionRuntime.context(input, input.actionState(), item,
                player.getHealth(), player.getMaxHealth());
        context.set("tick", ticks);
        double yaw = net.minecraft.util.Mth.wrapDegrees(input.headYawDegrees() - input.bodyYawDegrees());
        context.setQuery("ysm.head_yaw", yaw);
        context.setQuery("ysm.head_pitch", input.headPitchDegrees());
        // These two legacy YSM names intentionally follow its existing X/Y convention.
        context.setQuery("head_x_rotation", yaw);
        context.setQuery("head_y_rotation", input.headPitchDegrees());
        context.setQuery("ground_speed", input.horizontalSpeed());
        context.setQuery("vertical_speed", input.verticalVelocity());
        context.setQuery("yaw_speed", 20d * net.minecraft.util.Mth.wrapDegrees(player.getYRot() - player.yRotO));
        context.setQuery("life_time", ticks / 20d);
        context.setQuery("delta_time", .05);
        context.setQuery("position_delta(0)", player.getDeltaMovement().x);
        context.setQuery("position_delta(1)", player.getDeltaMovement().y);
        context.setQuery("position_delta(2)", player.getDeltaMovement().z);
        context.setQuery("is_on_ground", input.onGround() ? 1 : 0);
        context.setQuery("is_in_water", input.actionState().inWater() ? 1 : 0);
        context.setQuery("ysm.is_close_eyes", closeEyesQuery != null && closeEyesQuery.isCloseEyes() ? 1 : 0);
        YsmAnimationInputProvider.Snapshot optionalInputs = YsmAnimationInputRegistry.sample(player);
        optionalInputs.queries().forEach(context::setQuery);
        context.addTokens(optionalInputs.functions());
        variables.bind(context);
        if (poseDriver != null) poseDriver.setContext(variables);

        if (defaults != null) defaults.update(input, variables, optionalInputs.values(), mainItem, offItem);
        for (YsmControllerRuntime controller : controllers) controller.update(variables);
    }

    private static PlayerVisualInput sampleInput(LocalPlayer player) {
        var motion = player.getDeltaMovement();
        double forward = motion.z;
        double strafe = motion.x;
        boolean onGround = player.onGround();
        int hurtTicks = player.hurtTime;
        String pose = player.isDeadOrDying() ? "dead" : player.isSleeping() ? "sleeping"
                : player.isCrouching() ? "crouching" : player.isSwimming() ? "swimming" : "standing";
        PlayerActionState.AirPhase air = onGround ? PlayerActionState.AirPhase.GROUNDED
                : motion.y > 0.05 ? PlayerActionState.AirPhase.RISING : PlayerActionState.AirPhase.FALLING;
        String useAnimation = player.getUseItem().getUseAnimation().name().toLowerCase(Locale.ROOT);
        PlayerActionState action = new PlayerActionState(pose, air, player.onClimbable(), player.isInWater(),
                player.isUnderWater(), player.isInLava(), player.isAutoSpinAttack(), false, hurtTicks,
                useAnimation, false, false);
        return new PlayerVisualInput(++sequence, System.nanoTime() / 1_000_000_000d, forward, strafe, motion.y,
                player.yBodyRot, player.getYRot(), player.getXRot(), onGround, player.isCrouching(),
                player.isSprinting(), false, !onGround && motion.y < -0.01, player.isSwimming(),
                player.getAbilities().flying, player.isFallFlying(), player.isPassenger(), player.isSleeping(),
                player.isDeadOrDying(), player.isUsingItem(), player.swingTime > 0, null, action);
    }

    public static boolean active() { return activeModel != null; }

    /** Releases local-character state when its binding is retired, disabled, or leaves the world. */
    public static void detach() {
        if (boundAnimation != null && !boundAnimation.isClosed()
                && boundAnimation.getModifier("ysm:pose") != null)
            boundAnimation.removeModifier("ysm:pose");
        if (poseDriver != null) poseDriver.reset();
        if (defaults != null) defaults.reset();
        boundAnimation = null;
        poseDriver = null;
        defaults = null;
        controllers.clear();
        closeEyesQuery = null;
        variables.clear();
        activeModel = null;
        profileId = null;
    }

    public static YsmModelSource.YsmModelView model() { return activeModel; }

    static List<YsmModelSource.YsmControllerView> bodyControllers(YsmModelSource.YsmModelView model) {
        return model.controllers().stream().filter(view -> view.sourceGroup().isBlank()
                        || view.sourceGroup().equals("main") || view.sourceGroup().equals("tac"))
                .sorted(java.util.Comparator.comparingInt(view ->
                        YsmDefaultAnimationController.slotOrder(view.name())))
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
