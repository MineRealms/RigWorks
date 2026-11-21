package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.animation.PoseSnapshot;
import cn.minerealms.rigworks.character.core.CharacterEquipmentDefinition;
import cn.minerealms.rigworks.character.core.CharacterProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Reads inventory and the exact rendered character pose; never changes inventory or gameplay state. */
final class CharacterHeldItemRenderer {
    private CharacterHeldItemRenderer() {}

    static void render(LocalPlayer player, CharacterEquipmentDefinition equipment, PoseSnapshot renderedPose,
                       PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        render(player,LocalPlayerCharacter.currentProfile(),equipment,renderedPose,poses,buffers,light,overlay);
    }

    static void render(AbstractClientPlayer player, CharacterProfile profile, CharacterEquipmentDefinition equipment, PoseSnapshot renderedPose,
                       PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        if (!equipment.enabled()) return;
        renderHand(player, profile, equipment, false, renderedPose, poses, buffers, light, overlay);
        renderHand(player, profile, equipment, true, renderedPose, poses, buffers, light, overlay);
    }

    private static void renderHand(AbstractClientPlayer player, CharacterProfile profile, CharacterEquipmentDefinition equipment, boolean left,
                                   PoseSnapshot renderedPose, PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        HumanoidArm arm=left?HumanoidArm.LEFT:HumanoidArm.RIGHT;
        var stack = player instanceof LocalPlayer local ? CharacterWorkbenchPose.item(local,arm)
                : player.getMainArm()==arm?player.getMainHandItem():player.getOffhandItem();
        if (stack.isEmpty()) return;
        boolean using=player.isUsingItem()&&(player.getMainArm()==arm
                ?player.getUsedItemHand()==net.minecraft.world.InteractionHand.MAIN_HAND:player.getUsedItemHand()==net.minecraft.world.InteractionHand.OFF_HAND);
        boolean spyglass=stack.is(Items.SPYGLASS)&&using&&player.swingTime==0&&profile!=null;
        String context=spyglass?"head":left?"third_person_left_hand":"third_person_right_hand";
        String state=using?"using":stack.getItem() instanceof net.minecraft.world.item.CrossbowItem&&net.minecraft.world.item.CrossbowItem.isCharged(stack)?"charged":"held";
        var tags=stack.getTags().map(tag->tag.location().toString()).collect(java.util.stream.Collectors.toSet());
        String item=BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),useAnimation=stack.getUseAnimation().name().toLowerCase(java.util.Locale.ROOT);
        var override=equipment.override(left?"left":"right",item,tags,state,useAnimation,context);
        var mount=override!=null?override:equipment.handMount(left);
        if(spyglass) {
            var head=override!=null?override:equipment.socket("spyglass");
            var edited=override==null?cn.minerealms.assimploader.workbench.client.WorkbenchRuntime.socket(renderedPose.getScene(),renderedPose,"spyglass"):null;
            var frame=edited!=null?edited:head!=null?head.renderTransform(renderedPose):CharacterAttachmentFrames.frame(profile,renderedPose,"head");
            if(head!=null&&frame==null)return;
            if(frame!=null) {
                if(head==null&&edited==null) frame=clampSpyglassPitch(frame,CharacterAttachmentFrames.of(profile,renderedPose.getScene()).body(renderedPose));
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
        var mountFrame = renderedPose.getNodeTransform(mount.node());
        if (!isRenderableMountFrame(mountFrame)) return;
        var transform = mount.transform(mountFrame);
        Vector3f position = transform.getTranslation(new Vector3f());
        poses.pushPose();
        try {
            poses.translate(position.x, position.y, position.z);
            poses.mulPose(transform.getUnnormalizedRotation(new Quaternionf()).normalize());
            poses.scale(mount.scale(), mount.scale(), mount.scale());
            Minecraft.getInstance().gameRenderer.itemInHandRenderer.renderItem(player, stack,
                    left ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    left, poses, buffers, light);
            if(player instanceof LocalPlayer local)EquipmentItemOutline.capture(local, stack, left, poses);
        } finally {
            poses.popPose();
        }
    }

    static boolean isRenderableMountFrame(org.joml.Matrix4fc frame) {
        if (frame == null || !frame.isFinite() || frame.determinant3x3() <= 1e-12f) return false;
        var scale = frame.getScale(new org.joml.Vector3f());
        return Math.min(scale.x, Math.min(scale.y, scale.z)) >= 1e-6f;
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
