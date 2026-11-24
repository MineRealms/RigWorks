/*
 * Originally derived from YSM's TACZ client adapter (Apache-2.0).
 * Modified by MineRealms for RigWorks; optional API access is reflective.
 */
package cn.minerealms.rigworks.ysm.runtime;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Pose;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Optional TaCZ state sampler, isolated from the base RigWorks/TaCZ linkage. */
public final class TaczYsmAnimationInputProvider implements YsmAnimationInputProvider {
    private static final Logger LOGGER = LogManager.getLogger("rigworks.ysm");
    private static final String GUN_API = "com.tacz.guns.api.item.IGun";
    private static final String TIMELESS_API = "com.tacz.guns.api.TimelessAPI";
    private static final String OPERATOR_API = "com.tacz.guns.api.entity.IGunOperator";
    private static final String CLIENT_OPERATOR_API = "com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator";
    private static final Snapshot EMPTY = Snapshot.empty();
    private final ReflectiveAccess reflection;
    private final Set<String> failedOperations = ConcurrentHashMap.newKeySet();

    public TaczYsmAnimationInputProvider() {
        this(new ReflectiveAccess(name -> Class.forName(name, false,
                TaczYsmAnimationInputProvider.class.getClassLoader())));
        TaczActionSignals.register(reflection);
    }

    TaczYsmAnimationInputProvider(ReflectiveAccess reflection) { this.reflection = reflection; }

    @Override public String id() { return "tacz"; }

    @Override public Snapshot sample(LocalPlayer player) {
        return sampleOwner(player);
    }

    @Override public Snapshot sampleOwner(AbstractClientPlayer player) {
        if (player == null) return EMPTY;
        return sampleState(player, player.getMainHandItem(), player.isSwimming(),
                player.onGround() && player.isSprinting(), player.getPose() == Pose.SWIMMING && !player.isSwimming());
    }

    Snapshot sampleState(Object player, Object stack, boolean swimming, boolean sprinting) {
        return sampleState(player, stack, swimming, sprinting, false);
    }

    Snapshot sampleState(Object player, Object stack, boolean swimming, boolean sprinting, boolean crawling) {
        try {
            Object gun = reflection.invokeStatic(reflection.type(GUN_API), "getIGunOrNull", stack);
            if (gun == null) return EMPTY;
            Object gunId = reflection.invoke(gun, "getGunId", stack);
            String id = gunId == null ? "" : gunId.toString();
            String type = gunType(gunId);
            Object ammoValue = reflection.invoke(gun, "getCurrentAmmoCount", stack);
            double ammo = number(ammoValue);
            boolean barrelLoaded = Boolean.TRUE.equals(reflection.invoke(gun, "hasBulletInBarrel", stack));
            boolean inventoryAmmo = Boolean.TRUE.equals(reflection.invoke(gun, "useInventoryAmmo", stack));
            boolean overheated = Boolean.TRUE.equals(reflection.invoke(gun, "isOverheatLocked", stack));
            Object fireModeValue = reflection.invoke(gun, "getFireMode", stack);
            String fireMode = fireModeValue == null ? "" : fireModeValue.toString().toLowerCase(Locale.ROOT);
            Object operator = reflection.invokeStatic(reflection.type(OPERATOR_API), "fromLivingEntity", player);
            double aim = number(reflection.invoke(operator, "getSynAimingProgress"));
            Object reloadState = reflection.invoke(operator, "getSynReloadState");
            double reload = number(reflection.invoke(reloadState, "getCountDown"));
            double melee = number(reflection.invoke(operator, "getSynMeleeCoolDown"));
            double firing = number(reflection.invoke(operator, "getSynShootCoolDown"));
            double draw = number(reflection.invoke(operator, "getSynDrawCoolDown"));
            boolean bolting = Boolean.TRUE.equals(reflection.invoke(operator, "getSynIsBolting"));
            boolean aimIntent = Boolean.TRUE.equals(reflection.invoke(operator, "getSynIsAiming"));
            double sprintTime = number(reflection.invoke(operator, "getSynSprintTime"));
            Object reloadType = reflection.invoke(reloadState, "getStateType");
            String reloadStage = reloadType == null ? "" : reloadType.toString().toLowerCase(Locale.ROOT);
            var events = TaczActionSignals.get(player);
            Class<?> clientApi = reflection.type(CLIENT_OPERATOR_API);
            double chargeProgress = 0;
            boolean charging = false;
            if (clientApi != null && clientApi.isInstance(player)) {
                firing = Math.max(firing, number(reflection.invoke(player, "getClientShootCoolDown")));
                Object clientAim = reflection.invoke(player, "getClientAimingProgress", 1f);
                if (clientAim instanceof Number value) aim = finite(value.doubleValue());
                Object clientAimIntent = reflection.invoke(player, "isAim");
                if (clientAimIntent instanceof Boolean value) aimIntent = value;
                chargeProgress = number(reflection.invoke(player, "getChargeProgress"));
                charging = Boolean.TRUE.equals(reflection.invoke(player, "isCharging"));
            }

            Map<String, Double> queries = new LinkedHashMap<>(48);
            queries.put("q.tac_holding_gun", 1d);
            queries.put("q.tac_aiming", positive(aim));
            queries.put("q.tac_aim_progress", finite(aim));
            queries.put("q.tac_is_aiming", aimIntent ? 1d : 0d);
            queries.put("q.tac_reloading", positive(reload));
            queries.put("q.tac_reload_progress", finite(reload));
            // Cooldown queries preserve TaCZ's API units.
            queries.put("q.tac_reload_cooldown", reload);
            queries.put("q.tac_melee", positive(melee));
            queries.put("q.tac_melee_cooldown", melee);
            queries.put("q.tac_firing", positive(firing));
            queries.put("q.tac_fire_cooldown", firing);
            queries.put("q.tac_drawing", positive(draw));
            queries.put("q.tac_draw_cooldown", draw);
            queries.put("q.tac_bolting", bolting ? 1d : 0d);
            queries.put("q.tac_sprint_time", sprintTime);
            queries.put("q.tac_ammo", ammo);
            queries.put("q.tac_ammo_known", ammoValue instanceof Number ? 1d : 0d);
            queries.put("q.tac_barrel_loaded", barrelLoaded ? 1d : 0d);
            queries.put("q.tac_inventory_ammo", inventoryAmmo ? 1d : 0d);
            queries.put("q.tac_overheated", overheated ? 1d : 0d);
            queries.put("q.tac_charge_progress", chargeProgress);
            queries.put("q.tac_charging", charging ? 1d : 0d);
            queries.put("q.tac_reload_empty", reloadStage.startsWith("empty_") ? 1d : 0d);
            queries.put("q.tac_reload_finishing", reloadStage.endsWith("_finishing") ? 1d : 0d);
            queries.put("q.tac_fire_generation", (double) events.fire);
            queries.put("q.tac_reload_generation", (double) events.reload);
            queries.put("q.tac_melee_generation", (double) events.melee);
            queries.put("q.tac_draw_generation", (double) events.draw);
            queries.put("q.tac_swimming", swimming ? 1d : 0d);
            queries.put("q.tac_sprinting", sprinting ? 1d : 0d);
            queries.put("q.tac_crawling", crawling ? 1d : 0d);
            Map<String, String> values = new LinkedHashMap<>();
            values.put("tac.gun_id", id);
            values.put("tac.gun_type", type);
            values.put("tac.reload_stage", reloadStage);
            values.put("tac.fire_mode", fireMode);
            Set<String> functions = new LinkedHashSet<>();
            functions.add("ctrl.tac_hold_gun()");
            if (!id.isBlank()) functions.add("ctrl.tac_gun('" + id + "')");
            if (!type.isBlank()) functions.add("ctrl.tac_type('" + type + "')");
            if (!fireMode.isBlank()) functions.add("ctrl.tac_fire_mode('" + fireMode + "')");
            if (aim > 0) functions.add("ctrl.tac_state('aim')");
            if (reload > 0) functions.add("ctrl.tac_state('reload')");
            if (melee > 0) functions.add("ctrl.tac_state('melee')");
            if (firing > 0) functions.add("ctrl.tac_state('fire')");
            if (draw > 0) functions.add("ctrl.tac_state('draw')");
            if (bolting) functions.add("ctrl.tac_state('bolt')");
            if (charging) functions.add("ctrl.tac_state('charge')");
            if (swimming) functions.add("ctrl.tac_state('swim')");
            if (crawling) functions.add("ctrl.tac_state('climb')");
            if (sprinting) functions.add("ctrl.tac_state('run')");
            if (aim <= 0 && reload <= 0 && melee <= 0 && firing <= 0 && draw <= 0 && !bolting && !charging
                    && !swimming && !sprinting && !crawling) functions.add("ctrl.tac_state('hold')");
            return new Snapshot(queries, functions, values);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            warnOnce("sample state", failure);
            return EMPTY;
        }
    }

    private String gunType(Object gunId) {
        if (gunId == null) return "";
        try {
            Object result = reflection.invokeStatic(reflection.type(TIMELESS_API), "getCommonGunIndex", gunId);
            if (!(result instanceof java.util.Optional<?> optional) || optional.isEmpty()) return "";
            Object type = reflection.invoke(optional.get(), "getType");
            return type == null ? "" : type.toString().toLowerCase(Locale.ROOT);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            warnOnce("resolve gun type", failure);
            return "";
        }
    }

    private void warnOnce(String operation, Throwable failure) {
        if (!failedOperations.add(operation)) return;
        Throwable cause = failure instanceof InvocationTargetException invocation && invocation.getCause() != null
                ? invocation.getCause() : failure;
        LOGGER.warn("TaCZ YSM input operation '{}' failed; later samples will retry (further failures suppressed)",
                operation, cause);
    }

    @FunctionalInterface interface TypeResolver { Class<?> resolve(String name) throws ClassNotFoundException; }

    /** Missing APIs are cached too; adapted handles avoid per-call argument arrays. */
    static final class ReflectiveAccess {
        private final TypeResolver resolver;
        private final ConcurrentMap<String, Optional<Class<?>>> classes = new ConcurrentHashMap<>();
        private final ClassValue<Methods> methods = new ClassValue<>() {
            @Override protected Methods computeValue(Class<?> type) { return new Methods(type); }
        };

        ReflectiveAccess(TypeResolver resolver) { this.resolver = resolver; }

        Class<?> type(String name) {
            Optional<Class<?>> cached = classes.get(name);
            if (cached == null) cached = classes.computeIfAbsent(name, this::resolve);
            return cached.orElse(null);
        }

        private Optional<Class<?>> resolve(String name) {
            try { return Optional.ofNullable(resolver.resolve(name)); }
            catch (ClassNotFoundException | LinkageError unavailable) { return Optional.empty(); }
        }

        Object invokeStatic(Class<?> type, String name, Object argument) throws ReflectiveOperationException {
            return type == null ? null : oneArgument(type, name, argument, true).call(null, argument);
        }

        Object invoke(Object target, String name, Object argument) throws ReflectiveOperationException {
            return target == null ? null : oneArgument(target.getClass(), name, argument, false).call(target, argument);
        }

        Object invoke(Object target, String name) throws ReflectiveOperationException {
            return target == null ? null : zeroArguments(target.getClass(), name).call(target);
        }

        Invocation zeroArguments(Class<?> type, String name) { return methods.get(type).zero(name); }
        Invocation oneArgument(Class<?> type, String name, Object argument, boolean isStatic) {
            return methods.get(type).one(name, argument == null ? NullArgument.class : argument.getClass(), isStatic);
        }

        private static final class NullArgument {}

        private static final class Methods {
            private final Class<?> owner;
            private final ConcurrentMap<String, Invocation> zero = new ConcurrentHashMap<>();
            private final ConcurrentMap<String, ClassValue<Invocation>> staticOne = new ConcurrentHashMap<>();
            private final ConcurrentMap<String, ClassValue<Invocation>> instanceOne = new ConcurrentHashMap<>();

            Methods(Class<?> type) { owner = type; }

            Invocation zero(String name) {
                Invocation cached = zero.get(name);
                if (cached == null) cached = zero.computeIfAbsent(name, key -> find(key, null, false));
                return cached;
            }

            Invocation one(String name, Class<?> argument, boolean isStatic) {
                var cache = isStatic ? staticOne : instanceOne;
                ClassValue<Invocation> candidates = cache.get(name);
                if (candidates == null) candidates = cache.computeIfAbsent(name, key -> new ClassValue<>() {
                    @Override protected Invocation computeValue(Class<?> type) { return find(key, type, isStatic); }
                });
                return candidates.get(argument);
            }

            private Invocation find(String name, Class<?> argument, boolean isStatic) {
                for (Method method : owner.getMethods()) {
                    if (!method.getName().equals(name) || Modifier.isStatic(method.getModifiers()) != isStatic
                            || method.getParameterCount() != (argument == null ? 0 : 1)) continue;
                    if (argument != null && argument != NullArgument.class) {
                        Class<?> parameter = method.getParameterTypes()[0];
                        if (!parameter.isAssignableFrom(argument) && !(parameter == float.class && argument == Float.class)) continue;
                    }
                    try { return new Invocation(method); }
                    catch (IllegalAccessException ignored) { /* An inaccessible optional API is absent. */ }
                }
                return Invocation.MISSING;
            }
        }
    }

    static final class Invocation {
        private static final Invocation MISSING = new Invocation();
        private final MethodHandle handle;
        private final boolean isStatic;

        private Invocation() { handle = null; isStatic = false; }
        Invocation(Method method) throws IllegalAccessException {
            isStatic = Modifier.isStatic(method.getModifiers());
            MethodType signature = isStatic || method.getParameterCount() == 0
                    ? MethodType.methodType(Object.class, Object.class)
                    : MethodType.methodType(Object.class, Object.class, Object.class);
            handle = MethodHandles.publicLookup().unreflect(method).asType(signature);
        }

        Object call(Object target) throws InvocationTargetException {
            if (handle == null) return null;
            try { return (Object) handle.invokeExact(target); }
            catch (Throwable error) { throw new InvocationTargetException(error); }
        }

        Object call(Object target, Object argument) throws InvocationTargetException {
            if (handle == null) return null;
            try { return isStatic ? (Object) handle.invokeExact(argument) : (Object) handle.invokeExact(target, argument); }
            catch (Throwable error) { throw new InvocationTargetException(error); }
        }
    }

    private static double number(Object value) { return value instanceof Number number ? finite(number.doubleValue()) : 0d; }
    private static double finite(double value) { return Double.isFinite(value) ? value : 0d; }
    private static double positive(double value) { return value > 0 ? 1d : 0d; }
}
