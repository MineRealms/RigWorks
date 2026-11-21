package cn.minerealms.rigworks.ysm.runtime;

import cn.minerealms.assimploader.animation.AnimationState;
import cn.minerealms.rigworks.character.capabilities.BlinkQuery;
import cn.minerealms.rigworks.character.core.CharacterEquipmentDefinition;
import cn.minerealms.rigworks.character.core.PlayerVisualInput;
import cn.minerealms.rigworks.debug.RigWorksDiagnostics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A character's mutable YSM state. Geometry and compiled programs belong to its shared definition. */
public final class YsmRuntimeInstance implements AutoCloseable {
    public static final String MODIFIER_ID = "ysm:pose";

    public static final class Definition {
        private final YsmModelSource.YsmModelView model;
        private final YsmPoseDriver.Definition pose;
        private final List<YsmModelSource.YsmControllerView> controllers;
        private final List<YsmControllerRuntime.Definition> preparedControllers;

        public Definition(YsmModelSource.YsmModelView model) {
            this.model = Objects.requireNonNull(model);
            pose = new YsmPoseDriver.Definition(model.animations(), model.bones());
            controllers = YsmRuntimeBridge.thirdPersonControllers(model);
            preparedControllers = controllers.stream().map(view -> new YsmControllerRuntime.Definition(
                    YsmControllerRuntime.fromView(view))).toList();
        }

        public YsmModelSource.YsmModelView model() { return model; }
    }

    /** Plain owner data also lets editor previews and tests drive the same runtime without a player entity. */
    public record OwnerState(double health, double maxHealth, double yawSpeed,
                             double deltaX, double deltaY, double deltaZ, boolean mainHandRight,
                             YsmItemStateBridge.HeldItem mainItem, YsmItemStateBridge.HeldItem offItem,
                             YsmAnimationInputProvider.Snapshot optionalInputs, boolean swingingOffhand) {
        public static final OwnerState EMPTY = new OwnerState(20, 20, 0, 0, 0, 0, true,
                YsmItemStateBridge.HeldItem.EMPTY, YsmItemStateBridge.HeldItem.EMPTY,
                YsmAnimationInputProvider.Snapshot.empty());

        public OwnerState {
            mainItem = Objects.requireNonNull(mainItem);
            offItem = Objects.requireNonNull(offItem);
            optionalInputs = Objects.requireNonNull(optionalInputs);
        }

        public OwnerState(double health, double maxHealth, double yawSpeed,
                          double deltaX, double deltaY, double deltaZ, boolean mainHandRight,
                          YsmItemStateBridge.HeldItem mainItem, YsmItemStateBridge.HeldItem offItem,
                          YsmAnimationInputProvider.Snapshot optionalInputs) {
            this(health, maxHealth, yawSpeed, deltaX, deltaY, deltaZ, mainHandRight,
                    mainItem, offItem, optionalInputs, false);
        }
    }

    private final Definition definition;
    private final UUID ownerId;
    private final YsmPoseDriver pose;
    private final YsmVariableContext variables = new YsmVariableContext();
    private final List<YsmControllerRuntime> controllers;
    private final YsmDefaultAnimationController defaults, armDefaults;
    private final BlinkQuery blink;
    private AnimationState animation;
    private long lastSequence = -1;
    private double lastTime = -1;
    private int blinkTicks;
    private boolean sleeping;
    private boolean closed;

    public YsmRuntimeInstance(Definition definition, UUID ownerId) {
        this.definition = Objects.requireNonNull(definition);
        this.ownerId = Objects.requireNonNull(ownerId);
        pose = new YsmPoseDriver(definition.pose);
        // The query object is owned by this binding. Updating its two scalar inputs avoids
        // allocating a UUID query and lambdas on every render tick.
        blink = new BlinkQuery(ownerId, () -> blinkTicks, () -> sleeping);
        var overrides = YsmRuntimeBridge.defaultOverrides(definition.controllers);
        defaults = new YsmDefaultAnimationController(definition.model.animations(),
                group -> pose.scoped(group, "default"), "main", overrides);
        armDefaults = new YsmDefaultAnimationController(definition.model.animations(),
                group -> pose.scoped(group, "default"), "arm", overrides);
        controllers = new ArrayList<>(definition.controllers.size());
        for (int i = 0; i < definition.controllers.size(); i++) {
            var view = definition.controllers.get(i);
            controllers.add(new YsmControllerRuntime(definition.preparedControllers.get(i),
                    pose.scoped(view.sourceGroup(), view.name())));
        }
        pose.setContext(variables);
    }

    public void attach(AnimationState target) {
        if (closed) throw new IllegalStateException("YSM runtime is closed");
        if (target == null || target.isClosed()) throw new IllegalArgumentException("A live animation is required");
        if (animation == target) return;
        if (animation != null) throw new IllegalStateException("YSM runtime already belongs to an animation owner");
        if (target.getModifier(MODIFIER_ID) != null)
            throw new IllegalStateException("The animation already has a YSM pose owner");
        target.addModifier(MODIFIER_ID, pose);
        animation = target;
    }

    public void update(AbstractClientPlayer owner, CharacterEquipmentDefinition equipment, PlayerVisualInput input) {
        Objects.requireNonNull(owner, "YSM input owner");
        Objects.requireNonNull(input, "YSM input");
        if (!canUpdate()) return;
        if (!ownerId.equals(owner.getUUID())) throw new IllegalArgumentException("YSM input owner mismatch");
        if (input.sequence() <= lastSequence) return;
        var delta = owner.getDeltaMovement();
        update(input, equipment, new OwnerState(owner.getHealth(), owner.getMaxHealth(),
                20d * Mth.wrapDegrees(owner.getYRot() - owner.yRotO), delta.x, delta.y, delta.z,
                owner.getMainArm() == HumanoidArm.RIGHT,
                YsmItemStateBridge.held(owner, owner.getMainHandItem(), true),
                YsmItemStateBridge.held(owner, owner.getOffhandItem(), false),
                YsmAnimationInputRegistry.sample(owner),
                owner.swingingArm == net.minecraft.world.InteractionHand.OFF_HAND));
    }

    public void update(PlayerVisualInput input, CharacterEquipmentDefinition equipment, OwnerState owner) {
        Objects.requireNonNull(input);
        Objects.requireNonNull(owner);
        if (!canUpdate() || input.sequence() <= lastSequence || input.timeSeconds() < lastTime) return;
        double deltaSeconds = lastTime < 0 ? .05 : input.timeSeconds() - lastTime;
        lastSequence = input.sequence();
        lastTime = input.timeSeconds();
        blinkTicks = (int) (input.sequence() % Integer.MAX_VALUE);
        sleeping = input.sleeping();
        var item = YsmItemStateBridge.derive(equipment,
                owner.mainHandRight() ? owner.mainItem() : owner.offItem(),
                owner.mainHandRight() ? owner.offItem() : owner.mainItem(), owner.mainHandRight());
        var context = YsmExpressionRuntime.context(input, input.actionState(), item, owner.health(), owner.maxHealth());
        boolean swinging = input.swingAction() == null ? input.swinging() : input.swingAction().active();
        context.setQuery("using_offhand", input.usingItem() && owner.offItem().using() ? 1 : 0)
                .setQuery("swinging_offhand", swinging && owner.swingingOffhand() ? 1 : 0);
        context.set("tick", input.sequence());
        double yaw = Mth.wrapDegrees(input.headYawDegrees() - input.bodyYawDegrees());
        context.setQuery("ysm.head_yaw", yaw).setQuery("ysm.head_pitch", input.headPitchDegrees());
        // Legacy YSM uses these X/Y names for yaw/pitch respectively.
        context.setQuery("head_x_rotation", yaw).setQuery("head_y_rotation", input.headPitchDegrees());
        context.setQuery("ground_speed", input.horizontalSpeed()).setQuery("vertical_speed", input.verticalVelocity());
        context.setQuery("yaw_speed", owner.yawSpeed()).setQuery("life_time", input.timeSeconds());
        context.setQuery("delta_time", Math.min(1, deltaSeconds));
        context.setQuery("position_delta(0)", owner.deltaX()).setQuery("position_delta(1)", owner.deltaY())
                .setQuery("position_delta(2)", owner.deltaZ());
        context.setQuery("is_on_ground", input.onGround() ? 1 : 0).setQuery("is_in_water", input.actionState().inWater() ? 1 : 0);
        context.setQuery("ysm.is_close_eyes", blink.isCloseEyes() ? 1 : 0);
        owner.optionalInputs().queries().forEach(context::setQuery);
        context.addTokens(owner.optionalInputs().functions());
        variables.bind(context);
        defaults.update(input, variables, owner.optionalInputs().values(), owner.mainItem(), owner.offItem());
        armDefaults.update(input, variables, owner.optionalInputs().values(), owner.mainItem(), owner.offItem());
        for (var controller : controllers) controller.advance(variables);
        if (input.sequence() % 100 == 0 && RigWorksDiagnostics.enabled())
            RigWorksDiagnostics.debug("YSM owner={} crouching={} speed={} mainItem={} offItem={} layers={}",
                    ownerId, input.crouching(), input.horizontalSpeed(), owner.mainItem().id(), owner.offItem().id(), pose.activeLayers());
    }

    public Definition definition() { return definition; }
    public YsmModelSource.YsmModelView model() { return definition.model; }
    public YsmPoseDriver poseDriver() { return pose; }
    public boolean active() { return !closed && lastSequence >= 0 && ownsAttachedPose(); }
    public boolean isClosed() { return closed; }
    double value(String name) { return variables.value(name); }

    private boolean ownsAttachedPose() {
        return animation == null || !animation.isClosed() && animation.getModifier(MODIFIER_ID) == pose;
    }

    private boolean canUpdate() {
        if (closed) return false;
        if (!ownsAttachedPose()) {
            close();
            throw new IllegalStateException("YSM animation owner was closed or its pose was replaced");
        }
        return true;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (var controller : controllers) controller.reset();
        defaults.reset();
        armDefaults.reset();
        pose.reset();
        variables.clear();
        if (animation != null && !animation.isClosed() && animation.getModifier(MODIFIER_ID) == pose)
            animation.removeModifier(MODIFIER_ID);
        animation = null;
    }
}
