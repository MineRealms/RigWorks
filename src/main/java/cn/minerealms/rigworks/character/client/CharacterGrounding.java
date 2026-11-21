package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.render.ModelWorldBounds;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4fc;

/** Visual-only grounding correction for animated geometry; it never changes entity position or collision. */
final class CharacterGrounding {
    private CharacterGrounding() {}

    static float verticalLift(AABB localPoseBounds, Matrix4fc modelTransform) {
        return verticalLift(localPoseBounds, modelTransform, 0);
    }

    static float verticalLift(AABB localPoseBounds, Matrix4fc modelTransform, double renderOffsetY) {
        if (localPoseBounds == null) return 0;
        var transformed = ModelWorldBounds.transform(localPoseBounds, modelTransform, 0, 0, 0);
        return (float) Math.max(0, -transformed.minY - renderOffsetY);
    }
}
