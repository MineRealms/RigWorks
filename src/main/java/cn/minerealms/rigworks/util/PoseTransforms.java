package cn.minerealms.rigworks.util;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public final class PoseTransforms {
    private PoseTransforms() {}

    public static void multiply(PoseStack poses, Matrix4f transform) {
        //? if forge {
        poses.mulPoseMatrix(transform);
        poses.last().normal().mul(new Matrix3f(transform).invert().transpose());
        //?}
        //? if neoforge {
        /*
        poses.mulPose(transform);
        *///?}
    }
}
