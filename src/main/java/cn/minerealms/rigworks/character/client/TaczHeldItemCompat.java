package cn.minerealms.rigworks.character.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.Optional;

/** Optional TaCZ renderer state normally supplied by its vanilla ItemInHandLayer mixin. */
final class TaczHeldItemCompat {
    private static final Logger LOGGER = LogManager.getLogger("rigworks.compat.tacz");
    private static final Access ACCESS = new Access(name -> Class.forName(name, false,
            TaczHeldItemCompat.class.getClassLoader()));

    private TaczHeldItemCompat() {}

    static boolean isGun(ItemStack stack) {
        return !stack.isEmpty() && ACCESS.gun != null && ACCESS.gun.isInstance(stack.getItem());
    }

    static boolean isPistol(ItemStack stack) { return ACCESS.isPistol(stack); }

    static Scope begin(Object owner, boolean self) { return ACCESS.begin(owner, self); }

    @FunctionalInterface interface TypeResolver { Class<?> resolve(String name) throws ClassNotFoundException; }

    static final class Access {
        final Class<?> gun;
        private final Field currentEntity;
        private final Field muzzleSelf;
        private final Field shellSelf;
        private final MethodHandle gunId;
        private final MethodHandle gunIndex;
        private final MethodHandle gunType;
        private boolean warned;

        Access(TypeResolver resolver) {
            gun = type(resolver, "com.tacz.guns.api.item.IGun");
            currentEntity = field(resolver, "com.tacz.guns.client.particle.ThirdPersonMuzzleParticleManager", "currentEntity");
            muzzleSelf = field(resolver, "com.tacz.guns.client.model.functional.MuzzleFlashRender", "isSelf");
            shellSelf = field(resolver, "com.tacz.guns.client.model.functional.ShellRender", "isSelf");
            gunId = method(gun, "getGunId", MethodType.methodType(Object.class, Object.class, Object.class), ItemStack.class);
            gunIndex = method(type(resolver, "com.tacz.guns.api.TimelessAPI"), "getCommonGunIndex",
                    MethodType.methodType(Object.class, Object.class), ResourceLocation.class);
            gunType = method(type(resolver, "com.tacz.guns.resource.index.CommonGunIndex"), "getType",
                    MethodType.methodType(Object.class, Object.class));
        }

        boolean isPistol(ItemStack stack) {
            if (gunId == null || gunIndex == null || gunType == null) return false;
            try {
                Object id = (Object) gunId.invokeExact((Object) stack.getItem(), (Object) stack);
                Object result = (Object) gunIndex.invokeExact(id);
                if (!(result instanceof Optional<?> optional) || optional.isEmpty()) return false;
                return "pistol".equalsIgnoreCase(String.valueOf((Object) gunType.invokeExact(optional.get())));
            } catch (Throwable failure) {
                warn(failure);
                return false;
            }
        }

        Scope begin(Object owner, boolean self) {
            if (currentEntity == null && muzzleSelf == null && shellSelf == null) return Scope.NONE;
            Scope scope = null;
            try {
                scope = new Scope(this, currentEntity == null ? null : currentEntity.get(null),
                        muzzleSelf != null && muzzleSelf.getBoolean(null), shellSelf != null && shellSelf.getBoolean(null));
                if (currentEntity != null) currentEntity.set(null, owner);
                if (muzzleSelf != null) muzzleSelf.setBoolean(null, self);
                if (shellSelf != null) shellSelf.setBoolean(null, self);
                return scope;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                if (scope != null) scope.close();
                warn(failure);
                return Scope.NONE;
            }
        }

        private void restore(Scope scope) {
            restore(currentEntity, scope.owner);
            restore(muzzleSelf, scope.muzzle);
            restore(shellSelf, scope.shell);
        }

        private void restore(Field field, Object previous) {
            if (field == null) return;
            try { field.set(null, previous); }
            catch (ReflectiveOperationException | LinkageError | RuntimeException failure) { warn(failure); }
        }

        private void warn(Throwable failure) {
            if (warned) return;
            warned = true;
            LOGGER.warn("Cannot adapt TaCZ held-item renderer state; further failures suppressed", failure);
        }

        private static Class<?> type(TypeResolver resolver, String name) {
            try { return resolver.resolve(name); }
            catch (ClassNotFoundException | LinkageError unavailable) { return null; }
        }

        private static Field field(TypeResolver resolver, String name, String member) {
            var type = type(resolver, name);
            if (type == null) return null;
            try { return type.getField(member); }
            catch (ReflectiveOperationException | LinkageError unavailable) { return null; }
        }

        private static MethodHandle method(Class<?> type, String name, MethodType signature, Class<?>... arguments) {
            if (type == null) return null;
            try { return MethodHandles.publicLookup().unreflect(type.getMethod(name, arguments)).asType(signature); }
            catch (ReflectiveOperationException | LinkageError unavailable) { return null; }
        }
    }

    static final class Scope implements AutoCloseable {
        static final Scope NONE = new Scope(null, null, false, false);
        private final Access access;
        private final Object owner;
        private final boolean muzzle;
        private final boolean shell;
        private boolean closed;

        private Scope(Access access, Object owner, boolean muzzle, boolean shell) {
            this.access = access;
            this.owner = owner;
            this.muzzle = muzzle;
            this.shell = shell;
        }

        @Override public void close() {
            if (access == null || closed) return;
            closed = true;
            access.restore(this);
        }
    }
}
