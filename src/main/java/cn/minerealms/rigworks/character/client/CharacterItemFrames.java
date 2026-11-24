package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.animation.PoseSnapshot;
import cn.minerealms.rigworks.character.capabilities.YsmLocatorSockets;
import cn.minerealms.rigworks.character.core.CharacterEquipmentDefinition;
import cn.minerealms.rigworks.character.core.CharacterProfile;
import cn.minerealms.rigworks.ysm.runtime.YsmRuntimeBridge;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Native item metres are independent of the character's imported model units. */
final class CharacterItemFrames {
    private static final float HALF_PI = (float) Math.PI / 2;
    private CharacterItemFrames() {}

    /** YSM's native T(0,-1/16,-.1)*Rx(-90) expressed in our Z-reflected model basis. */
    static CharacterEquipmentDefinition.Mount ysmHandMount(String node) {
        var rotation = new Quaternionf().rotationX(HALF_PI);
        return new CharacterEquipmentDefinition.Mount(node, new CharacterEquipmentDefinition.Position(0, -.0625f, .1f),
                new CharacterProfile.Rotation(rotation.x, rotation.y, rotation.z, rotation.w), 1);
    }

    static boolean isYsm(CharacterProfile profile, PoseSnapshot pose) {
        return (profile != null && profile.model().endsWith(".rwym")) || YsmRuntimeBridge.ownsScene(pose.getScene());
    }

    static Matrix4f frame(CharacterProfile profile, CharacterEquipmentDefinition.Mount mount, PoseSnapshot pose,
                          boolean ysm) {
        return frame(mount, pose.getNodeTransform(mount.node()), profile == null ? 1 : profile.scale(), ysm);
    }

    static Matrix4f frame(CharacterEquipmentDefinition.Mount mount, Matrix4fc bone, double modelScale, boolean ysm) {
        if (!renderable(bone)) return null;
        if (!Double.isFinite(modelScale) || modelScale <= 0)
            throw new IllegalArgumentException("Equipment requires a positive finite model unit scale");
        var result = new Matrix4f().translationRotate(bone.getTranslation(new Vector3f()),
                bone.getUnnormalizedRotation(new Quaternionf()).normalize()).scale((float) (1 / modelScale));
        // Canonical YSM caches have unit bind scale; locator scale is authored animation,
        // including fades/replacements, rather than the FBX's centimetre conversion.
        if (ysm) result.scale(bone.getScale(new Vector3f()));
        if (ysm && legacyHandMount(mount)) {
            result.translate(0, -.0625f, .1f).rotateX(HALF_PI);
        }
        if (ysm && unconvertedYsmHandMount(mount)) return result.translate(0, -.0625f, .1f).rotateX(HALF_PI);
        return result.translate(mount.position().x(), mount.position().y(), mount.position().z())
                .rotate(mount.rotation().quaternion()).scale(mount.scale());
    }

    static boolean renderable(Matrix4fc frame) {
        if (frame == null || !frame.isFinite() || frame.determinant3x3() <= 1e-12f) return false;
        var scale = frame.getScale(new Vector3f());
        return Math.min(scale.x, Math.min(scale.y, scale.z)) >= 1e-6f;
    }

    /** Older imported profiles used an uncorrected identity hand mount. */
    private static boolean legacyHandMount(CharacterEquipmentDefinition.Mount mount) {
        if (!ysmHandLocator(mount.node())) return false;
        var position = mount.position();
        var rotation = mount.rotation();
        return position.x() == 0 && position.y() == 0 && position.z() == 0
                && rotation.x() == 0 && rotation.y() == 0 && rotation.z() == 0 && Math.abs(rotation.w()) == 1;
    }

    /** Migrate the automatically authored native-basis calibration without changing custom mounts. */
    private static boolean unconvertedYsmHandMount(CharacterEquipmentDefinition.Mount mount) {
        if (!ysmHandLocator(mount.node()) || mount.scale() != 1) return false;
        var position = mount.position();
        return position.x() == 0 && position.y() == -.0625f && position.z() == -.1f
                && Math.abs(mount.rotation().quaternion().dot(new Quaternionf().rotationX(-HALF_PI))) > 1 - 1e-6f;
    }

    private static boolean ysmHandLocator(String node) {
        String socket = YsmLocatorSockets.socketFor(node);
        return socket != null && (socket.equals("right_hand") || socket.equals("left_hand")
                || socket.startsWith("right_hand_") || socket.startsWith("left_hand_"));
    }
}
