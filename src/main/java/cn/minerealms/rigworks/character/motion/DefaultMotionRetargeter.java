package cn.minerealms.rigworks.character.motion;

import cn.minerealms.assimploader.animation.PoseGraph;
import cn.minerealms.assimploader.animation.SkinBindPose;
import cn.minerealms.assimploader.animation.ik.TwoBonePoseConstraint;
import cn.minerealms.assimploader.model.AssimpNode;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.rigworks.character.core.CharacterProfile;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-avatar calibration; the source poses and target nodes are never modified. */
final class DefaultMotionRetargeter {
    private record Bone(String role, String node, int source, Matrix4f reference, Matrix3f correction, int depth) {}
    private record Limb(String side, String root, String middle, String tip, float scale,
                        float near, float far, Vector3f neutralOffset, TwoBonePoseConstraint constraint) {}
    private static final Map<String, String> CHILD = Map.ofEntries(
            Map.entry("spine", "chest"), Map.entry("chest", "neck"), Map.entry("neck", "head"),
            Map.entry("left_shoulder", "left_upper_arm"), Map.entry("right_shoulder", "right_upper_arm"),
            Map.entry("left_upper_arm", "left_forearm"), Map.entry("right_upper_arm", "right_forearm"),
            Map.entry("left_forearm", "left_hand"), Map.entry("right_forearm", "right_hand"),
            Map.entry("left_thigh", "left_shin"), Map.entry("right_thigh", "right_shin"),
            Map.entry("left_shin", "left_foot"), Map.entry("right_shin", "right_foot"));
    private final AssimpScene scene;
    private final DefaultMotionLibrary library;
    private final DefaultMotionLibrary.Pose bind;
    private final Map<String, Matrix4f> referenceLocals;
    private final Map<String, Bone> bones = new LinkedHashMap<>();
    private final List<Bone> ordered;
    private final List<Limb> legs = new ArrayList<>(), arms = new ArrayList<>();
    private final Matrix3f basis, inverseBasis;
    private final float bodyScale;
    private float maxLegError, maxHandError;

    DefaultMotionRetargeter(CharacterProfile profile, AssimpScene scene, DefaultMotionLibrary library) {
        this.scene = scene;
        this.library = library;
        bind = library.bind();
        referenceLocals = new LinkedHashMap<>(SkinBindPose.locals(scene));
        var corrections = new LinkedHashMap<String, CharacterProfile.Rotation>();
        profile.humanoid().values().forEach(bone -> {
            var existing = corrections.putIfAbsent(bone.node(), bone.nativeRotation());
            if (existing != null) {
                if (Math.abs(existing.quaternion().dot(bone.nativeRotation().quaternion())) < 1 - 1e-6f)
                    throw new IllegalArgumentException("Conflicting DEFAULT native calibrations for " + bone.node());
                return;
            }
            Matrix4f local = referenceLocals.get(bone.node());
            if (local != null) referenceLocals.put(bone.node(), calibratedLocal(local, bone.nativeRotation()));
        });
        var reference = new PoseGraph(scene.getRootNode(), referenceLocals);
        var depths = new LinkedHashMap<String, Integer>();
        collect(scene.getRootNode(), 0, depths);
        for (String role : library.roles()) {
            var bone = profile.bone(role);
            if (bone != null && reference.contains(bone.node()))
                bones.put(role, new Bone(role, bone.node(), library.roles().indexOf(role), reference.global(bone.node()),
                        new Matrix3f(), depths.get(bone.node())));
        }
        if (!bones.containsKey("hips") || !bones.containsKey("head")
                || !bones.containsKey("left_upper_arm") || !bones.containsKey("right_upper_arm"))
            throw new IllegalArgumentException("DEFAULT requires humanoid hips, head and both upper arms; calibrate the profile first");
        Vector3f up = reference.position(node("head")).sub(reference.position(node("hips"))).normalize();
        Vector3f across = reference.position(node("left_upper_arm")).sub(reference.position(node("right_upper_arm")));
        across.fma(-across.dot(up), up).normalize();
        Vector3f forward = reference.global(node("head")).transformDirection(
                profile.bone("head").referenceRotation().quaternion().transform(profile.headLook().forward().vector()));
        forward.fma(-forward.dot(up), up).fma(-forward.dot(across), across).normalize();
        if (!up.isFinite() || !across.isFinite() || !forward.isFinite())
            throw new IllegalArgumentException("DEFAULT humanoid axes are degenerate; calibrate the head forward and shoulders");
        basis = new Matrix3f().setColumn(0, across).setColumn(1, up).setColumn(2, forward);
        inverseBasis = new Matrix3f(basis).invert();
        for (Bone bone : bones.values()) {
            Matrix3f calibrated = new Matrix3f(bone.reference);
            String childRole = CHILD.get(bone.role);
            Vector3f sourceDirection = null, targetDirection = null;
            if (childRole != null && bones.containsKey(childRole)) {
                sourceDirection = source(bind, childRole).sub(source(bind, bone.role));
                targetDirection = reference.position(node(childRole)).sub(reference.position(bone.node));
            } else if (bone.role.endsWith("_foot")) {
                AssimpNode toe = findToe(scene.findNodeByName(bone.node));
                if (toe != null) {
                    sourceDirection = source(bind, bone.role.replace("_foot", "_toe")).sub(source(bind, bone.role));
                    targetDirection = reference.position(toe.getName()).sub(reference.position(bone.node));
                    // Shoe heading is calibrated on the sole plane; pitch and signed scale stay authored.
                    sourceDirection.y = 0;
                    Vector3f sourceWorld = basis.transform(sourceDirection);
                    sourceWorld.fma(-sourceWorld.dot(up), up);
                    targetDirection.fma(-targetDirection.dot(up), up);
                    if (valid(sourceWorld) && valid(targetDirection))
                        calibrated = new Matrix3f().rotation(new Quaternionf().rotationTo(
                                targetDirection.normalize(), sourceWorld.normalize())).mul(calibrated);
                    sourceDirection = null;
                }
            }
            if (sourceDirection != null && valid(sourceDirection) && valid(targetDirection)) {
                basis.transform(sourceDirection).normalize();
                calibrated = new Matrix3f().rotation(new Quaternionf().rotationTo(
                        targetDirection.normalize(), sourceDirection)).mul(calibrated);
            }
            Matrix3f sourceReference = mappedRotation(bind, bone.source);
            bone.correction.set(sourceReference.invert().mul(calibrated));
        }
        ordered = bones.values().stream().sorted(Comparator.comparingInt(Bone::depth)).toList();
        float targetHeight = reference.position(node("head")).distance(reference.position(node("hips")));
        float sourceHeight = source(bind, "head").distance(source(bind, "hips"));
        for (String side : List.of("left", "right")) {
            addLimb(legs, reference, side, "_thigh", "_shin", "_foot");
            addLimb(arms, reference, side, "_upper_arm", "_forearm", "_hand");
        }
        bodyScale = legs.isEmpty() ? targetHeight / sourceHeight
                : (float)legs.stream().mapToDouble(Limb::scale).average().orElseThrow();
    }

    private void addLimb(List<Limb> list, PoseGraph graph, String side, String a, String b, String c) {
        if (!bones.containsKey(side + a) || !bones.containsKey(side + b) || !bones.containsKey(side + c)) return;
        String root = node(side + a), middle = node(side + b), tip = node(side + c);
        if (!graph.isAncestor(root, middle) || !graph.isAncestor(middle, tip)) return;
        float upper = graph.position(root).distance(graph.position(middle));
        float lower = graph.position(middle).distance(graph.position(tip));
        float targetLength = upper + lower;
        float sourceLength = source(bind, side + a).distance(source(bind, side + b))
                + source(bind, side + b).distance(source(bind, side + c));
        if (Math.min(targetLength, sourceLength) < 1e-6f) return;
        float scale = targetLength / sourceLength;
        Vector3f offset = graph.position(tip).sub(graph.position(node("hips")))
                .sub(basis.transform(source(bind, side + c).sub(source(bind, "hips"))).mul(scale));
        list.add(new Limb(side, root, middle, tip, scale, Math.abs(upper - lower) + 1e-6f, targetLength - 1e-6f, offset,
                new TwoBonePoseConstraint(root, middle, tip, 0, (float)Math.PI - .001f)));
    }

    void apply(Map<String, Matrix4f> locals, DefaultMotionLibrary.Pose base,
               DefaultMotionLibrary.Pose left, DefaultMotionLibrary.Pose right,
               boolean ownLeft, boolean ownRight, boolean pairedHands, boolean upperBody, boolean suppressVertical) {
        // Use the actual skin bind pose for helper nodes, including exported root scale/axis transforms.
        referenceLocals.forEach((name, value) -> locals.put(name, new Matrix4f(value)));
        var graph = new PoseGraph(scene.getRootNode(), locals);
        for (Bone bone : ordered) {
            if (bone.role.equals("head")) continue;
            boolean leftArm = arm(bone.role, "left"), rightArm = arm(bone.role, "right");
            if (leftArm && !ownLeft || rightArm && !ownRight) continue;
            boolean torso = bone.role.equals("spine") || bone.role.equals("chest") || bone.role.equals("neck");
            var sample = leftArm && left != null ? left : rightArm && right != null ? right
                    : torso && (pairedHands || upperBody) && left != null ? left : base;
            Matrix4f desired = new Matrix4f(mappedRotation(sample, bone.source).mul(bone.correction))
                    .setTranslation(graph.position(bone.node));
            if (bone.role.equals("hips")) {
                float vertical = suppressVertical ? 0 : source(sample, "hips").y - source(bind, "hips").y;
                desired.setTranslation(new Vector3f(bone.reference.getTranslation(new Vector3f()))
                        .add(basis.transform(new Vector3f(0, vertical * bodyScale, 0))));
            }
            graph.setGlobal(bone.node, desired);
        }
        maxLegError = maxHandError = 0;
        var goals = new Vector3f[legs.size()];
        var roots = new Vector3f[legs.size()];
        for (int i = 0; i < legs.size(); i++) {
            Limb leg = legs.get(i);
            goals[i] = graph.position(node("hips"))
                    .add(basis.transform(source(base, leg.side + "_foot").sub(source(base, "hips"))).mul(leg.scale))
                    .add(leg.neutralOffset);
            roots[i] = graph.position(leg.root);
        }
        Vector3f pelvis = fitTogether(legs, goals, roots).negate();
        if (pelvis.lengthSquared() > 1e-12f) {
            Matrix4f hip = graph.global(node("hips"));
            hip.setTranslation(hip.getTranslation(new Vector3f()).add(pelvis));
            graph.setGlobal(node("hips"), hip);
        }
        for (int i = 0; i < legs.size(); i++) {
            Limb leg = legs.get(i);
            Vector3f direction = source(base, leg.side + "_foot").sub(source(base, leg.side + "_thigh")).normalize();
            Vector3f bend = source(base, leg.side + "_shin").sub(source(base, leg.side + "_thigh"));
            bend.fma(-bend.dot(direction), direction);
            if (!valid(bend)) bend.set(0, 0, 1);
            Vector3f pole = graph.position(leg.root).add(basis.transform(bend.normalize()));
            maxLegError = Math.max(maxLegError, solve(graph, leg, goals[i], pole));
        }
        if (pairedHands && left != null && arms.size() == 2 && bones.containsKey("chest")) {
            float scale = (arms.get(0).scale + arms.get(1).scale) * .5f;
            Vector3f chest = graph.position(node("chest"));
            Vector3f sourceChest = source(left, "chest");
            goals = new Vector3f[arms.size()];
            roots = new Vector3f[arms.size()];
            for (int i = 0; i < arms.size(); i++) {
                Limb arm = arms.get(i);
                goals[i] = new Vector3f(chest).add(basis.transform(source(left, arm.side + "_hand").sub(sourceChest)).mul(scale));
                roots[i] = graph.position(arm.root);
            }
            // Translate both grip targets together; independent clamping would change their separation.
            Vector3f adjustment = fitTogether(arms, goals, roots);
            for (int i = 0; i < arms.size(); i++) {
                Limb arm = arms.get(i);
                Vector3f pole = new Vector3f(chest).add(basis.transform(source(left, arm.side + "_forearm").sub(sourceChest)).mul(scale))
                        .add(adjustment);
                maxHandError = Math.max(maxHandError, solve(graph, arm, goals[i].add(adjustment), pole));
            }
        }
    }

    private static Vector3f fitTogether(List<Limb> limbs, Vector3f[] goals, Vector3f[] roots) {
        Vector3f adjustment = new Vector3f(), delta = new Vector3f();
        for (int pass = 0; pass < 16; pass++) {
            float worst = 0;
            for (int i = 0; i < limbs.size(); i++) {
                Limb limb = limbs.get(i);
                delta.set(goals[i]).add(adjustment).sub(roots[i]);
                float distance = delta.length();
                float change = distance > limb.far ? limb.far - distance
                        : distance > 1e-7f && distance < limb.near ? limb.near - distance : 0;
                if (change != 0) adjustment.fma(change / distance, delta);
                worst = Math.max(worst, Math.abs(change));
            }
            if (worst < 1e-6f) break;
        }
        return adjustment;
    }

    private static Matrix4f calibratedLocal(Matrix4f local, CharacterProfile.Rotation correction) {
        if (correction.equals(CharacterProfile.Rotation.IDENTITY)) return new Matrix4f(local);
        Vector3f scale = local.getScale(new Vector3f());
        if (Math.min(scale.x, Math.min(scale.y, scale.z)) < 1e-8f)
            throw new IllegalArgumentException("DEFAULT calibration requires a nonsingular bind scale");
        Matrix3f rotation = new Matrix3f(local);
        if (rotation.determinant() < 0) scale.x = -scale.x;
        rotation.scale(1 / scale.x, 1 / scale.y, 1 / scale.z);
        Quaternionf orientation = rotation.getNormalizedRotation(new Quaternionf()).normalize().mul(correction.quaternion());
        return new Matrix4f().translationRotateScale(local.getTranslation(new Vector3f()), orientation, scale);
    }

    private static float solve(PoseGraph graph, Limb limb, Vector3f goal, Vector3f pole) {
        Matrix3f orientation = new Matrix3f(graph.global(limb.tip));
        var result = limb.constraint.solve(graph, goal, pole, 1);
        graph.setGlobal(limb.tip, new Matrix4f(orientation).setTranslation(graph.position(limb.tip)));
        return result.error();
    }

    private Matrix3f mappedRotation(DefaultMotionLibrary.Pose pose, int index) {
        return new Matrix3f(basis).mul(new Matrix3f().rotation(pose.rotation(index, new Quaternionf()))).mul(inverseBasis);
    }
    private Vector3f source(DefaultMotionLibrary.Pose pose, String role) {
        int index = library.roles().indexOf(role);
        return index < 0 ? new Vector3f() : pose.position(index, new Vector3f());
    }
    private String node(String role) { return bones.get(role).node; }
    private static boolean valid(Vector3f vector) { return vector != null && vector.isFinite() && vector.lengthSquared() > 1e-10f; }
    static boolean arm(String role, String side) {
        return role.startsWith(side + "_") && (role.endsWith("shoulder") || role.endsWith("upper_arm")
                || role.endsWith("forearm") || role.endsWith("hand"));
    }
    private static AssimpNode findToe(AssimpNode node) {
        if (node == null) return null;
        for (var child : node.getChildren()) {
            if (child.getName().toLowerCase(java.util.Locale.ROOT).contains("toe")) return child;
            AssimpNode found = findToe(child);
            if (found != null) return found;
        }
        return null;
    }
    private static void collect(AssimpNode node, int depth, Map<String, Integer> depths) {
        if (node == null) return;
        depths.put(node.getName(), depth);
        for (var child : node.getChildren()) collect(child, depth + 1, depths);
    }
    float maxLegError() { return maxLegError; }
    float maxHandError() { return maxHandError; }
}
