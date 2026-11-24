package cn.minerealms.rigworks.character.motion;

import cn.minerealms.assimploader.animation.AnimationState;
import cn.minerealms.assimploader.animation.PoseModifier;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.rigworks.character.core.CharacterProfile;
import cn.minerealms.rigworks.character.core.PlayerVisualInput;
import cn.minerealms.rigworks.character.core.VanillaPoseSelection;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Built-in solved motions, sampled and retargeted entirely in Java. Minecraft remains the action clock. */
public final class DefaultMotionController implements PoseModifier {
    public static final String MODIFIER = "character:default-motion";
    private static final Set<String> ITEM_TYPES = Set.of("empty", "item", "sword", "tool", "bow", "crossbow", "shield");
    public record Items(String left, String right, String activeArm, float useProgress,
                        boolean leftCharged, boolean rightCharged) {
        public static final Items EMPTY = new Items("empty", "empty", "", 0, false, false);
        public Items {
            if (!ITEM_TYPES.contains(left) || !ITEM_TYPES.contains(right)
                    || !Set.of("", "left", "right").contains(activeArm)
                    || !Float.isFinite(useProgress) || useProgress < 0 || useProgress > 1)
                throw new IllegalArgumentException("Invalid DEFAULT physical-hand observation");
        }
    }
    public record Selection(String base, String left, String right, double baseTime, float swingPhase,
                            boolean nativeFallback, String diagnostic) {}
    private final CharacterProfile profile;
    private final AnimationState animation;
    private DefaultMotionLibrary library;
    private DefaultMotionRetargeter retargeter;
    private DefaultMotionLibrary.Pose base, sampled, transition, left, right, first, mirrored;
    private String baseId = "", leftId = "", rightId = "", unsupported = "";
    private double baseTime, transitionTime, lastTime = -1, swingStarted;
    private long lastSequence = -1, swingGeneration = -1;
    private boolean active, enabled, ownsLeft, ownsRight, paired, upperBody, suppressVertical, closed, wasSwinging;
    private float phase;
    private Selection selection = new Selection("", "", "", 0, 0, true, "inactive");

    /** Construct before the vanilla pose driver so native head/item/root fallback composes afterward. */
    public DefaultMotionController(CharacterProfile profile, AnimationState animation) {
        this.profile = Objects.requireNonNull(profile);
        this.animation = Objects.requireNonNull(animation);
        animation.initialize();
        if (animation.getModifier(MODIFIER) != null) throw new IllegalArgumentException("DEFAULT motion already installed");
        animation.addModifier(MODIFIER, this);
    }

    public VanillaPoseSelection update(PlayerVisualInput input) { return update(input, Items.EMPTY); }

    public VanillaPoseSelection update(PlayerVisualInput input, Items items) {
        if (closed || animation.isClosed()) throw new IllegalStateException("DEFAULT controller is closed");
        Objects.requireNonNull(input); Objects.requireNonNull(items);
        if (!active) {
            animation.stop();
            animation.resume();
            active = true;
        }
        if (input.sequence() < lastSequence || input.timeSeconds() < lastTime)
            return fallback("stale observation");
        boolean firstObservation = lastTime < 0;
        double delta = firstObservation ? 0 : Math.min(.25, input.timeSeconds() - lastTime);
        lastSequence = input.sequence(); lastTime = input.timeSeconds();
        var state = input.actionState();
        if (input.dead() || input.sleeping() || input.riding() || input.elytra() || input.flying()
                || state.climbing() || state.riptide() || state.frozen() || state.inLava())
            return fallback("native special state");
        if (!initializeLibrary()) return fallback(unsupported);
        String desired = input.swimming() || input.crawling() ? "swimming" : state.inWater() ? "swim"
                : !input.onGround() ? "jump" : input.crouching()
                ? input.horizontalSpeed() > profile.movementThreshold() ? "crouch_walk" : "crouch"
                : input.horizontalSpeed() > profile.movementThreshold() ? input.sprinting() ? "run" : "walk" : "stand";
        if (!desired.equals(baseId)) {
            cn.minerealms.rigworks.debug.RigWorksDiagnostics.debug("DEFAULT {} base {} -> {}", profile.model(), baseId, desired);
            transition.set(base);
            baseId = desired; baseTime = transitionTime = 0;
        }
        var clip = library.clip(baseId);
        double cadence = switch (baseId) {
            case "walk" -> input.horizontalSpeed() * clip.duration() / profile.timing().walkStride();
            case "run" -> input.horizontalSpeed() * clip.duration() / profile.timing().runStride();
            case "crouch_walk" -> input.horizontalSpeed() * clip.duration() / profile.timing().crouchStride();
            default -> 1;
        };
        if (profile.timing().movementSync()) cadence = Math.max(profile.timing().minRate(), Math.min(profile.timing().maxRate(), cadence));
        else cadence = 1;
        baseTime += delta * cadence;
        double sampleTime = baseTime;
        if ((baseId.equals("walk") || baseId.equals("run")) && input.forwardVelocity() < -.01 && clip.duration() > 0)
            sampleTime = clip.duration() - baseTime % clip.duration();
        sample(clip, sampleTime, sampled);
        transitionTime += delta;
        float blend = profile.blendSeconds() == 0 || firstObservation ? 1
                : (float)Math.min(1, transitionTime / profile.blendSeconds());
        base.blend(transition, sampled, blend);

        ownsLeft = ownsRight = true;
        leftId = hold(items.left(), "left");
        rightId = hold(items.right(), "right");
        paired = upperBody = false;
        boolean mirrorPair = false, mirrorLeft = false;
        double actionTime = input.timeSeconds();
        String use = state.useAnimation();
        if (input.usingItem() && (use.equals("bow") || use.equals("crossbow"))) {
            leftId = rightId = use.equals("bow") ? items.useProgress() >= 1 ? "bow_aim" : "bow_draw" : "crossbow_draw";
            paired = true;
            mirrorPair = items.activeArm().equals("left");
            actionTime = library.clip(leftId).duration() * items.useProgress();
        } else if (input.usingItem() && use.equals("block")) {
            if (items.activeArm().equals("left")) leftId = "shield_block_left";
            else rightId = "shield_block_right";
        } else if (input.usingItem()) {
            // Eating, spear, spyglass and mod-specific use poses retain the actual game's arm solver.
            ownsLeft = ownsRight = false;
            leftId = rightId = "";
        } else if (items.left().equals("bow") || items.right().equals("bow")) {
            leftId = rightId = "bow_hold"; paired = true;
            mirrorPair = items.left().equals("bow") && !items.right().equals("bow");
        } else if (items.left().equals("crossbow") || items.right().equals("crossbow")) {
            leftId = rightId = items.leftCharged() || items.rightCharged() ? "crossbow_loaded" : "crossbow_hold";
            paired = true;
            mirrorPair = items.left().equals("crossbow") && !items.right().equals("crossbow");
        }
        var swing = input.swingAction();
        boolean swinging = swing == null ? input.swinging() : swing.active();
        if (swinging && !input.usingItem()) {
            leftId = hold(items.left(), "left");
            rightId = hold(items.right(), "right");
            mirrorPair = false;
            if (!wasSwinging || swing != null && swing.generation() != swingGeneration) swingStarted = input.timeSeconds();
            if (swing != null) swingGeneration = swing.generation();
            phase = swing == null ? (float)Math.min(1, (input.timeSeconds() - swingStarted) / .3) : swing.progress();
            boolean leftSwing = items.activeArm().equals("left")
                    || items.activeArm().isEmpty() && items.right().equals("empty") && !items.left().equals("empty");
            String type = leftSwing ? items.left() : items.right();
            String attack = type.equals("sword") ? "attack_sword" : type.equals("tool") ? "attack_tool_right"
                    : type.equals("shield") ? "attack_shield_right" : type.equals("empty") ? "attack_item" : "attack_item_right";
            upperBody = library.clip(attack).mask().equals("upper_body");
            if (upperBody) {
                leftId = rightId = attack;
                mirrorPair = leftSwing;
            } else if (leftSwing) { leftId = attack; mirrorLeft = true; }
            else rightId = attack;
            actionTime = library.clip(attack).duration() * phase;
            paired = false;
        } else phase = 0;
        wasSwinging = swinging;
        if (!leftId.isEmpty()) sample(library.clip(leftId), actionTime, left);
        if (!rightId.isEmpty()) sample(library.clip(rightId), actionTime, right);
        if (mirrorPair) {
            library.mirror(left, mirrored); left.set(mirrored);
            library.mirror(right, mirrored); right.set(mirrored);
        } else if (mirrorLeft) {
            library.mirror(left, mirrored); left.set(mirrored);
        }
        suppressVertical = !input.onGround();
        enabled = true;
        animation.resume();
        selection = new Selection(baseId, leftId, rightId, baseTime, phase, false, "");
        return new VanillaPoseSelection(true, false, !ownsLeft || !ownsRight,
                input.swimming() || input.crawling() || state.hurtTicks() > 0 ? 1 : 0,
                0, 1, ownsLeft ? 0 : 1, ownsRight ? 0 : 1, 0, 0);
    }

    private boolean initializeLibrary() {
        if (retargeter != null) return true;
        if (!unsupported.isEmpty()) return false;
        try {
            library = DefaultMotionLibrary.builtin();
            retargeter = new DefaultMotionRetargeter(profile, animation.getScene(), library);
            base = library.newPose(); sampled = library.newPose(); transition = library.newPose();
            left = library.newPose(); right = library.newPose(); first = library.newPose(); mirrored = library.newPose();
            library.clip("stand").sample(0, base); transition.set(base);
            cn.minerealms.rigworks.debug.RigWorksDiagnostics.debug("DEFAULT calibrated {}: {} shared clips, {} pose bytes",
                    profile.model(), library.clipNames().size(), library.poseArrayBytes());
            return true;
        } catch (IllegalArgumentException | IllegalStateException error) {
            unsupported = error.getMessage() == null ? error.toString() : error.getMessage();
            org.apache.logging.log4j.LogManager.getLogger("rigworks.motion").warn(
                    "Cannot calibrate DEFAULT motion for {}; retaining vanilla poses: {}", profile.model(), unsupported, error);
            return false;
        }
    }

    private void sample(DefaultMotionLibrary.Clip clip, double time, DefaultMotionLibrary.Pose output) {
        clip.sample(time, output);
        if (clip.looping() && clip.duration() > 0) {
            double window = Math.min(4.0 / library.fps(), clip.duration() / 4);
            double remaining = clip.duration() - time % clip.duration();
            if (remaining < window) {
                clip.sample(0, first);
                output.blend(output, first, (float)(1 - remaining / window));
            }
        }
    }

    private static String hold(String item, String side) {
        return item.equals("empty") ? "" : item.equals("shield") ? "shield_hold_" + side : "item_hold_" + side;
    }

    private VanillaPoseSelection fallback(String reason) {
        enabled = false; wasSwinging = false;
        selection = new Selection("", "", "", 0, 0, true, reason);
        return VanillaPoseSelection.ALL;
    }

    public Selection lastSelection() { return selection; }
    public float maxLegError() { return retargeter == null ? 0 : retargeter.maxLegError(); }
    public float maxHandError() { return retargeter == null ? 0 : retargeter.maxHandError(); }
    @Override public Category category() { return Category.MOTION; }
    @Override public void apply(AssimpScene scene, Map<String, Matrix4f> locals, double deltaSeconds) {
        if (enabled) retargeter.apply(locals, base, leftId.isEmpty() ? null : left,
                rightId.isEmpty() ? null : right, ownsLeft, ownsRight, paired, upperBody, suppressVertical);
    }
    @Override public void reset() {
        active = enabled = wasSwinging = false;
        lastSequence = swingGeneration = -1; lastTime = -1; baseId = leftId = rightId = "";
        baseTime = transitionTime = 0;
        selection = new Selection("", "", "", 0, 0, true, "inactive");
    }
    @Override public void close() {
        if (closed) return;
        closed = true; reset();
        if (!animation.isClosed() && animation.getModifier(MODIFIER) == this) animation.removeModifier(MODIFIER);
    }
}
