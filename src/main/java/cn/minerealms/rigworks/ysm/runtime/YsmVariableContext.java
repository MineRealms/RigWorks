package cn.minerealms.rigworks.ysm.runtime;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Variables belong to one character, while query values are refreshed from its live inputs. */
public final class YsmVariableContext implements YsmExpressionRuntime.Context {
    private final Map<String, Double> variables = new HashMap<>();
    private YsmExpressionRuntime.Context queries;

    public void bind(YsmExpressionRuntime.Context queries) { this.queries = queries; }

    @Override public double value(String name) {
        if (variable(name)) {
            Double value = variables.get(key(name));
            if (value != null) return value;
        }
        return queries == null ? 0 : queries.value(name);
    }

    @Override public boolean has(String name) {
        return variable(name) && variables.containsKey(key(name)) || queries != null && queries.has(name);
    }

    @Override public void setVariable(String name, double value) {
        if (!variable(name)) return;
        String key = key(name);
        if (variables.size() >= 4096 && !variables.containsKey(key))
            throw new IllegalStateException("YSM character variable limit exceeded");
        variables.put(key, Double.isFinite(value) ? value : 0);
    }

    @Override public boolean matches(String token) { return queries != null && queries.matches(token); }
    @Override public boolean mayMatchFunction(String name) { return queries != null && queries.mayMatchFunction(name); }
    public void clear() { variables.clear(); queries = null; }

    private static boolean variable(String name) { return name.startsWith("v.") || name.startsWith("variable."); }
    private static String key(String name) {
        return (name.startsWith("variable.") ? "v." + name.substring(9) : name).toLowerCase(Locale.ROOT);
    }
}
