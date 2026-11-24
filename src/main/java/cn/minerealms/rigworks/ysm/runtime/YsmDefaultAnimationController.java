/*
 * Based on YSM/OpenYSM's player predicates and TaCZ action selection (Apache-2.0).
 * Modified by MineRealms for RigWorks; consumes immutable host input without optional-mod linkage.
 */
package cn.minerealms.rigworks.ysm.runtime;

import cn.minerealms.rigworks.character.core.PlayerActionState;
import cn.minerealms.rigworks.character.core.PlayerVisualInput;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** YSM's coded fallback for assets without authored controllers. Instances own one target rig. */
public final class YsmDefaultAnimationController {
    private static final double MOVEMENT_THRESHOLD = 0.05;
    private enum Motion {
        DEATH("death"), RIPTIDE("riptide"), SLEEP("sleep"), SWIM("swim"), CLIMB("climb"), CLIMBING("climbing"),
        LADDER_UP("ladder_up"), LADDER_STILL("ladder_stillness"), LADDER_DOWN("ladder_down"), FLY("fly"),
        ELYTRA("elytra_fly"), SWIM_STAND("swim_stand"), ATTACKED("attacked"), JUMP("jump"), SNEAK("sneak"),
        SNEAKING("sneaking"), RUN("run"), WALK("walk"), IDLE("idle"), RIDE("ride"), SIT("sit"), BOAT("boat");
        final String name;
        Motion(String name) { this.name = name; }
    }
    private enum GunAction {
        HOLD("tac:hold"), AIM("tac:aim"), RUN("tac:run"), CLIMB("tac:climb"), CLIMBING("tac:climbing"),
        RELOAD("tac:reload"), RELOAD_EMPTY("tac:reload_empty"), DRAW("tac:draw"), BOLT("tac:bolt"),
        MELEE("tac:melee"), AIM_FIRE("tac:aim:fire"), HOLD_FIRE("tac:hold:fire"),
        CLIMBING_FIRE("tac:climbing:fire");
        final String prefix;
        GunAction(String prefix) { this.prefix = prefix; }
    }
    private enum HandAction {
        HOLD_MAIN("hold_mainhand", false, false), HOLD_OFF("hold_offhand", true, false),
        USE_MAIN("use_mainhand", false, true), USE_OFF("use_offhand", true, true),
        SWING_MAIN("swing", false, false), SWING_OFF("swing_offhand", true, false);
        final String prefix;
        final boolean offhand;
        final boolean use;
        HandAction(String prefix, boolean offhand, boolean use) {
            this.prefix = prefix; this.offhand = offhand; this.use = use;
        }
    }
    private record Clip(String category, String name, double duration, YsmControllerRuntime.AnimationSink sink) {}
    private record Rule(Clip clip, char kind, String value, String handToken, String plainHandToken) {
        int matchRank(YsmItemStateBridge.HeldItem item, String classification,
                      YsmExpressionRuntime.Context context) {
            if (item != null) {
                return switch (kind) {
                    case '$' -> value.equals(item.id()) ? 0 : -1;
                    case '#' -> item.tags().contains(value) ? 1 : -1;
                    default -> value.equals("empty") ? item.empty() ? 2 : -1
                            : value.equals("charged_crossbow") ? item.charged() && classification.equals("crossbow") ? 2 : -1
                            : value.equals(classification) ? 2
                            : !value.equals("none") && value.equals(item.useAnimation()) ? 3 : -1;
                };
            }
            return context != null && (context.matches(handToken) || context.matches(plainHandToken)) ? 2 : -1;
        }
    }
    private static final class GunCandidates {
        final Map<String, Clip> specific = new HashMap<>();
        final Map<String, Clip> types = new HashMap<>();
        Clip generic;

        Clip select(String id, String type) {
            Clip explicit = specific.get(id);
            if (explicit != null) return explicit;
            explicit = types.get(type);
            if (explicit != null) return explicit;
            return types.getOrDefault("rifle", generic);
        }
    }
    private final class Slot {
        final String id;
        final String name;
        Clip clip;
        double expires = Double.POSITIVE_INFINITY;

        Slot(String id) {
            this.id = "ysm:default:" + targetCategory + ":" + id;
            this.name = id;
        }
        void show(Clip next) {
            if (overriddenSlots.contains(name) || next != null
                    && overriddenSlots.contains(next.category() + "/" + name)) next = null;
            if (clip == next) return;
            clear();
            clip = next;
            if (next != null) next.sink().showLayer(id, next.name(), 1f);
        }
        void trigger(Clip next, double time) {
            clear();
            show(next);
            if (next != null) expires = time + next.duration();
        }
        void expire(double time) { if (time >= expires) clear(); }
        void clear() {
            if (clip != null) clip.sink().hideLayer(id);
            clip = null;
            expires = Double.POSITIVE_INFINITY;
        }
    }

    private final String targetCategory;
    private final Set<String> overriddenSlots;
    private final Clip[] motion;
    private final Clip[] gunMotion;
    private final GunCandidates[] gun;
    private final Rule[][] handRules;
    private final Clip[] handFallback;
    private final Clip fishingMain;
    private final Clip chargedMain, chargedOff;
    private final Slot base, holdMain, holdOff, use, swing, gunOnce;
    private final Clip[] preParallel = new Clip[8], parallel = new Clip[8];
    private final Slot[] preParallelSlots = new Slot[8], parallelSlots = new Slot[8];
    private boolean previousSwinging;
    private long previousSwingGeneration = -1;
    private int previousHurt;
    private double previousReload, previousMelee, previousFire, previousDraw;
    private double previousReloadGeneration = -1, previousMeleeGeneration = -1;
    private double previousFireGeneration = -1, previousDrawGeneration = -1;
    private boolean previousBolt;
    private String previousGunId = "", previousGunType = "";

    public YsmDefaultAnimationController(List<YsmModelSource.YsmAnimationView> clips,
                                         Function<String, YsmControllerRuntime.AnimationSink> sinks) {
        this(clips, sinks, "main");
    }

    /** Use a separate arm instance for a first-person rig; arm clips never leak into the body. */
    public YsmDefaultAnimationController(List<YsmModelSource.YsmAnimationView> clips,
                                         Function<String, YsmControllerRuntime.AnimationSink> sinks,
                                         String targetCategory) {
        this(clips, sinks, targetCategory, Set.of());
    }

    public YsmDefaultAnimationController(List<YsmModelSource.YsmAnimationView> clips,
                                         Function<String, YsmControllerRuntime.AnimationSink> sinks,
                                         String targetCategory, Set<String> overriddenSlots) {
        this.targetCategory = Objects.requireNonNull(targetCategory, "target category");
        this.overriddenSlots = Set.copyOf(overriddenSlots);
        Objects.requireNonNull(sinks, "animation sinks");
        Map<String, Clip> primary = new LinkedHashMap<>();
        Map<String, Clip> tactical = new LinkedHashMap<>();
        Map<String, YsmControllerRuntime.AnimationSink> sinkByCategory = new HashMap<>();
        for (YsmModelSource.YsmAnimationView view : clips) {
            String category = view.category();
            boolean target = targetCategory.equals(category);
            if (!target && (!"main".equals(targetCategory) || !"tac".equals(category))) continue;
            YsmControllerRuntime.AnimationSink sink = sinkByCategory.computeIfAbsent(category, sinks);
            if (sink == null) continue;
            double duration = view.durationSeconds();
            Clip clip = new Clip(category, view.name(), Double.isFinite(duration) && duration > 0 ? duration : 0, sink);
            (target ? primary : tactical).putIfAbsent(clip.name(), clip);
        }
        motion = new Clip[Motion.values().length];
        gunMotion = new Clip[motion.length];
        for (Motion state : Motion.values()) {
            motion[state.ordinal()] = primary.get(state.name);
            String name = "tac:" + state.name;
            gunMotion[state.ordinal()] = tactical.getOrDefault(name, primary.get(name));
        }
        gun = new GunCandidates[GunAction.values().length];
        for (GunAction action : GunAction.values()) {
            GunCandidates candidates = new GunCandidates();
            indexGun(candidates, primary, action.prefix);
            indexGun(candidates, tactical, action.prefix);
            gun[action.ordinal()] = candidates;
        }
        handRules = new Rule[HandAction.values().length][];
        handFallback = new Clip[handRules.length];
        for (HandAction action : HandAction.values()) {
            ArrayList<Rule> rules = new ArrayList<>();
            for (Clip clip : primary.values()) {
                String name = clip.name();
                if (!name.startsWith(action.prefix) || name.length() <= action.prefix.length()) continue;
                char kind = name.charAt(action.prefix.length());
                if (kind != '$' && kind != '#' && kind != ':') continue;
                String value = name.substring(action.prefix.length() + 1);
                String tokenValue = value;
                String function = action.use ? "use" : "hold";
                rules.add(new Rule(clip, kind, value,
                        "ctrl." + function + "('" + (action.offhand ? "offhand" : "mainhand") + "','" + tokenValue + "')",
                        function + "('" + (action.offhand ? "offhand" : "mainhand") + "','" + tokenValue + "')"));
            }
            rules.sort(Comparator.comparingInt((Rule rule) -> rule.kind() == '$' ? 0 : rule.kind() == '#' ? 1 : 2));
            handRules[action.ordinal()] = rules.toArray(Rule[]::new);
            handFallback[action.ordinal()] = primary.get(action.prefix);
        }
        handFallback[HandAction.SWING_MAIN.ordinal()] = primary.getOrDefault("swing_hand", primary.get("swing"));
        fishingMain = primary.get("hold_mainhand:fishing");
        chargedMain = primary.get("hold_mainhand:charged_crossbow");
        chargedOff = primary.get("hold_offhand:charged_crossbow");
        base = new Slot("main"); holdMain = new Slot("hold_mainhand"); holdOff = new Slot("hold_offhand");
        use = new Slot("use"); swing = new Slot("swing"); gunOnce = new Slot("gun_action");
        for (int i = 0; i < 8; i++) {
            preParallel[i] = primary.get("pre_parallel" + i);
            parallel[i] = primary.get("parallel" + i);
            preParallelSlots[i] = new Slot("pre_parallel" + i);
            parallelSlots[i] = new Slot("parallel" + i);
        }
    }

    private static void indexGun(GunCandidates result, Map<String, Clip> clips, String prefix) {
        Clip generic = clips.get(prefix);
        if (generic != null) result.generic = generic;
        String typedPrefix = prefix + ":";
        String specificPrefix = prefix + "$";
        for (Clip clip : clips.values()) {
            if (clip.name().startsWith(specificPrefix))
                result.specific.put(clip.name().substring(specificPrefix.length()), clip);
            else if (clip.name().startsWith(typedPrefix)) {
                String type = clip.name().substring(typedPrefix.length());
                if (!type.contains(":")) result.types.put(type, clip);
            }
        }
    }

    public void update(PlayerVisualInput input, YsmExpressionRuntime.Context context, Map<String, String> optionalValues) {
        update(input, context, optionalValues, null, null);
    }

    /** Item snapshots are optional; supplying them enables full item-id and tag override precedence. */
    public void update(PlayerVisualInput input, YsmExpressionRuntime.Context context, Map<String, String> optionalValues,
                       YsmItemStateBridge.HeldItem mainHand, YsmItemStateBridge.HeldItem offhand) {
        Objects.requireNonNull(input, "player input");
        for (int i = 0; i < 8; i++) preParallelSlots[i].show(preParallel[i]);
        boolean holdingGun = query(context, "q.tac_holding_gun") > 0;
        Motion selected = movement(input, optionalValues);
        Clip nextBase = holdingGun ? gunMotion[selected.ordinal()] : null;
        if (nextBase == null) nextBase = motion[selected.ordinal()];
        if (selected == Motion.ATTACKED && input.actionState().hurtTicks() > previousHurt)
            base.trigger(nextBase, input.timeSeconds());
        else base.show(nextBase);
        previousHurt = input.actionState().hurtTicks();

        double time = input.timeSeconds();
        swing.expire(time);
        gunOnce.expire(time);
        if (input.dead() || input.sleeping()) {
            holdMain.clear(); holdOff.clear(); use.clear(); swing.clear(); gunOnce.clear();
            rememberSignals(input, context);
            for (int i = 0; i < 8; i++) parallelSlots[i].show(parallel[i]);
            return;
        }
        boolean useOffhand = query(context, "q.using_offhand") > 0;
        boolean swingOffhand = query(context, "q.swinging_offhand") > 0;
        String mainKind = classify(mainHand), offKind = classify(offhand);
        if (input.usingItem() && (!holdingGun || useOffhand)) use.show(hand(useOffhand ? HandAction.USE_OFF : HandAction.USE_MAIN,
                useOffhand ? offhand : mainHand, useOffhand ? offKind : mainKind, context));
        else use.clear();

        PlayerVisualInput.SwingAction acceptedSwing = input.swingAction();
        boolean swinging = acceptedSwing == null ? input.swinging() : acceptedSwing.active();
        boolean retrigger = acceptedSwing == null ? input.swinging() && !previousSwinging
                : acceptedSwing.active() && acceptedSwing.generation() != previousSwingGeneration;
        if (retrigger && (!holdingGun || swingOffhand)) swing.trigger(hand(swingOffhand ? HandAction.SWING_OFF : HandAction.SWING_MAIN,
                swingOffhand ? offhand : mainHand, swingOffhand ? offKind : mainKind, context), time);
        else if (holdingGun && !swingOffhand) swing.clear();

        if (holdingGun) updateGun(input, context, optionalValues);
        else { gunOnce.clear(); previousGunId = previousGunType = ""; holdMain.show((input.usingItem() && !useOffhand || swinging && !swingOffhand)
                ? null : mainHand != null && mainHand.charged() && chargedMain != null ? chargedMain
                : query(context, "q.fishing") > 0 && fishingMain != null ? fishingMain
                : hand(HandAction.HOLD_MAIN, mainHand, mainKind, context)); }
        holdOff.show((input.usingItem() && useOffhand || swinging && swingOffhand)
                ? null : offhand != null && offhand.charged() && chargedOff != null ? chargedOff
                : hand(HandAction.HOLD_OFF, offhand, offKind, context));
        rememberSignals(input, context);
        for (int i = 0; i < 8; i++) parallelSlots[i].show(parallel[i]);
    }

    private void updateGun(PlayerVisualInput input, YsmExpressionRuntime.Context context, Map<String, String> values) {
        String id = value(values, "tac.gun_id"), type = value(values, "tac.gun_type");
        if (!id.equals(previousGunId) || !type.equals(previousGunType)) {
            gunOnce.clear();
            previousReload = previousMelee = previousFire = previousDraw = 0;
            previousBolt = false;
            previousGunId = id; previousGunType = type;
        }
        boolean crawling = input.crawling();
        boolean aiming = query(context, "q.tac_aiming") > 0 || query(context, "q.tac_aim_progress") > 0;
        GunAction hold = crawling ? input.horizontalSpeed() > MOVEMENT_THRESHOLD ? GunAction.CLIMB : GunAction.CLIMBING
                : aiming ? GunAction.AIM : input.onGround() && input.sprinting() ? GunAction.RUN : GunAction.HOLD;
        Clip held = gun[hold.ordinal()].select(id, type);
        holdMain.show(held == null ? gun[GunAction.HOLD.ordinal()].select(id, type) : held);
        double reload = reload(context), melee = melee(context), fire = fire(context);
        double draw = Math.max(query(context, "q.tac_drawing"), query(context, "q.tac_draw_cooldown"));
        boolean bolt = query(context, "q.tac_bolting") > 0;
        double reloadGeneration = query(context, "q.tac_reload_generation");
        double meleeGeneration = query(context, "q.tac_melee_generation");
        double fireGeneration = query(context, "q.tac_fire_generation");
        double drawGeneration = query(context, "q.tac_draw_generation");
        if (triggered(reload, previousReload, reloadGeneration, previousReloadGeneration)) {
            Clip reloading = query(context, "q.tac_reload_empty") > 0
                    ? gun[GunAction.RELOAD_EMPTY.ordinal()].select(id, type) : null;
            gunOnce.trigger(reloading == null ? gun[GunAction.RELOAD.ordinal()].select(id, type) : reloading, input.timeSeconds());
        } else if (reload <= 0 && triggered(melee, previousMelee, meleeGeneration, previousMeleeGeneration))
            gunOnce.trigger(gun[GunAction.MELEE.ordinal()].select(id, type), input.timeSeconds());
        else if (reload <= 0 && melee <= 0
                && triggered(fire, previousFire, fireGeneration, previousFireGeneration)) {
            GunAction shot = crawling && input.horizontalSpeed() <= MOVEMENT_THRESHOLD ? GunAction.CLIMBING_FIRE
                    : aiming ? GunAction.AIM_FIRE : GunAction.HOLD_FIRE;
            Clip firing = gun[shot.ordinal()].select(id, type);
            gunOnce.trigger(firing == null ? gun[GunAction.HOLD_FIRE.ordinal()].select(id, type) : firing, input.timeSeconds());
        } else if (reload <= 0 && melee <= 0 && triggered(draw, previousDraw, drawGeneration, previousDrawGeneration))
            triggerIfPresent(GunAction.DRAW, id, type, input.timeSeconds());
        else if (reload <= 0 && melee <= 0 && bolt && !previousBolt)
            triggerIfPresent(GunAction.BOLT, id, type, input.timeSeconds());
    }

    private void triggerIfPresent(GunAction action, String id, String type, double time) {
        Clip clip = gun[action.ordinal()].select(id, type);
        if (clip != null) gunOnce.trigger(clip, time);
    }

    private static boolean triggered(double signal, double previousSignal, double generation, double previousGeneration) {
        // Accepted events are authoritative once observed. A later sync of the same cooldown
        // must not restart that event a second time. Older/remote APIs retain edge fallback.
        return generation > 0 && previousGeneration >= 0 ? generation != previousGeneration : signal > 0
                && (previousSignal <= 0 || signal > previousSignal);
    }

    private Clip hand(HandAction action, YsmItemStateBridge.HeldItem item, String kind,
                      YsmExpressionRuntime.Context context) {
        Rule[] rules = handRules[action.ordinal()];
        Clip selected = handFallback[action.ordinal()];
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < rules.length; i++) {
            int rank = rules[i].matchRank(item, kind, context);
            if (rank >= 0 && rank < bestRank) {
                bestRank = rank;
                selected = rules[i].clip();
                if (rank <= 1) break;
            }
        }
        return selected;
    }

    private static Motion movement(PlayerVisualInput input, Map<String, String> values) {
        PlayerActionState action = input.actionState();
        boolean moving = input.horizontalSpeed() > MOVEMENT_THRESHOLD;
        if (input.dead()) return Motion.DEATH;
        if (action.riptide()) return Motion.RIPTIDE;
        if (input.sleeping()) return Motion.SLEEP;
        if (input.riding()) return "boat".equals(value(values, "vehicle.type")) ? Motion.BOAT
                : "saddleable".equals(value(values, "vehicle.type")) ? Motion.RIDE : Motion.SIT;
        if (input.swimming()) return Motion.SWIM;
        if (input.crawling()) return moving ? Motion.CLIMB : Motion.CLIMBING;
        if (action.climbing()) return input.verticalVelocity() > 0 ? Motion.LADDER_UP
                : input.verticalVelocity() < 0 ? Motion.LADDER_DOWN : Motion.LADDER_STILL;
        if (input.flying()) return Motion.FLY;
        if (input.elytra()) return Motion.ELYTRA;
        if (action.inWater() && !input.onGround()) return Motion.SWIM_STAND;
        if (action.hurtTicks() > 0) return Motion.ATTACKED;
        if (!input.onGround() && !action.inWater()) return Motion.JUMP;
        if (input.onGround() && input.crouching()) return moving ? Motion.SNEAK : Motion.SNEAKING;
        if (input.onGround() && input.sprinting()) return Motion.RUN;
        if (input.onGround() && moving) return Motion.WALK;
        return Motion.IDLE;
    }

    private static String classify(YsmItemStateBridge.HeldItem item) {
        return YsmItemStateBridge.kindOf(item);
    }

    private void rememberSignals(PlayerVisualInput input, YsmExpressionRuntime.Context context) {
        previousSwinging = input.swinging();
        if (input.swingAction() != null) previousSwingGeneration = input.swingAction().generation();
        previousReload = reload(context); previousMelee = melee(context); previousFire = fire(context);
        previousDraw = Math.max(query(context, "q.tac_drawing"), query(context, "q.tac_draw_cooldown"));
        previousBolt = query(context, "q.tac_bolting") > 0;
        previousReloadGeneration = query(context, "q.tac_reload_generation");
        previousMeleeGeneration = query(context, "q.tac_melee_generation");
        previousFireGeneration = query(context, "q.tac_fire_generation");
        previousDrawGeneration = query(context, "q.tac_draw_generation");
    }
    private static double reload(YsmExpressionRuntime.Context context) {
        return Math.max(query(context, "q.tac_reloading"), query(context, "q.tac_reload_progress"));
    }
    private static double melee(YsmExpressionRuntime.Context context) {
        return Math.max(query(context, "q.tac_melee"), query(context, "q.tac_melee_cooldown"));
    }
    private static double fire(YsmExpressionRuntime.Context context) {
        return Math.max(query(context, "q.tac_firing"), query(context, "q.tac_fire_cooldown"));
    }
    private static double query(YsmExpressionRuntime.Context context, String name) {
        if (context == null) return 0;
        double value = context.value(name);
        return Double.isFinite(value) ? value : 0;
    }
    private static String value(Map<String, String> values, String name) {
        if (values == null) return "";
        String value = values.get(name);
        return value == null ? "" : value;
    }

    static String controllerSlot(String name) {
        if (name == null) return "";
        String slot = name.toLowerCase(java.util.Locale.ROOT);
        if (slot.startsWith("player_ctrl_")) slot = slot.substring(12);
        else if (slot.startsWith("player.")) slot = slot.substring(7);
        if (slot.startsWith("pre_parallel_")) slot = "pre_parallel" + slot.substring(13);
        else if (slot.startsWith("parallel_")) slot = "parallel" + slot.substring(9);
        return slot;
    }

    static int slotOrder(String name) {
        String slot = controllerSlot(name);
        if (slot.startsWith("pre_parallel")) return 100;
        if (slot.startsWith("parallel")) return 1000;
        return switch (slot) {
            case "vehicle" -> 200;
            case "pre_main" -> 250;
            case "main" -> 300;
            case "post_main" -> 350;
            case "pre_hold" -> 400;
            case "hold_offhand" -> 450;
            case "hold_mainhand" -> 500;
            case "post_hold" -> 550;
            case "pre_swing" -> 600;
            case "swing", "gun_action" -> 650;
            case "post_swing" -> 700;
            case "pre_use" -> 750;
            case "use" -> 800;
            case "post_use" -> 850;
            case "passenger", "cap" -> 900;
            default -> 950;
        };
    }

    public void reset() {
        base.clear(); holdMain.clear(); holdOff.clear(); use.clear(); swing.clear(); gunOnce.clear();
        for (int i = 0; i < 8; i++) { preParallelSlots[i].clear(); parallelSlots[i].clear(); }
        previousSwinging = false; previousSwingGeneration = -1; previousHurt = 0;
        previousReload = previousMelee = previousFire = previousDraw = 0;
        previousReloadGeneration = previousMeleeGeneration = previousFireGeneration = previousDrawGeneration = -1;
        previousBolt = false;
        previousGunId = previousGunType = "";
    }
}
