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

public final class YsmRuntimeBridge {
    private static YsmModelSource.YsmModelView activeModel;
    private static String profileId;
    private static final List<YsmControllerRuntime> controllers = new ArrayList<>();
    private static BlinkQuery closeEyesQuery;
    private static AnimationState boundAnimation;
    private static YsmPoseDriver poseDriver;
    private static long sequence;
    private static int ticks;

    private YsmRuntimeBridge() {}

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
        update(minecraft, null);
    }

    public static void update(Minecraft minecraft, LocalPlayerCharacter owner) {
        if (minecraft == null || minecraft.player == null || activeModel == null) return;
        String configured = LocalPlayerCharacter.settings().profile();
        if (profileId != null && !profileId.equals(configured)) { detach(); return; }
        AnimationState animation = LocalPlayerCharacter.currentAnimation();
        if (animation == null || animation.isClosed()) return;
        LocalPlayer player = minecraft.player;
        ticks++;

        if (boundAnimation != animation) {
            detach();
            boundAnimation = animation;
            controllers.clear();
            poseDriver = new YsmPoseDriver(activeModel.animations(), activeModel.bones());
            animation.addModifier("ysm:pose", poseDriver);
            if (activeModel != null) for (YsmModelSource.YsmControllerView view : activeModel.controllers()) {
                YsmControllerRuntime runtime = new YsmControllerRuntime(YsmControllerRuntime.fromView(view),
                        poseDriver.scoped(view.sourceGroup(), view.name()));
                controllers.add(runtime);
            }
            closeEyesQuery = new BlinkQuery(player.getUUID(), () -> ticks, player::isSleeping);
        }

        CharacterVisualConfig visual = LocalPlayerCharacter.currentVisualConfig();
        var item = YsmItemStateBridge.sample(player, visual == null ? null : visual.equipment());
        PlayerVisualInput input = sampleInput(player);
        YsmExpressionRuntime.MapContext context = YsmExpressionRuntime.context(input, input.actionState(), item,
                player.getHealth(), player.getMaxHealth());
        context.set("tick", ticks);
        context.setQuery("ysm.is_close_eyes", closeEyesQuery != null && closeEyesQuery.isCloseEyes() ? 1 : 0);
        YsmAnimationInputProvider.Snapshot optionalInputs = YsmAnimationInputRegistry.sample(player);
        optionalInputs.queries().forEach(context::setQuery);
        context.addTokens(optionalInputs.functions());
        if (poseDriver != null) poseDriver.setContext(context);

        for (YsmControllerRuntime controller : controllers) controller.update(context);
        animation.invalidateCache();
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

    private static void detach() {
        if (boundAnimation != null && !boundAnimation.isClosed()
                && boundAnimation.getModifier("ysm:pose") != null)
            boundAnimation.removeModifier("ysm:pose");
        if (poseDriver != null) poseDriver.reset();
        boundAnimation = null;
        poseDriver = null;
    }

    public static YsmModelSource.YsmModelView model() { return activeModel; }
}
