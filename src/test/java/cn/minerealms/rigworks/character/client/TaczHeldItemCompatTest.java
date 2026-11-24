package cn.minerealms.rigworks.character.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaczHeldItemCompatTest {
    public static final class Muzzle {
        public static boolean isSelf;
    }

    public static final class Shell {
        public static boolean isSelf;
    }

    public static final class Particles {
        public static Object currentEntity;
    }

    private static TaczHeldItemCompat.Access access() {
        return new TaczHeldItemCompat.Access(name -> switch (name) {
            case "com.tacz.guns.client.model.functional.MuzzleFlashRender" -> Muzzle.class;
            case "com.tacz.guns.client.model.functional.ShellRender" -> Shell.class;
            case "com.tacz.guns.client.particle.ThirdPersonMuzzleParticleManager" -> Particles.class;
            default -> throw new ClassNotFoundException(name);
        });
    }

    @Test void nestedGunDrawsRestoreTheExactPreviousOwnerAndEffectFlags() {
        var access = access();
        Object original = new Object(), local = new Object(), remote = new Object();
        Particles.currentEntity = original;
        Muzzle.isSelf = false;
        Shell.isSelf = true;
        try (var outer = access.begin(local, true)) {
            assertSame(local, Particles.currentEntity);
            assertTrue(Muzzle.isSelf);
            assertTrue(Shell.isSelf);
            try (var inner = access.begin(remote, false)) {
                assertSame(remote, Particles.currentEntity);
                assertFalse(Muzzle.isSelf);
                assertFalse(Shell.isSelf);
            }
            assertSame(local, Particles.currentEntity);
            assertTrue(Muzzle.isSelf);
            assertTrue(Shell.isSelf);
        }
        assertSame(original, Particles.currentEntity);
        assertFalse(Muzzle.isSelf);
        assertTrue(Shell.isSelf);
    }

    @Test void rendererExceptionsCannotLeakStateToTheNextEntity() {
        var access = access();
        Particles.currentEntity = null;
        Muzzle.isSelf = false;
        Shell.isSelf = false;
        assertThrows(IllegalStateException.class, () -> {
            try (var ignored = access.begin(new Object(), true)) {
                throw new IllegalStateException("render failed");
            }
        });
        assertNull(Particles.currentEntity);
        assertFalse(Muzzle.isSelf);
        assertFalse(Shell.isSelf);
    }

    @Test void absentTaczApisAreOptionalAndTheirAbsenceIsResolvedOnce() {
        var resolved = new java.util.HashMap<String, Integer>();
        var access = new TaczHeldItemCompat.Access(name -> {
            resolved.merge(name, 1, Integer::sum);
            throw new ClassNotFoundException(name);
        });
        for (int i = 0; i < 20; i++) assertSame(TaczHeldItemCompat.Scope.NONE, access.begin(new Object(), true));
        assertNull(access.gun);
        assertTrue(resolved.values().stream().allMatch(count -> count == 1));
    }
}
