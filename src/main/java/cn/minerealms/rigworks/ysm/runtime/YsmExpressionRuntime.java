/*
 * Originally derived from:
 * Source: https://github.com/YesSteveModel/YesSteveModel (Apache-2.0)
 *        and https://github.com/stevewongv/YesSteveModel (Apache-2.0)
 *
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy of the License at:
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Modified by MineRealms for RigWorks.
 * Changes: Cleanroom reimplementation / package adaptation / API changes for in-mod YSM decode.
 */
package cn.minerealms.rigworks.ysm.runtime;

import cn.minerealms.rigworks.character.core.PlayerActionState;
import cn.minerealms.rigworks.character.core.PlayerVisualInput;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class YsmExpressionRuntime {
    private YsmExpressionRuntime() {}

    public interface Context {
        double value(String name);

        boolean has(String name);

        default boolean matches(String token) { return false; }
    }

    public static final class MapContext implements Context {
        private final Map<String, Double> values;
        private final Set<String> tokens;

        public MapContext(Map<String, Double> values, Set<String> tokens) {
            this.values = new HashMap<>(values);
            this.tokens = new LinkedHashSet<>(tokens);
        }

        @Override public double value(String name) {
            Double value = values.get(name);
            if (value != null && Double.isFinite(value)) return value;
            if (!name.startsWith("q.") && !name.startsWith("v.")) {
                Double qualified = values.get("q." + name);
                if (qualified != null && Double.isFinite(qualified)) return qualified;
            }
            return 0;
        }

        @Override public boolean has(String name) { return values.containsKey(name) || values.containsKey("q." + name); }

        @Override public boolean matches(String token) { return tokens.contains(token); }

        public MapContext set(String name, double value) {
            values.put(name.startsWith("q.") || name.startsWith("v.") ? name : "v." + name, value);
            return this;
        }

        public MapContext setQuery(String name, double value) {
            values.put(name.startsWith("q.") ? name : "q." + name, Double.isFinite(value) ? value : 0d);
            return this;
        }

        public MapContext addTokens(Set<String> additional) {
            if (additional != null) tokens.addAll(additional);
            return this;
        }
    }

    public static MapContext context(PlayerVisualInput input, PlayerActionState state,
                                     YsmItemStateBridge.State item, double health, double maxHealth) {
        Map<String, Double> values = new HashMap<>();
        Set<String> tokens = new LinkedHashSet<>();
        PlayerActionState action = state == null ? PlayerActionState.DEFAULT : state;
        put(values, "q.is_sneaking", input.crouching());
        put(values, "q.is_swimming", input.swimming());
        put(values, "q.on_ground", input.onGround());
        put(values, "q.is_sprinting", input.sprinting());
        put(values, "q.is_jumping", input.jumping());
        put(values, "q.is_falling", input.falling());
        put(values, "q.is_flying", input.flying());
        put(values, "q.is_elytra", input.elytra());
        put(values, "q.is_riding", input.riding());
        put(values, "q.is_sleeping", input.sleeping());
        put(values, "q.is_dead", input.dead());
        put(values, "q.is_using_item", input.usingItem());
        put(values, "q.is_swinging", input.swinging());
        put(values, "q.is_crawling", input.crawling());
        put(values, "q.on_climbable", action.climbing());
        put(values, "q.in_water", action.inWater());
        put(values, "q.under_water", action.underWater());
        put(values, "q.in_lava", action.inLava());
        put(values, "q.riptide", action.riptide());
        put(values, "q.frozen", action.frozen());
        put(values, "q.left_hand", action.leftHeld());
        put(values, "q.right_hand", action.rightHeld());
        put(values, "q.hurt", action.hurtTicks());
        put(values, "q.health", health);
        put(values, "q.max_health", maxHealth);
        put(values, "q.speed", input.horizontalSpeed());
        put(values, "q.forward", input.forwardVelocity());
        put(values, "q.strafe", input.strafeVelocity());
        put(values, "q.vertical", input.verticalVelocity());
        put(values, "q.time", input.timeSeconds());
        put(values, "v.is_sneaking", input.crouching());
        put(values, "v.is_swimming", input.swimming());
        put(values, "v.on_ground", input.onGround());
        if (item != null) {
            tokens.addAll(item.states());
            put(values, "q.hold_bow", item.holdBow());
            put(values, "q.use_bow", item.useBow());
            put(values, "q.hold_sword", item.holdSword());
            put(values, "q.use_sword", item.useSword());
            put(values, "q.hold_crossbow", item.holdCrossbow());
            put(values, "q.use_crossbow", item.useCrossbow());
            put(values, "q.hold_shield", item.holdShield());
            put(values, "q.use_shield", item.useShield());
            put(values, "q.hold_offhand_bow", item.holdOffhandBow());
            put(values, "q.use_offhand_bow", item.useOffhandBow());
        }
        return new MapContext(values, tokens);
    }

    public static MapContext context(PlayerVisualInput input, PlayerActionState state, YsmItemStateBridge.State item) {
        return context(input, state, item, 20, 20);
    }

    public static double evaluate(String expression, Context context) {
        if (expression == null || expression.isBlank()) return 0;
        return new Parser(expression, context == null ? EMPTY : context).parse();
    }

    public static boolean condition(String expression, Context context) {
        return evaluate(expression, context) != 0;
    }

    private static final Context EMPTY = new Context() {
        @Override public double value(String name) { return 0; }
        @Override public boolean has(String name) { return false; }
    };

    private static void put(Map<String, Double> values, String key, boolean value) { values.put(key, value ? 1d : 0d); }

    private static void put(Map<String, Double> values, String key, double value) {
        values.put(key, Double.isFinite(value) ? value : 0d);
    }

    private static final class Parser {
        private final String source;
        private final Context context;
        private int pos;

        private Parser(String source, Context context) {
            this.source = source;
            this.context = context;
        }

        private double parse() {
            double result = ternary();
            skip();
            if (pos != source.length()) throw error("unexpected trailing input");
            return result;
        }

        private double ternary() {
            double test = or();
            skip();
            if (!consume('?')) return test;
            double yes = ternary();
            skip();
            if (!consume(':')) throw error("expected ':' in ternary");
            double no = ternary();
            return truth(test) ? yes : no;
        }

        private double or() {
            double value = and();
            while (true) {
                skip();
                if (!consume('|', '|')) return value;
                double right = and();
                value = truth(value) || truth(right) ? 1 : 0;
            }
        }

        private double and() {
            double value = equality();
            while (true) {
                skip();
                if (!consume('&', '&')) return value;
                double right = equality();
                value = truth(value) && truth(right) ? 1 : 0;
            }
        }

        private double equality() {
            double value = comparison();
            while (true) {
                skip();
                if (consume('=', '=')) value = comparison() == value ? 1 : 0;
                else if (consume('!', '=')) value = comparison() != value ? 1 : 0;
                else return value;
            }
        }

        private double comparison() {
            double value = additive();
            while (true) {
                skip();
                if (consume('<', '=')) value = value <= additive() ? 1 : 0;
                else if (consume('>', '=')) value = value >= additive() ? 1 : 0;
                else if (consume('<')) value = value < additive() ? 1 : 0;
                else if (consume('>')) value = value > additive() ? 1 : 0;
                else return value;
            }
        }

        private double additive() {
            double value = multiplicative();
            while (true) {
                skip();
                if (consume('+')) value += multiplicative();
                else if (consume('-')) value -= multiplicative();
                else return value;
            }
        }

        private double multiplicative() {
            double value = unary();
            while (true) {
                skip();
                if (consume('*')) value *= unary();
                else if (consume('/')) {
                    double divisor = unary();
                    value = divisor == 0 ? 0 : value / divisor;
                } else if (consume('%')) {
                    double divisor = unary();
                    value = divisor == 0 ? 0 : value % divisor;
                } else return value;
            }
        }

        private double unary() {
            skip();
            if (consume('!')) return truth(unary()) ? 0 : 1;
            if (consume('-')) return -unary();
            if (consume('+')) return unary();
            return primary();
        }

        private double primary() {
            skip();
            if (consume('(')) {
                double value = ternary();
                skip();
                if (!consume(')')) throw error("expected ')'");
                return value;
            }
            if (pos >= source.length()) throw error("unexpected end of expression");
            char ch = source.charAt(pos);
            if (ch == '"' || ch == '\'') return stringLiteral().isEmpty() ? 0 : 1;
            if (Character.isDigit(ch) || ch == '.') return number();
            String name = identifier();
            skip();
            if (consume('(')) return call(name);
            if (name.equalsIgnoreCase("true")) return 1;
            if (name.equalsIgnoreCase("false")) return 0;
            return context.value(name);
        }

        private double call(String name) {
            double[] args = new double[8];
            String[] strings = new String[8];
            int count = 0;
            skip();
            if (!consume(')')) {
                while (count < args.length) {
                    skip();
                    if (pos < source.length() && (source.charAt(pos) == '"' || source.charAt(pos) == '\'')) {
                        strings[count] = stringLiteral();
                        args[count] = strings[count].isEmpty() ? 0 : 1;
                    } else args[count] = ternary();
                    count++;
                    skip();
                    if (consume(')')) break;
                    if (!consume(',')) throw error("expected ',' or ')' in call");
                }
            }
            String key = name.toLowerCase(Locale.ROOT);
            if (key.startsWith("q.")) key = key.substring(2);
            if (key.equals("ctrl.hold") || key.equals("ctrl.use") || key.equals("hold") || key.equals("use")) {
                boolean use = key.endsWith("use");
                boolean ctrl = key.startsWith("ctrl.");
                String verb = use ? "use" : "hold";
                if (count == 1 && strings[0] != null) {
                    String value = strings[0];
                    if (context.matches("ctrl." + verb + "('" + value + "')")
                            || context.matches(verb + "('" + value + "')")) return 1;
                    return 0;
                }
                if (count >= 2 && strings[0] != null && strings[1] != null) {
                    String side = strings[0];
                    String value = strings[1];
                    if (context.matches("ctrl." + verb + "('" + side + "','" + value + "')")
                            || context.matches(verb + "('" + side + "','" + value + "')")) return 1;
                    if (ctrl) return 0;
                }
                return 0;
            }
            if (key.equals("ctrl.tac_hold_gun") || key.equals("tac_hold_gun"))
                return context.matches("ctrl.tac_hold_gun()") || context.matches("tac_hold_gun()") ? 1 : 0;
            if (matchesFunction(name, strings, args, count, context)) return 1;
            if (key.startsWith("math.")) key = key.substring(5);
            return switch (key) {
                case "sin" -> count > 0 ? Math.sin(args[0]) : 0;
                case "cos" -> count > 0 ? Math.cos(args[0]) : 0;
                case "min" -> min(args, count);
                case "max" -> max(args, count);
                case "clamp" -> count > 0 ? Math.min(Math.max(args[0], count > 1 ? args[1] : 0),
                        count > 2 ? args[2] : 1) : 0;
                default -> 0;
            };
        }

        private static boolean matchesFunction(String name, String[] strings, double[] args, int count, Context context) {
            StringBuilder token = new StringBuilder(name.toLowerCase(Locale.ROOT)).append('(');
            for (int i = 0; i < count; i++) {
                if (i > 0) token.append(',');
                if (strings[i] != null) token.append('\'').append(strings[i]).append('\'');
                else token.append(Double.toString(args[i]));
            }
            return context.matches(token.append(')').toString());
        }

        private static double min(double[] args, int count) {
            if (count == 0) return 0;
            double value = args[0];
            for (int i = 1; i < count; i++) value = Math.min(value, args[i]);
            return value;
        }

        private static double max(double[] args, int count) {
            if (count == 0) return 0;
            double value = args[0];
            for (int i = 1; i < count; i++) value = Math.max(value, args[i]);
            return value;
        }

        private double number() {
            int start = pos;
            while (pos < source.length() && (Character.isDigit(source.charAt(pos))
                    || source.charAt(pos) == '.' || source.charAt(pos) == 'e' || source.charAt(pos) == 'E'
                    || (source.charAt(pos) == '-' && pos > start && (source.charAt(pos - 1) == 'e' || source.charAt(pos - 1) == 'E')))) pos++;
            try { return Double.parseDouble(source.substring(start, pos)); }
            catch (NumberFormatException failure) { throw error("invalid number"); }
        }

        private String stringLiteral() {
            char quote = source.charAt(pos++);
            StringBuilder text = new StringBuilder();
            while (pos < source.length()) {
                char ch = source.charAt(pos++);
                if (ch == quote) return text.toString();
                if (ch == '\\' && pos < source.length()) ch = source.charAt(pos++);
                text.append(ch);
            }
            throw error("unterminated string literal");
        }

        private String identifier() {
            int start = pos;
            while (pos < source.length() && (Character.isLetterOrDigit(source.charAt(pos))
                    || source.charAt(pos) == '_' || source.charAt(pos) == '.')) pos++;
            if (start == pos) throw error("expected identifier");
            return source.substring(start, pos);
        }

        private void skip() { while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) pos++; }

        private boolean consume(char expected) {
            skip();
            if (pos < source.length() && source.charAt(pos) == expected) { pos++; return true; }
            return false;
        }

        private boolean consume(char first, char second) {
            skip();
            if (pos + 1 < source.length() && source.charAt(pos) == first && source.charAt(pos + 1) == second) {
                pos += 2;
                return true;
            }
            return false;
        }

        private static boolean truth(double value) { return value != 0 && Double.isFinite(value); }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("Molang expression error at " + pos + ": " + message);
        }
    }
}
