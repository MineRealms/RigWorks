package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.animation.PoseSnapshot;
import cn.minerealms.rigworks.character.core.CharacterEquipmentDefinition;
import cn.minerealms.rigworks.character.core.CharacterProfile;
import cn.minerealms.rigworks.character.capabilities.YsmLocatorSockets;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Reads inventory and the exact rendered character pose; never changes inventory or gameplay state. */
final class CharacterHeldItemRenderer {
    private static final String[] RIGHT_HAND_SOCKETS = {"right_hand_2", "right_hand_3", "right_hand_4",
            "right_hand_5", "right_hand_6", "right_hand_7", "right_hand_8"};
    private static final String[] LEFT_HAND_SOCKETS = {"left_hand_2", "left_hand_3", "left_hand_4",
            "left_hand_5", "left_hand_6", "left_hand_7", "left_hand_8"};

    private CharacterHeldItemRenderer() {}

    static void render(LocalPlayer player, CharacterEquipmentDefinition equipment, PoseSnapshot renderedPose,
                       PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        render(player,LocalPlayerCharacter.currentProfile(),equipment,renderedPose,poses,buffers,light,overlay);
    }

    static void render(AbstractClientPlayer player, CharacterProfile profile, CharacterEquipmentDefinition equipment, PoseSnapshot renderedPose,
                       PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        if (!equipment.enabled()) return;
        boolean ysm = CharacterItemFrames.isYsm(profile, renderedPose);
        boolean mainGun = TaczHeldItemCompat.isGun(player.getMainHandItem());
        renderHand(player, profile, equipment, false, ysm, mainGun, renderedPose, poses, buffers, light, overlay);
        renderHand(player, profile, equipment, true, ysm, mainGun, renderedPose, poses, buffers, light, overlay);
    }

    private static void renderHand(AbstractClientPlayer player, CharacterProfile profile, CharacterEquipmentDefinition equipment, boolean left,
                                   boolean ysm, boolean mainGun,
                                   PoseSnapshot renderedPose, PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        HumanoidArm arm=left?HumanoidArm.LEFT:HumanoidArm.RIGHT;
        var stack = player instanceof LocalPlayer local ? CharacterWorkbenchPose.item(local,arm)
                : player.getMainArm()==arm?player.getMainHandItem():player.getOffhandItem();
        if (stack.isEmpty()) return;
        boolean offhand = player.getMainArm() != arm;
        boolean gun = TaczHeldItemCompat.isGun(stack);
        if (mainGun && offhand && !gun) return;
        boolean using=player.isUsingItem()&&(player.getMainArm()==arm
                ?player.getUsedItemHand()==net.minecraft.world.InteractionHand.MAIN_HAND:player.getUsedItemHand()==net.minecraft.world.InteractionHand.OFF_HAND);
        boolean spyglass=stack.is(Items.SPYGLASS)&&using&&player.swingTime==0&&profile!=null;
        String context=spyglass?"head":left?"third_person_left_hand":"third_person_right_hand";
        String state=using?"using":stack.getItem() instanceof net.minecraft.world.item.CrossbowItem&&net.minecraft.world.item.CrossbowItem.isCharged(stack)?"charged":"held";
        CharacterEquipmentDefinition.Mount override = null;
        if (!equipment.overrides().isEmpty()) {
            var tags=stack.getTags().map(tag->tag.location().toString()).collect(java.util.stream.Collectors.toSet());
            String item=BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),useAnimation=stack.getUseAnimation().name().toLowerCase(java.util.Locale.ROOT);
            override=equipment.override(left?"left":"right",item,tags,state,useAnimation,context);
        }
        if (gun && offhand && ysm && override == null) {
            renderStoredGun(player, profile, equipment, stack, renderedPose, poses, buffers, light, overlay);
            return;
        }
        if (mainGun && offhand && override == null) return;
        var mount=override!=null?override:equipment.handMount(left);
        if(spyglass) {
            var head=override!=null?override:equipment.socket("spyglass");
            var edited=override==null?cn.minerealms.assimploader.workbench.client.WorkbenchRuntime.socket(renderedPose.getScene(),renderedPose,"spyglass"):null;
            var frame=edited!=null?edited:head!=null?CharacterItemFrames.frame(profile,head,renderedPose,ysm):CharacterAttachmentFrames.frame(profile,renderedPose,"head");
            if(head!=null&&frame==null)return;
            if(frame!=null) {
                if(head==null&&edited==null) frame=clampSpyglassPitch(frame,CharacterAttachmentFrames.of(profile,renderedPose.getScene()).body(renderedPose));
                if (head == null || edited != null) frame.scale((float) (1 / profile.scale()));
                poses.pushPose();
                try {
                    cn.minerealms.rigworks.util.PoseTransforms.multiply(poses,frame);
                    if(head==null&&edited==null) {
                        net.minecraft.client.renderer.entity.layers.CustomHeadLayer.translateToHead(poses,false);
                        poses.translate((left?-2.5f:2.5f)/16,-1f/16,0);
                    }
                    Minecraft.getInstance().gameRenderer.itemInHandRenderer.renderItem(player,stack,ItemDisplayContext.HEAD,false,poses,buffers,light);
                } finally {poses.popPose();}
                return;
            }
        }
        if (mount == null) return;
        renderAtMount(player, profile, mount, stack, left, gun, ysm, renderedPose, poses, buffers, light);
        if (ysm && override == null && (left ? "left_hand" : "right_hand").equals(YsmLocatorSockets.socketFor(mount.node()))) {
            for (String socket : left ? LEFT_HAND_SOCKETS : RIGHT_HAND_SOCKETS) {
                var additional = equipment.socket(socket);
                if (additional != null) renderAtMount(player, profile, additional, stack, left, gun, true,
                        renderedPose, poses, buffers, light);
            }
        }
    }

    private static void renderAtMount(AbstractClientPlayer player, CharacterProfile profile,
                                      CharacterEquipmentDefinition.Mount mount, ItemStack stack, boolean left,
                                      boolean gun, boolean ysm, PoseSnapshot pose, PoseStack poses,
                                      MultiBufferSource buffers, int light) {
        var transform = CharacterItemFrames.frame(profile, mount, pose, ysm);
        if (transform == null) return;
        poses.pushPose();
        try (var ignored = gun ? TaczHeldItemCompat.begin(player, player == Minecraft.getInstance().player)
                : TaczHeldItemCompat.Scope.NONE) {
            cn.minerealms.rigworks.util.PoseTransforms.multiply(poses, transform);
            // TaCZ deliberately skips THIRD_PERSON_LEFT_HAND. Physical handedness is
            // still passed separately, so a left-handed primary gun uses its real renderer.
            Minecraft.getInstance().gameRenderer.itemInHandRenderer.renderItem(player, stack,
                    left && !gun ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    left, poses, buffers, light);
            if(player instanceof LocalPlayer local)EquipmentItemOutline.capture(local, stack, left, poses);
        } finally {
            poses.popPose();
        }
    }

    private static void renderStoredGun(AbstractClientPlayer player, CharacterProfile profile,
                                        CharacterEquipmentDefinition equipment, ItemStack stack, PoseSnapshot pose,
                                        PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        boolean pistol = TaczHeldItemCompat.isPistol(stack);
        var mount = equipment.socket(pistol ? "pistol" : "rifle");
        if (mount == null) return;
        var frame = CharacterItemFrames.frame(profile, mount, pose, true);
        if (frame == null) return;
        poses.pushPose();
        try (var ignored = TaczHeldItemCompat.begin(player, false)) {
            cn.minerealms.rigworks.util.PoseTransforms.multiply(poses, frame);
            if (pistol) poses.translate(0, -.125f, 0);
            poses.scale(.65f, .65f, .65f);
            poses.mulPose(new Quaternionf().rotationY(pistol ? -(float) Math.PI / 2 : -(float) Math.PI));
            if (pistol) poses.mulPose(new Quaternionf().rotationZ((float) Math.PI / 2));
            Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, light, overlay,
                    poses, buffers, player.level(), player.getId());
        } finally { poses.popPose(); }
    }

    static boolean isRenderableMountFrame(org.joml.Matrix4fc frame) {
        return CharacterItemFrames.renderable(frame);
    }

    static org.joml.Matrix4f clampSpyglassPitch(org.joml.Matrix4f head,org.joml.Matrix4f body) {
        if(body==null)return head;
        var local=new org.joml.Matrix4f(body).invert().mul(head);
        var angles=local.getEulerAnglesZYX(new Vector3f());
        angles.x=net.minecraft.util.Mth.clamp(angles.x,-(float)Math.PI/6,(float)Math.PI/2);
        var rotation=body.getUnnormalizedRotation(new Quaternionf()).normalize()
                .mul(new Quaternionf().rotationZYX(angles.z,angles.y,angles.x));
        return new org.joml.Matrix4f().translationRotate(head.getTranslation(new Vector3f()),rotation);
    }
}
