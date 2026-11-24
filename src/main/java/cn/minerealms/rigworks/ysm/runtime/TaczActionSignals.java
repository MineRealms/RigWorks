package cn.minerealms.rigworks.ysm.runtime;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Accepted client gun events distinguish rapid shots whose sampled cooldowns are identical. */
final class TaczActionSignals {
    private static final Map<Object, Counters> OWNERS = new WeakHashMap<>();
    private static final Counters EMPTY = new Counters();
    private static boolean registered;

    static final class Counters {
        volatile long fire, reload, melee, draw;
    }

    private TaczActionSignals() {}

    static synchronized Counters get(Object owner) { return OWNERS.getOrDefault(owner, EMPTY); }

    static synchronized void record(Object owner, String action) {
        var counts = OWNERS.computeIfAbsent(owner, ignored -> new Counters());
        switch (action) {
            case "fire" -> counts.fire++;
            case "reload" -> counts.reload++;
            case "melee" -> counts.melee++;
            case "draw" -> counts.draw++;
            default -> throw new IllegalArgumentException("Unknown gun action " + action);
        }
    }

    static synchronized void register(TaczYsmAnimationInputProvider.ReflectiveAccess reflection) {
        if (registered) return;
        registered = true;
        // Recent TaCZ builds publish a post event for locally predicted shots. Remote
        // players still arrive through GunFireEvent in the server notification packet.
        boolean localPost = register(reflection, "GunFirePostEvent", "getShooter", "fire", false);
        register(reflection, "GunFireEvent", "getShooter", "fire", localPost);
        register(reflection, "GunReloadEvent", "getEntity", "reload", false);
        register(reflection, "GunMeleeEvent", "getShooter", "melee", false);
        register(reflection, "GunDrawEvent", "getEntity", "draw", false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean register(TaczYsmAnimationInputProvider.ReflectiveAccess reflection,
                                    String eventName, String ownerGetter, String action, boolean ignoreLocal) {
        Class<?> type = reflection.type("com.tacz.guns.api.event.common." + eventName);
        if (type == null || !Event.class.isAssignableFrom(type)) return false;
        var warned = new AtomicBoolean();
        Consumer<Event> listener = event -> {
            try {
                Object side = reflection.invoke(event, "getLogicalSide");
                if (side != null && !"CLIENT".equals(side.toString())) return;
                Object owner = reflection.invoke(event, ownerGetter);
                if (ignoreLocal && owner instanceof LocalPlayer) return;
                if (owner instanceof AbstractClientPlayer player && player.level().isClientSide())
                    record(owner, action);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                if (warned.compareAndSet(false, true)) org.apache.logging.log4j.LogManager.getLogger("rigworks.ysm").warn(
                        "Cannot observe accepted TaCZ {} event", action, failure);
            }
        };
        try {
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, (Class) type, (Consumer) listener);
            return true;
        } catch (LinkageError | RuntimeException failure) {
            org.apache.logging.log4j.LogManager.getLogger("rigworks.ysm").warn(
                    "Cannot subscribe to optional TaCZ {} event; cooldown sampling remains available", eventName, failure);
            return false;
        }
    }
}
