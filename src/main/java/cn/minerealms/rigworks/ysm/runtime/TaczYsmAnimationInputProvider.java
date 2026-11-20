/*
 * Originally derived from YSM's TACZ client adapter (Apache-2.0).
 * Modified by MineRealms for RigWorks; optional API access is reflective.
 */
package cn.minerealms.rigworks.ysm.runtime;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Optional TaCZ state sampler, isolated from the base RigWorks/TaCZ linkage. */
public final class TaczYsmAnimationInputProvider implements YsmAnimationInputProvider {
    private static final String GUN_API = "com.tacz.guns.api.item.IGun";
    private static final String TIMELESS_API = "com.tacz.guns.api.TimelessAPI";
    private static final String OPERATOR_API = "com.tacz.guns.api.entity.IGunOperator";
    private static final String CLIENT_OPERATOR_API = "com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator";

    @Override public String id() { return "tacz"; }

    @Override public Snapshot sample(LocalPlayer player) {
        if (player == null) return Snapshot.empty();
        try {
            Class<?> gunApi = Class.forName(GUN_API, false, getClass().getClassLoader());
            ItemStack stack = player.getMainHandItem();
            Object gun = invokeStatic(gunApi, "getIGunOrNull", stack);
            if (gun == null) return Snapshot.empty();
            Object gunId = invoke(gun, "getGunId", stack);
            String id = gunId == null ? "" : gunId.toString();
            String type = gunType(gunId);
            Object operator = invokeStatic(Class.forName(OPERATOR_API, false, getClass().getClassLoader()),
                    "fromLivingEntity", player);
            double aim = number(invoke(operator, "getSynAimingProgress"));
            Object reloadState = invoke(operator, "getSynReloadState");
            double reload = number(invoke(reloadState, "getCountDown"));
            double melee = number(invoke(operator, "getSynMeleeCoolDown"));
            double firing = number(invoke(operator, "getSynShootCoolDown"));
            try {
                Class<?> clientApi = Class.forName(CLIENT_OPERATOR_API, false, getClass().getClassLoader());
                if (clientApi.isInstance(player))
                    firing = Math.max(firing, number(invoke(player, "getClientShootCoolDown")));
            } catch (ReflectiveOperationException ignored) {
                // Older TaCZ builds may not expose client-side shoot cooldown.
            }

            Map<String, Double> queries = new LinkedHashMap<>();
            queries.put("q.tac_holding_gun", 1d);
            queries.put("q.tac_aiming", positive(aim));
            queries.put("q.tac_aim_progress", finite(aim));
            queries.put("q.tac_reloading", positive(reload));
            queries.put("q.tac_reload_progress", finite(reload));
            queries.put("q.tac_melee", positive(melee));
            queries.put("q.tac_firing", positive(firing));
            queries.put("q.tac_swimming", player.isSwimming() ? 1d : 0d);
            queries.put("q.tac_sprinting", player.onGround() && player.isSprinting() ? 1d : 0d);
            Map<String, String> values = new LinkedHashMap<>();
            values.put("tac.gun_id", id);
            values.put("tac.gun_type", type);
            Set<String> functions = new LinkedHashSet<>();
            functions.add("ctrl.tac_hold_gun()");
            if (!id.isBlank()) functions.add("ctrl.tac_gun('" + id + "')");
            if (!type.isBlank()) functions.add("ctrl.tac_type('" + type + "')");
            if (aim > 0) functions.add("ctrl.tac_state('aim')");
            else if (reload > 0) functions.add("ctrl.tac_state('reload')");
            else if (melee > 0) functions.add("ctrl.tac_state('melee')");
            else if (firing > 0) functions.add("ctrl.tac_state('fire')");
            else if (player.isSwimming()) functions.add("ctrl.tac_state('climb')");
            else if (player.onGround() && player.isSprinting()) functions.add("ctrl.tac_state('run')");
            else functions.add("ctrl.tac_state('hold')");
            return new Snapshot(queries, functions, values);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
            return Snapshot.empty();
        }
    }

    private static String gunType(Object gunId) {
        if (gunId == null) return "";
        try {
            Class<?> api = Class.forName(TIMELESS_API, false, TaczYsmAnimationInputProvider.class.getClassLoader());
            Object result = invokeStatic(api, "getCommonGunIndex", gunId);
            if (!(result instanceof java.util.Optional<?> optional) || optional.isEmpty()) return "";
            Object type = invoke(optional.get(), "getType");
            return type == null ? "" : type.toString().toLowerCase(Locale.ROOT);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return "";
        }
    }

    private static Object invokeStatic(Class<?> type, String name, Object argument) throws ReflectiveOperationException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 1 && compatible(method.getParameterTypes()[0], argument))
                return method.invoke(null, argument);
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static Object invoke(Object target, String name, Object argument) throws ReflectiveOperationException {
        if (target == null) return null;
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                    && compatible(method.getParameterTypes()[0], argument)) return method.invoke(target, argument);
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    private static Object invoke(Object target, String name) throws ReflectiveOperationException {
        if (target == null) return null;
        return target.getClass().getMethod(name).invoke(target);
    }

    private static boolean compatible(Class<?> parameter, Object argument) {
        return argument == null || parameter.isInstance(argument)
                || parameter.isPrimitive() && argument instanceof Number;
    }

    private static double number(Object value) { return value instanceof Number number ? number.doubleValue() : 0d; }
    private static double finite(double value) { return Double.isFinite(value) ? value : 0d; }
    private static double positive(double value) { return value > 0 ? 1d : 0d; }
}
