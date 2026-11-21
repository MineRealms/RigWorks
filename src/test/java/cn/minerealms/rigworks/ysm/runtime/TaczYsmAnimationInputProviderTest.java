package cn.minerealms.rigworks.ysm.runtime;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class TaczYsmAnimationInputProviderTest {
    @Test void missingOptionalModIsResolvedOnlyOnceAndReturnsSharedEmptySnapshot() {
        AtomicInteger resolutions = new AtomicInteger();
        var provider = new TaczYsmAnimationInputProvider(new TaczYsmAnimationInputProvider.ReflectiveAccess(name -> {
            resolutions.incrementAndGet();
            throw new ClassNotFoundException(name);
        }));
        var first = provider.sampleState(new Object(), new Object(), false, false);
        for (int i = 0; i < 100; i++)
            assertSame(first, provider.sampleState(new Object(), new Object(), false, false));
        assertTrue(first.queries().isEmpty());
        assertEquals(1, resolutions.get());
        assertSame(first, provider.sample(null));
    }

    @Test void samplesRawCooldownsAndRetainsExistingQueriesAndFunctions() {
        var fixture = new Fixture();
        fixture.operator.aim = .6;
        fixture.operator.reload = 2.5;
        fixture.operator.melee = 12;
        fixture.operator.firing = 7;
        fixture.player.clientFiring = 14;
        var state = fixture.sample(false, false);
        assertEquals(1d, state.queries().get("q.tac_holding_gun"));
        assertEquals(.6d, state.queries().get("q.tac_aim_progress"));
        assertEquals(2.5d, state.queries().get("q.tac_reload_progress"));
        assertEquals(2.5d, state.queries().get("q.tac_reload_cooldown"));
        assertEquals(12d, state.queries().get("q.tac_melee_cooldown"));
        assertEquals(14d, state.queries().get("q.tac_fire_cooldown"));
        assertEquals(1d, state.queries().get("q.tac_reloading"));
        assertEquals(1d, state.queries().get("q.tac_melee"));
        assertEquals(1d, state.queries().get("q.tac_firing"));
        assertEquals("tacz:test", state.values().get("tac.gun_id"));
        assertEquals("rifle", state.values().get("tac.gun_type"));
        assertTrue(state.functions().contains("ctrl.tac_state('aim')"));
        assertTrue(state.functions().contains("ctrl.tac_gun('tacz:test')"));
        assertTrue(state.functions().contains("ctrl.tac_type('rifle')"));
        assertThrows(UnsupportedOperationException.class, () -> state.queries().put("q.tac_firing", 0d));
    }

    @Test void warmSamplesReusePositiveAndNegativeApiLookupsWithoutCachingLiveState() {
        var fixture = new Fixture();
        fixture.sample(false, false);
        for (int i = 0; i < 100; i++) fixture.sample(false, false);
        assertEquals(4, fixture.resolutions.size());
        assertTrue(fixture.resolutions.values().stream().allMatch(count -> count == 1));
        var lookup = fixture.access;
        assertSame(lookup.zeroArguments(FakeOperator.class, "getSynShootCoolDown"),
                lookup.zeroArguments(FakeOperator.class, "getSynShootCoolDown"));
        assertSame(lookup.zeroArguments(FakeOperator.class, "missingGetter"),
                lookup.zeroArguments(FakeOperator.class, "missingGetter"));
        assertSame(lookup.oneArgument(FakeGunApi.class, "getIGunOrNull", fixture.stack, true),
                lookup.oneArgument(FakeGunApi.class, "getIGunOrNull", new FakeStack(), true));
        fixture.operator.firing = 25;
        fixture.stack.id.index.type = "PISTOL";
        var state = fixture.sample(false, false);
        assertEquals(25d, state.queries().get("q.tac_fire_cooldown"));
        assertEquals("pistol", state.values().get("tac.gun_type"));
    }

    @Test void olderApiMissingClientCooldownAndOperatorGettersStillHoldsTheGun() {
        var fixture = new Fixture();
        fixture.types.remove("com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator");
        fixture.player.operator = new MinimalOperator();
        var state = fixture.sample(false, false);
        assertEquals(1d, state.queries().get("q.tac_holding_gun"));
        assertEquals(.25d, state.queries().get("q.tac_aim_progress"));
        assertEquals(0d, state.queries().get("q.tac_fire_cooldown"));
        assertEquals(0d, state.queries().get("q.tac_reload_cooldown"));
        for (int i = 0; i < 10; i++) fixture.sample(false, false);
        assertEquals(1, fixture.resolutions.get("com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator"));
    }

    @Test void absentOperatorAndGunMetadataRemainOptional() {
        var fixture = new Fixture();
        fixture.types.remove("com.tacz.guns.api.entity.IGunOperator");
        fixture.types.remove("com.tacz.guns.api.TimelessAPI");
        var state = fixture.sample(false, true);
        assertEquals(1d, state.queries().get("q.tac_holding_gun"));
        assertEquals(0d, state.queries().get("q.tac_aim_progress"));
        assertEquals("", state.values().get("tac.gun_type"));
        assertTrue(state.functions().contains("ctrl.tac_state('run')"));
    }

    @Test void cooldownQueriesKeepApiUnitsAndSanitizeNonFiniteValuesBeforeMerging() {
        var fixture = new Fixture();
        fixture.operator.firing = Double.NaN;
        fixture.operator.reload = Double.POSITIVE_INFINITY;
        fixture.operator.melee = Double.NEGATIVE_INFINITY;
        fixture.player.clientFiring = 1250;
        var state = fixture.sample(false, false);
        assertEquals(1250d, state.queries().get("q.tac_fire_cooldown"));
        assertEquals(0d, state.queries().get("q.tac_reload_cooldown"));
        assertEquals(0d, state.queries().get("q.tac_melee_cooldown"));
        assertEquals(0d, state.queries().get("q.tac_reloading"));
        fixture.operator.firing = -2;
        fixture.player.clientFiring = -5;
        state = fixture.sample(false, false);
        assertEquals(-2d, state.queries().get("q.tac_fire_cooldown"));
        assertEquals(0d, state.queries().get("q.tac_firing"));
    }

    @Test void optionalInvocationFailuresDoNotEscapeAndNextSampleCanRecover() {
        var fixture = new Fixture();
        fixture.operator.throwOnAim = true;
        assertTrue(fixture.sample(false, false).queries().isEmpty());
        fixture.operator.throwOnAim = false;
        assertEquals(1d, fixture.sample(false, false).queries().get("q.tac_holding_gun"));
        fixture.stack.gun = null;
        assertTrue(fixture.sample(false, false).queries().isEmpty());
    }

    private static final class Fixture {
        final FakeOperator operator = new FakeOperator();
        final FakePlayer player = new FakePlayer(operator);
        final FakeStack stack = new FakeStack();
        final Map<String, Class<?>> types = new HashMap<>(Map.of(
                "com.tacz.guns.api.item.IGun", FakeGunApi.class,
                "com.tacz.guns.api.TimelessAPI", FakeTimelessApi.class,
                "com.tacz.guns.api.entity.IGunOperator", FakeOperatorApi.class,
                "com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator", FakeClientOperator.class));
        final Map<String, Integer> resolutions = new HashMap<>();
        final TaczYsmAnimationInputProvider.ReflectiveAccess access = new TaczYsmAnimationInputProvider.ReflectiveAccess(name -> {
            resolutions.merge(name, 1, Integer::sum);
            Class<?> type = types.get(name);
            if (type == null) throw new ClassNotFoundException(name);
            return type;
        });
        final TaczYsmAnimationInputProvider provider = new TaczYsmAnimationInputProvider(access);

        YsmAnimationInputProvider.Snapshot sample(boolean swimming, boolean sprinting) {
            return provider.sampleState(player, stack, swimming, sprinting);
        }
    }

    public static class FakeStack {
        FakeGun gun = new FakeGun();
        final FakeId id = new FakeId();
    }
    public static class FakeId {
        final FakeIndex index = new FakeIndex();
        @Override public String toString() { return "tacz:test"; }
    }
    public static class FakeIndex {
        String type = "RIFLE";
        public String getType() { return type; }
    }
    public static class FakeGunApi {
        public static FakeGun getIGunOrNull(FakeStack stack) { return stack.gun; }
    }
    public static class FakeGun {
        public FakeId getGunId(FakeStack stack) { return stack.id; }
    }
    public static class FakeTimelessApi {
        public static Optional<FakeIndex> getCommonGunIndex(FakeId id) { return Optional.of(id.index); }
    }
    public static class FakeOperatorApi {
        public static Object fromLivingEntity(FakePlayer player) { return player.operator; }
    }
    public interface FakeClientOperator {}
    public static class FakePlayer implements FakeClientOperator {
        Object operator;
        double clientFiring;
        FakePlayer(Object operator) { this.operator = operator; }
        public double getClientShootCoolDown() { return clientFiring; }
    }
    public static class FakeOperator {
        double aim, reload, melee, firing;
        boolean throwOnAim;
        public double getSynAimingProgress() {
            if (throwOnAim) throw new IllegalStateException("optional API failure");
            return aim;
        }
        public FakeReloadState getSynReloadState() { return new FakeReloadState(reload); }
        public double getSynMeleeCoolDown() { return melee; }
        public double getSynShootCoolDown() { return firing; }
    }
    public static class FakeReloadState {
        final double countdown;
        FakeReloadState(double countdown) { this.countdown = countdown; }
        public double getCountDown() { return countdown; }
    }
    public static class MinimalOperator {
        public float getSynAimingProgress() { return .25f; }
    }
}
