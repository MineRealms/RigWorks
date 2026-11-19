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
import cn.minerealms.assimploader.model.AssimpNode;
import cn.minerealms.rigworks.character.client.LocalPlayerCharacter;
import cn.minerealms.rigworks.character.core.CharacterVisualConfig;
import cn.minerealms.rigworks.character.core.PlayerActionState;
import cn.minerealms.rigworks.character.core.PlayerVisualInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class YsmRuntimeBridge {
    private static YsmModelSource.YsmModelView activeModel;
    private static String profileId;
    private static final List<YsmControllerRuntime> controllers = new ArrayList<>();
    private static YsmBlinkLayer blink;
    private static AnimationState boundAnimation;
    private static long sequence;
    private static int ticks;

    private YsmRuntimeBridge() {}

    public static void installed(YsmAvatarInstaller.Result result) {
        controllers.clear();
        blink = null;
        boundAnimation = null;
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
        if (profileId != null && !profileId.equals(configured)) return;
        AnimationState animation = LocalPlayerCharacter.currentAnimation();
        if (animation == null || animation.isClosed()) return;
        LocalPlayer player = minecraft.player;
        ticks++;

        if (boundAnimation != animation) {
            boundAnimation = animation;
            controllers.clear();
            if (activeModel != null) for (YsmModelSource.YsmControllerView view : activeModel.controllers()) {
                YsmControllerRuntime runtime = new YsmControllerRuntime(YsmControllerRuntime.fromView(view),
                        YsmControllerRuntime.fromAnimationState(animation, "ysm:controller:" + controllers.size()));
                controllers.add(runtime);
            }
            blink = null;
        }

        CharacterVisualConfig visual = LocalPlayerCharacter.currentVisualConfig();
        var item = YsmItemStateBridge.sample(player, visual == null ? null : visual.equipment());
        PlayerVisualInput input = sampleInput(player);
        YsmExpressionRuntime.MapContext context = YsmExpressionRuntime.context(input, input.actionState(), item,
                player.getHealth(), player.getMaxHealth());
        context.set("tick", ticks);

        if (blink == null && animation.getScene() != null) {
            Set<String> nodes = new LinkedHashSet<>();
            collect(animation.getScene().getRootNode(), nodes);
            blink = YsmBlinkLayer.fromAnimations(activeModel.animationNames(), nodes, player.getUUID(),
                    () -> ticks, player::isSleeping);
        }
        if (blink != null) blink.update(animation);
        for (YsmControllerRuntime controller : controllers) controller.update(context);
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

    private static void collect(AssimpNode node, Set<String> names) {
        if (node == null) return;
        names.add(node.getName());
        for (AssimpNode child : node.getChildren()) collect(child, names);
    }

    public static boolean active() { return activeModel != null; }

    public static YsmModelSource.YsmModelView model() { return activeModel; }
}
