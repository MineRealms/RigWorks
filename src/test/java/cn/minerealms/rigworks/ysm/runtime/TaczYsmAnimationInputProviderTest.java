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
        fixture.operator.draw = 75;
        fixture.operator.bolt = fixture.operator.aiming = true;
        fixture.operator.sprint = .3;
        fixture.operator.reloadStage = "EMPTY_RELOAD_FINISHING";
        fixture.player.clientFiring = 14;
        var state = fixture.sample(false, false);
        assertEquals(1d, state.queries().get("q.tac_holding_gun"));
        assertEquals(.6d, state.queries().get("q.tac_aim_progress"));
        assertEquals(2.5d, state.queries().get("q.tac_reload_progress"));
        assertEquals(2.5d, state.queries().get("q.tac_reload_cooldown"));
        assertEquals(12d, state.queries().get("q.tac_melee_cooldown"));
        assertEquals(14d, state.queries().get("q.tac_fire_cooldown"));
        assertEquals(75d, state.queries().get("q.tac_draw_cooldown"));
        assertEquals(.3d, state.queries().get("q.tac_sprint_time"));
        assertEquals(1d, state.queries().get("q.tac_bolting"));
        assertEquals(1d, state.queries().get("q.tac_is_aiming"));
        assertEquals(1d, state.queries().get("q.tac_reload_empty"));
        assertEquals(1d, state.queries().get("q.tac_reload_finishing"));
        assertEquals(1d, state.queries().get("q.tac_reloading"));
        assertEquals(1d, state.queries().get("q.tac_melee"));
        assertEquals(1d, state.queries().get("q.tac_firing"));
        assertEquals("tacz:test", state.values().get("tac.gun_id"));
        assertEquals("rifle", state.values().get("tac.gun_type"));
        assertEquals("empty_reload_finishing", state.values().get("tac.reload_stage"));
        assertTrue(state.functions().contains("ctrl.tac_state('aim')"));
        assertTrue(state.functions().contains("ctrl.tac_state('reload')"));
        assertTrue(state.functions().contains("ctrl.tac_state('melee')"));
        assertTrue(state.functions().contains("ctrl.tac_state('fire')"));
        assertTrue(state.functions().contains("ctrl.tac_state('draw')"));
        assertTrue(state.functions().contains("ctrl.tac_state('bolt')"));
        assertTrue(state.functions().contains("ctrl.tac_gun('tacz:test')"));
        assertTrue(state.functions().contains("ctrl.tac_type('rifle')"));
        assertThrows(UnsupportedOperationException.class, () -> state.queries().put("q.tac_firing", 0d));
    }

    @Test void acceptedEventsKeepSeparateGenerationsForEveryActionAndOwner() {
        var fixture = new Fixture();
        var other = new Fixture();
        var first = fixture.sample(false, false);
        assertEquals(0d, first.queries().get("q.tac_fire_generation"));
        TaczActionSignals.record(fixture.player, "fire");
        TaczActionSignals.record(fixture.player, "fire");
        TaczActionSignals.record(fixture.player, "reload");
        TaczActionSignals.record(fixture.player, "melee");
        TaczActionSignals.record(fixture.player, "draw");
        var state = fixture.sample(false, false);
        assertEquals(2d, state.queries().get("q.tac_fire_generation"));
        assertEquals(1d, state.queries().get("q.tac_reload_generation"));
        assertEquals(1d, state.queries().get("q.tac_melee_generation"));
        assertEquals(1d, state.queries().get("q.tac_draw_generation"));
        assertEquals(0d, state.queries().get("q.tac_fire_cooldown"), "events do not invent cooldowns");
        assertEquals(2d, fixture.sample(false, false).queries().get("q.tac_fire_generation"));
        assertEquals(0d, other.sample(false, false).queries().get("q.tac_fire_generation"));
        assertEquals(0d, first.queries().get("q.tac_fire_generation"), "published snapshots stay immutable");
    }

    @Test void localPredictionOverridesSyncedAimAndExposesGunAndChargeData() {
        var fixture = new Fixture();
        fixture.operator.aim = .2;
        fixture.player.clientAim = .85;
        fixture.player.clientAimIntent = true;
        fixture.player.charge = .7;
        fixture.player.charging = true;
        fixture.stack.gun.ammo = 17;
        fixture.stack.gun.barrel = fixture.stack.gun.inventoryAmmo = fixture.stack.gun.overheated = true;
        fixture.stack.gun.fireMode = "BURST";
        var state = fixture.sample(false, false);
        assertEquals(.85d, state.queries().get("q.tac_aim_progress"));
        assertEquals(1d, state.queries().get("q.tac_is_aiming"));
        assertEquals(.7d, state.queries().get("q.tac_charge_progress"));
        assertEquals(1d, state.queries().get("q.tac_charging"));
        assertEquals(17d, state.queries().get("q.tac_ammo"));
        assertEquals(1d, state.queries().get("q.tac_ammo_known"));
        assertEquals(1d, state.queries().get("q.tac_barrel_loaded"));
        assertEquals(1d, state.queries().get("q.tac_inventory_ammo"));
        assertEquals(1d, state.queries().get("q.tac_overheated"));
        assertEquals("burst", state.values().get("tac.fire_mode"));
        assertTrue(state.functions().contains("ctrl.tac_fire_mode('burst')"));
        assertTrue(state.functions().contains("ctrl.tac_state('charge')"));
        assertSame(fixture.access.oneArgument(FakePlayer.class, "getClientAimingProgress", 1f, false),
                fixture.access.oneArgument(FakePlayer.class, "getClientAimingProgress", 0f, false));
    }

    @Test void swimmingAndTaczCrawlingHaveSeparatePredicates() {
        var fixture = new Fixture();
        var state = fixture.sample(true, false);
        assertTrue(state.functions().contains("ctrl.tac_state('swim')"));
        assertFalse(state.functions().contains("ctrl.tac_state('climb')"));
        state = fixture.provider.sampleState(fixture.player, fixture.stack, false, false, true);
        assertEquals(1d, state.queries().get("q.tac_crawling"));
        assertTrue(state.functions().contains("ctrl.tac_state('climb')"));
        assertFalse(state.functions().contains("ctrl.tac_state('swim')"));
        assertFalse(state.functions().contains("ctrl.tac_state('hold')"));
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
        assertEquals(0d, state.queries().get("q.tac_draw_cooldown"));
        assertEquals(0d, state.queries().get("q.tac_bolting"));
        assertEquals("", state.values().get("tac.reload_stage"));
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
        int ammo;
        boolean barrel, inventoryAmmo, overheated;
        String fireMode = "SEMI";
        public FakeId getGunId(FakeStack stack) { return stack.id; }
        public int getCurrentAmmoCount(FakeStack stack) { return ammo; }
        public boolean hasBulletInBarrel(FakeStack stack) { return barrel; }
        public boolean useInventoryAmmo(FakeStack stack) { return inventoryAmmo; }
        public boolean isOverheatLocked(FakeStack stack) { return overheated; }
        public String getFireMode(FakeStack stack) { return fireMode; }
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
        double clientFiring, charge;
        Double clientAim;
        Boolean clientAimIntent;
        boolean charging;
        FakePlayer(Object operator) { this.operator = operator; }
        public double getClientShootCoolDown() { return clientFiring; }
        public Double getClientAimingProgress(float partialTick) { return clientAim; }
        public Boolean isAim() { return clientAimIntent; }
        public double getChargeProgress() { return charge; }
        public boolean isCharging() { return charging; }
    }
    public static class FakeOperator {
        double aim, reload, melee, firing, draw, sprint;
        boolean throwOnAim, bolt, aiming;
        String reloadStage = "NOT_RELOADING";
        public double getSynAimingProgress() {
            if (throwOnAim) throw new IllegalStateException("optional API failure");
            return aim;
        }
        public FakeReloadState getSynReloadState() { return new FakeReloadState(reload, reloadStage); }
        public double getSynMeleeCoolDown() { return melee; }
        public double getSynShootCoolDown() { return firing; }
        public double getSynDrawCoolDown() { return draw; }
        public boolean getSynIsBolting() { return bolt; }
        public boolean getSynIsAiming() { return aiming; }
        public double getSynSprintTime() { return sprint; }
    }
    public static class FakeReloadState {
        final double countdown;
        final String stage;
        FakeReloadState(double countdown, String stage) { this.countdown = countdown; this.stage = stage; }
        public double getCountDown() { return countdown; }
        public String getStateType() { return stage; }
    }
    public static class MinimalOperator {
        public float getSynAimingProgress() { return .25f; }
    }
}
