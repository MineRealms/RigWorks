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

        default void setVariable(String name, double value) {}

        default boolean matches(String token) { return false; }

        /** Conservative by default; token-backed contexts can skip absent function families. */
        default boolean mayMatchFunction(String name) { return true; }
    }

    public static final class MapContext implements Context {
        private final Map<String, Double> values;
        private final Set<String> tokens;
        private final Set<String> functionNames = new HashSet<>();

        public MapContext(Map<String, Double> values, Set<String> tokens) {
            this.values = new HashMap<>();
            values.forEach((name, value) -> this.values.put(normalizeName(name), value));
            this.tokens = new LinkedHashSet<>(tokens);
            indexFunctions(tokens);
        }

        @Override public double value(String name) {
            name = normalizeName(name);
            return valuePrepared(name, !name.startsWith("q.") && !name.startsWith("v.") ? "q." + name : null);
        }

        private double valuePrepared(String name, String qualifiedFallback) {
            Double value = values.get(name);
            if (value != null && Double.isFinite(value)) return value;
            if (qualifiedFallback != null) {
                Double qualified = values.get(qualifiedFallback);
                if (qualified != null && Double.isFinite(qualified)) return qualified;
            }
            return 0;
        }

        @Override public boolean has(String name) {
            name = normalizeName(name);
            return values.containsKey(name) || values.containsKey("q." + name);
        }

        @Override public boolean matches(String token) { return tokens.contains(token); }

        @Override public boolean mayMatchFunction(String name) { return functionNames.contains(name); }

        @Override public void setVariable(String name, double value) {
            values.put(normalizeName(name), Double.isFinite(value) ? value : 0d);
        }

        private void indexFunctions(Set<String> additional) {
            for (String token : additional) {
                if (token == null) continue;
                int open = token.indexOf('(');
                if (open > 0) functionNames.add(token.substring(0, open));
            }
        }

        public MapContext set(String name, double value) {
            name = normalizeName(name);
            values.put(name.startsWith("q.") || name.startsWith("v.") || name.startsWith("t.") ? name : "v." + name,
                    Double.isFinite(value) ? value : 0d);
            return this;
        }

        public MapContext setQuery(String name, double value) {
            name = normalizeName(name);
            values.put(name.startsWith("q.") ? name : "q." + name, Double.isFinite(value) ? value : 0d);
            return this;
        }

        public MapContext addTokens(Set<String> additional) {
            if (additional != null) {
                tokens.addAll(additional);
                indexFunctions(additional);
            }
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

    /** An immutable expression program. Evaluate it repeatedly with live query values. */
    public static final class Program {
        private final Node root;

        private Program(Node root) { this.root = root; }

        public double evaluate(Context context) { return root.evaluate(context == null ? EMPTY : context); }

        public boolean condition(Context context) { return evaluate(context) != 0; }
    }

    static final int MAX_CACHED_PROGRAMS = 1024;
    static final int MAX_CACHED_SOURCE_CHARS = 256 * 1024;
    private static final Map<String, Program> PROGRAMS = new java.util.LinkedHashMap<>(64, 0.75f, true);
    private static int cachedSourceChars;
    private static final Program ZERO = new Program(new Constant(0));
    private static final Context EMPTY = new Context() {
        @Override public double value(String name) { return 0; }
        @Override public boolean has(String name) { return false; }
        @Override public boolean mayMatchFunction(String name) { return false; }
    };

    /** The bounded compatibility cache does not keep every imported avatar alive. */
    public static Program compile(String expression) {
        if (expression == null || expression.isBlank()) return ZERO;
        synchronized (PROGRAMS) {
            Program cached = PROGRAMS.get(expression);
            if (cached != null) return cached;
        }
        Program compiled = new Program(new Parser(expression).parse());
        synchronized (PROGRAMS) {
            Program existing = PROGRAMS.get(expression);
            if (existing != null) return existing;
            PROGRAMS.put(expression, compiled);
            cachedSourceChars += expression.length();
            while (PROGRAMS.size() > MAX_CACHED_PROGRAMS || cachedSourceChars > MAX_CACHED_SOURCE_CHARS) {
                String oldest = PROGRAMS.keySet().iterator().next();
                cachedSourceChars -= oldest.length();
                PROGRAMS.remove(oldest);
            }
        }
        return compiled;
    }

    static int cachedProgramCount() {
        synchronized (PROGRAMS) { return PROGRAMS.size(); }
    }

    static int cachedSourceCharCount() {
        synchronized (PROGRAMS) { return cachedSourceChars; }
    }

    public static double evaluate(String expression, Context context) {
        return compile(expression).evaluate(context);
    }

    public static boolean condition(String expression, Context context) {
        return compile(expression).condition(context);
    }

    private static void put(Map<String, Double> values, String key, boolean value) { values.put(key, value ? 1d : 0d); }

    private static void put(Map<String, Double> values, String key, double value) {
        values.put(key, Double.isFinite(value) ? value : 0d);
    }

    private static boolean truth(double value) { return value != 0 && Double.isFinite(value); }

    private static String normalizeName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("query.")) return "q." + normalized.substring(6);
        if (normalized.startsWith("variable.")) return "v." + normalized.substring(9);
        if (normalized.startsWith("temp.")) return "t." + normalized.substring(5);
        if (normalized.startsWith("temporary.")) return "t." + normalized.substring(10);
        return normalized;
    }

    private interface Node {
        double evaluate(Context context);
    }

    private record Constant(double number) implements Node {
        @Override public double evaluate(Context context) { return number; }
    }

    private record Variable(String name, String qualified) implements Node {
        @Override public double evaluate(Context context) {
            return context instanceof MapContext map ? map.valuePrepared(name, qualified) : context.value(name);
        }
    }

    private record Assignment(String name, Node value) implements Node {
        @Override public double evaluate(Context context) {
            double assigned = value.evaluate(context);
            context.setVariable(name, assigned);
            return assigned;
        }
    }

    private record Sequence(Node[] statements) implements Node {
        @Override public double evaluate(Context context) {
            double result = 0;
            for (Node statement : statements) result = statement.evaluate(context);
            return result;
        }
    }

    private record Unary(char operator, Node operand) implements Node {
        @Override public double evaluate(Context context) {
            double value = operand.evaluate(context);
            return operator == '!' ? truth(value) ? 0 : 1 : -value;
        }
    }

    private enum Operator { ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO, EQ, NE, LT, LE, GT, GE, AND, OR }

    private record Binary(Operator operator, Node left, Node right) implements Node {
        @Override public double evaluate(Context context) {
            double a = left.evaluate(context);
            if (operator == Operator.AND && !truth(a)) return 0;
            if (operator == Operator.OR && truth(a)) return 1;
            double b = right.evaluate(context);
            return switch (operator) {
                case ADD -> a + b;
                case SUBTRACT -> a - b;
                case MULTIPLY -> a * b;
                case DIVIDE -> b == 0 ? 0 : a / b;
                case MODULO -> b == 0 ? 0 : a % b;
                case EQ -> a == b ? 1 : 0;
                case NE -> a != b ? 1 : 0;
                case LT -> a < b ? 1 : 0;
                case LE -> a <= b ? 1 : 0;
                case GT -> a > b ? 1 : 0;
                case GE -> a >= b ? 1 : 0;
                case AND -> truth(a) && truth(b) ? 1 : 0;
                case OR -> truth(a) || truth(b) ? 1 : 0;
            };
        }
    }

    private record Conditional(Node test, Node yes, Node no) implements Node {
        @Override public double evaluate(Context context) {
            return truth(test.evaluate(context)) ? yes.evaluate(context) : no.evaluate(context);
        }
    }

    private static final class Call implements Node {
        private final String name;
        private final String key;
        private final Node[] arguments;
        private final String[] strings;
        private final String constantToken;
        private final String controlToken;
        private final String alternateControlToken;
        private final boolean holdOrUse;
        private final boolean tacHold;

        private Call(String name, Node[] arguments, String[] strings) {
            this.name = name.toLowerCase(Locale.ROOT);
            String unqualified = this.name.startsWith("q.") ? this.name.substring(2) : this.name;
            this.key = unqualified.startsWith("math.") ? unqualified.substring(5) : unqualified;
            this.arguments = arguments;
            this.strings = strings;
            holdOrUse = unqualified.equals("ctrl.hold") || unqualified.equals("ctrl.use")
                    || unqualified.equals("hold") || unqualified.equals("use");
            tacHold = unqualified.equals("ctrl.tac_hold_gun") || unqualified.equals("tac_hold_gun");
            if (holdOrUse && strings.length > 0 && strings[0] != null
                    && (strings.length == 1 || strings[1] != null)) {
                String verb = unqualified.endsWith("use") ? "use" : "hold";
                String values = "'" + strings[0] + "'";
                if (strings.length >= 2) values += ",'" + strings[1] + "'";
                controlToken = "ctrl." + verb + "(" + values + ")";
                alternateControlToken = verb + "(" + values + ")";
            } else {
                controlToken = null;
                alternateControlToken = null;
            }
            boolean constant = true;
            for (Node argument : arguments) constant &= argument instanceof Constant;
            constantToken = constant ? token(null, 0, 0, 0, 0, 0, 0, 0, 0) : null;
        }

        @Override public double evaluate(Context context) {
            // Primitive locals avoid an argument array per evaluation and retain visit order.
            int count = arguments.length;
            double a0 = count > 0 ? arguments[0].evaluate(context) : 0;
            double a1 = count > 1 ? arguments[1].evaluate(context) : 0;
            double a2 = count > 2 ? arguments[2].evaluate(context) : 0;
            double a3 = count > 3 ? arguments[3].evaluate(context) : 0;
            double a4 = count > 4 ? arguments[4].evaluate(context) : 0;
            double a5 = count > 5 ? arguments[5].evaluate(context) : 0;
            double a6 = count > 6 ? arguments[6].evaluate(context) : 0;
            double a7 = count > 7 ? arguments[7].evaluate(context) : 0;
            if (holdOrUse)
                return controlToken != null && (context.matches(controlToken) || context.matches(alternateControlToken)) ? 1 : 0;
            if (tacHold)
                return context.matches("ctrl.tac_hold_gun()") || context.matches("tac_hold_gun()") ? 1 : 0;
            if (context.mayMatchFunction(name)) {
                String token = constantToken != null ? constantToken : token(context, a0, a1, a2, a3, a4, a5, a6, a7);
                if (context.matches(token)) return 1;
            }
            if (key.equals("position_delta") && count == 1 && a0 >= 0 && a0 <= 2 && a0 == (int) a0)
                return context.value(switch ((int) a0) {
                    case 0 -> "q.position_delta(0)";
                    case 1 -> "q.position_delta(1)";
                    default -> "q.position_delta(2)";
                });
            return switch (key) {
                case "sin" -> count > 0 ? Math.sin(Math.toRadians(a0)) : 0;
                case "cos" -> count > 0 ? Math.cos(Math.toRadians(a0)) : 0;
                case "abs" -> count > 0 ? Math.abs(a0) : 0;
                case "pow" -> count > 1 ? Math.pow(a0, a1) : 0;
                case "random" -> {
                    double minimum = count > 0 ? a0 : 0;
                    double maximum = count > 1 ? a1 : 1;
                    yield maximum <= minimum ? minimum
                            : minimum + Math.random() * (maximum - minimum);
                }
                case "min", "max" -> {
                    double value = a0;
                    for (int i = 1; i < count; i++) {
                        double next = argument(i, a0, a1, a2, a3, a4, a5, a6, a7);
                        value = key.equals("min") ? Math.min(value, next) : Math.max(value, next);
                    }
                    yield value;
                }
                case "clamp" -> count > 0 ? Math.min(Math.max(a0, count > 1 ? a1 : 0), count > 2 ? a2 : 1) : 0;
                default -> 0;
            };
        }

        private String token(Context context, double a0, double a1, double a2, double a3,
                             double a4, double a5, double a6, double a7) {
            StringBuilder token = new StringBuilder(name).append('(');
            for (int i = 0; i < arguments.length; i++) {
                if (i > 0) token.append(',');
                if (strings[i] != null) token.append('\'').append(strings[i]).append('\'');
                else {
                    double value = context == null ? ((Constant) arguments[i]).number()
                            : argument(i, a0, a1, a2, a3, a4, a5, a6, a7);
                    token.append(Double.toString(value));
                }
            }
            return token.append(')').toString();
        }

        private static double argument(int i, double a0, double a1, double a2, double a3,
                                       double a4, double a5, double a6, double a7) {
            return switch (i) {
                case 0 -> a0;
                case 1 -> a1;
                case 2 -> a2;
                case 3 -> a3;
                case 4 -> a4;
                case 5 -> a5;
                case 6 -> a6;
                default -> a7;
            };
        }
    }

    private static final class Parser {
        private final String source;
        private int pos;
        private int depth;
        private int nodeCount;

        private Parser(String source) {
            if (source.length() > 65536) throw new IllegalArgumentException("Molang expression exceeds 64 KiB");
            this.source = source;
        }

        private Node parse() {
            Node result = assignment();
            java.util.ArrayList<Node> statements = null;
            while (consume(';')) {
                skip();
                if (pos == source.length()) break;
                if (statements == null) {
                    statements = new java.util.ArrayList<>();
                    statements.add(result);
                }
                statements.add(assignment());
            }
            if (pos != source.length()) throw error("unexpected trailing input");
            return statements == null ? result : node(new Sequence(statements.toArray(Node[]::new)));
        }

        private Node assignment() {
            skip();
            int start = pos;
            if (pos < source.length() && (Character.isLetter(source.charAt(pos)) || source.charAt(pos) == '_')) {
                String name = identifier();
                skip();
                if (pos < source.length() && source.charAt(pos) == '='
                        && (pos + 1 == source.length() || source.charAt(pos + 1) != '=')) {
                    if (!name.startsWith("v.") && !name.startsWith("t."))
                        throw error("only variable and temporary names are writable");
                    pos++;
                    if (++depth > 256) throw error("expression nesting exceeds limit");
                    try { return node(new Assignment(name, assignment())); }
                    finally { depth--; }
                }
            }
            pos = start;
            return ternary();
        }

        private Node ternary() {
            if (++depth > 256) throw error("expression nesting exceeds limit");
            try {
                Node test = or();
                if (!consume('?')) return test;
                Node yes = assignment();
                return node(new Conditional(test, yes, consume(':') ? assignment() : node(new Constant(0))));
            } finally {
                depth--;
            }
        }

        private Node or() {
            Node value = and();
            while (consume('|', '|')) value = binary(Operator.OR, value, and());
            return value;
        }

        private Node and() {
            Node value = equality();
            while (consume('&', '&')) value = binary(Operator.AND, value, equality());
            return value;
        }

        private Node equality() {
            Node value = comparison();
            while (true) {
                if (consume('=', '=')) value = binary(Operator.EQ, value, comparison());
                else if (consume('!', '=')) value = binary(Operator.NE, value, comparison());
                else return value;
            }
        }

        private Node comparison() {
            Node value = additive();
            while (true) {
                if (consume('<', '=')) value = binary(Operator.LE, value, additive());
                else if (consume('>', '=')) value = binary(Operator.GE, value, additive());
                else if (consume('<')) value = binary(Operator.LT, value, additive());
                else if (consume('>')) value = binary(Operator.GT, value, additive());
                else return value;
            }
        }

        private Node additive() {
            Node value = multiplicative();
            while (true) {
                if (consume('+')) value = binary(Operator.ADD, value, multiplicative());
                else if (consume('-')) value = binary(Operator.SUBTRACT, value, multiplicative());
                else return value;
            }
        }

        private Node multiplicative() {
            Node value = unary();
            while (true) {
                if (consume('*')) value = binary(Operator.MULTIPLY, value, unary());
                else if (consume('/')) value = binary(Operator.DIVIDE, value, unary());
                else if (consume('%')) value = binary(Operator.MODULO, value, unary());
                else return value;
            }
        }

        private Node unary() {
            if (++depth > 256) throw error("expression nesting exceeds limit");
            try {
                if (consume('!')) return node(new Unary('!', unary()));
                if (consume('-')) return node(new Unary('-', unary()));
                if (consume('+')) return unary();
                return primary();
            } finally {
                depth--;
            }
        }

        private Node primary() {
            skip();
            if (consume('(')) {
                Node value = assignment();
                if (!consume(')')) throw error("expected ')'");
                return value;
            }
            if (pos >= source.length()) throw error("unexpected end of expression");
            char ch = source.charAt(pos);
            if (ch == '"' || ch == '\'') return node(new Constant(stringLiteral().isEmpty() ? 0 : 1));
            if (Character.isDigit(ch) || ch == '.') return node(new Constant(number()));
            String name = identifier();
            if (consume('(')) return call(name);
            if (name.equalsIgnoreCase("true")) return node(new Constant(1));
            if (name.equalsIgnoreCase("false")) return node(new Constant(0));
            if (name.equals("math.pi")) return node(new Constant(Math.PI));
            return node(new Variable(name, !name.startsWith("q.") && !name.startsWith("v.") ? "q." + name : null));
        }

        private Node call(String name) {
            java.util.ArrayList<Node> arguments = new java.util.ArrayList<>();
            java.util.ArrayList<String> strings = new java.util.ArrayList<>();
            skip();
            if (!consume(')')) {
                while (true) {
                    if (arguments.size() >= 8) throw error("function has more than eight arguments");
                    skip();
                    if (pos < source.length() && (source.charAt(pos) == '"' || source.charAt(pos) == '\'')) {
                        String value = stringLiteral();
                        strings.add(value);
                        arguments.add(node(new Constant(value.isEmpty() ? 0 : 1)));
                    } else {
                        strings.add(null);
                        arguments.add(assignment());
                    }
                    if (consume(')')) break;
                    if (!consume(',')) throw error("expected ',' or ')' in call");
                }
            }
            return node(new Call(name, arguments.toArray(Node[]::new), strings.toArray(String[]::new)));
        }

        private Node binary(Operator operator, Node left, Node right) { return node(new Binary(operator, left, right)); }

        private Node node(Node value) {
            if (++nodeCount > 1024) throw error("expression node count exceeds limit");
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
            return normalizeName(source.substring(start, pos));
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

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("Molang expression error at " + pos + ": " + message);
        }
    }
}
