package cn.minerealms.rigworks.character.client;

import net.minecraft.client.player.AbstractClientPlayer;
import org.apache.logging.log4j.LogManager;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** TaCZ supplies the animated hand frame; RigWorks supplies only the geometry. */
final class TaczFirstPersonCompat {
    private static final MethodHandle HAND_SCOPE = handScope();
    private static boolean warned;

    private TaczFirstPersonCompat() {}

    static boolean drawingHand(AbstractClientPlayer player) {
        if (HAND_SCOPE == null) return TaczHeldItemCompat.isGun(player.getMainHandItem());
        try { return (boolean) HAND_SCOPE.invokeExact((Object) player); }
        catch (Throwable failure) {
            if (!warned) {
                warned = true;
                LogManager.getLogger("rigworks.compat.tacz")
                        .warn("Cannot read TaCZ first-person draw scope; using held-gun fallback", failure);
            }
            return TaczHeldItemCompat.isGun(player.getMainHandItem());
        }
    }

    private static MethodHandle handScope() {
        try {
            var type = Class.forName("com.tacz.guns.client.animation.third.AnimationRenderContext", false,
                    TaczFirstPersonCompat.class.getClassLoader());
            return MethodHandles.publicLookup().unreflect(type.getMethod("isFirstPersonHand", Object.class))
                    .asType(MethodType.methodType(boolean.class, Object.class));
        } catch (ReflectiveOperationException | LinkageError absent) { return null; }
    }
}
