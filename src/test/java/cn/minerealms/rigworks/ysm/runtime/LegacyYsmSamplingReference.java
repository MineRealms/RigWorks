/* Test-only baseline copied from commit 40dda30 before prepared sampling.
 * Original YSM-derived implementation: Apache-2.0, modified by MineRealms.
 * Retained to compare semantics and actual runtime cost against the old parser. */
package cn.minerealms.rigworks.ysm.runtime;

import cn.minerealms.rigworks.ysm.model.YsmChannel;
import cn.minerealms.rigworks.ysm.model.YsmKeyframe;
import cn.minerealms.rigworks.ysm.model.YsmTypedValue;
import cn.minerealms.rigworks.ysm.runtime.YsmExpressionRuntime.Context;
import java.util.List;
import java.util.Locale;

final class LegacyYsmSamplingReference {
    private static final float DEGREES_TO_RADIANS = (float) (Math.PI / 180.0);

    private LegacyYsmSamplingReference() {}

    public static float[] sample(YsmChannel channel, double timeSeconds, YsmExpressionRuntime.Context context) {
        if (channel == null) throw new IllegalArgumentException("YSM channel is required");
        if (!Double.isFinite(timeSeconds)) throw new IllegalArgumentException("YSM sample time must be finite");
        List<YsmKeyframe> frames = channel.frames();
        if (frames == null || frames.isEmpty()) return new float[3];

        for (int i = 1; i < frames.size(); i++) {
            if (frames.get(i).time() < frames.get(i - 1).time())
                throw new IllegalArgumentException("YSM keyframes must be sorted by time");
        }

        float[] result;
        if (timeSeconds < frames.get(0).time() || frames.size() == 1) {
            result = values(timeSeconds < frames.get(0).time() ? pre(frames.get(0)) : post(frames.get(0)), context);
        } else {
            result = sampleSegment(frames, timeSeconds, context);
        }

        if ("rotation".equalsIgnoreCase(channel.path())) {
            result[0] = -result[0] * DEGREES_TO_RADIANS;
            result[1] = -result[1] * DEGREES_TO_RADIANS;
            result[2] = result[2] * DEGREES_TO_RADIANS;
        }
        return result;
    }

    private static float[] sampleSegment(List<YsmKeyframe> frames, double time, YsmExpressionRuntime.Context context) {
        for (int i = 0; i < frames.size() - 1; i++) {
            YsmKeyframe begin = frames.get(i);
            YsmKeyframe end = frames.get(i + 1);
            if (time >= end.time()) continue;

            float duration = end.time() - begin.time();
            if (duration <= 0) continue;
            float progress = (float) ((time - begin.time()) / duration);
            float[] p1 = values(post(begin), context);
            float[] p2 = values(pre(end), context);
            boolean catmullRom = end.interpolationMode() == 2 || begin.interpolationMode() == 2;
            if (!catmullRom) return lerp(p1, p2, progress);

            YsmKeyframe leftFrame = frames.get(Math.max(0, i - 1));
            YsmKeyframe rightFrame = frames.get(Math.min(frames.size() - 1, i + 2));
            float[] p0 = values(post(leftFrame), context);
            float[] p3 = values(pre(rightFrame), context);
            return catmullRom(p0, p1, p2, p3, progress);
        }
        return values(post(frames.get(frames.size() - 1)), context);
    }

    private static List<YsmTypedValue> pre(YsmKeyframe frame) {
        return frame.hasPreValue() ? frame.preValues() : frame.postValues();
    }

    private static List<YsmTypedValue> post(YsmKeyframe frame) {
        return frame.postValues();
    }

    private static float[] values(List<YsmTypedValue> values, YsmExpressionRuntime.Context context) {
        float[] result = new float[3];
        for (int axis = 0; axis < 3; axis++) {
            YsmTypedValue value = values.get(axis);
            double number = value.isFloat() ? value.floatValue()
                    : evaluate(value.rawString(), context);
            if (!Double.isFinite(number))
                throw new IllegalArgumentException("YSM keyframe value evaluated to a non-finite number");
            result[axis] = (float) number;
        }
        return result;
    }

    private static float[] lerp(float[] start, float[] end, float t) {
        return new float[]{
                start[0] + t * (end[0] - start[0]),
                start[1] + t * (end[1] - start[1]),
                start[2] + t * (end[2] - start[2])
        };
    }

    private static float[] catmullRom(float[] left, float[] begin, float[] end, float[] right, float t) {
        float[] result = new float[3];
        float t2 = t * t;
        float t3 = t2 * t;
        for (int axis = 0; axis < 3; axis++) {
            float v0 = (end[axis] - left[axis]) * 0.5f;
            float v1 = (right[axis] - begin[axis]) * 0.5f;
            result[axis] = (2 * begin[axis] - 2 * end[axis] + v0 + v1) * t3
                    + (-3 * begin[axis] + 3 * end[axis] - 2 * v0 - v1) * t2
                    + v0 * t + begin[axis];
        }
        return result;
    }

    private static final Context EMPTY = new Context() {
        @Override public double value(String name) { return 0; }
        @Override public boolean has(String name) { return false; }
    };

    static double evaluate(String expression, Context context) {
        if (expression == null || expression.isBlank()) return 0;
        return new Parser(expression, context == null ? EMPTY : context).parse();
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
                case "sin" -> count > 0 ? Math.sin(Math.toRadians(args[0])) : 0;
                case "cos" -> count > 0 ? Math.cos(Math.toRadians(args[0])) : 0;
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

