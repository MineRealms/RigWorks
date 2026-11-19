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

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface YsmModelSource {
    Object decode(byte[] payload) throws IOException;

    byte[] toGlb(Object model) throws IOException;

    YsmModelView view(Object model);

    record YsmTransitionView(String to, String condition) {}

    record YsmStateView(Map<String, String> animations, List<YsmTransitionView> transitions) {}

    record YsmControllerView(String name, String initialState, Map<String, YsmStateView> states) {}

    record YsmModelView(List<String> animationNames, List<YsmControllerView> controllers) {
        public YsmModelView {
            animationNames = List.copyOf(animationNames);
            controllers = List.copyOf(controllers);
        }
    }

    static YsmModelSource reflective() {
        return new YsmModelSource() {
            @Override public Object decode(byte[] payload) throws IOException {
                return invoke("cn.minerealms.rigworks.ysm.model.YsmBinaryDecoder", "decode", payload);
            }

            @Override public byte[] toGlb(Object model) throws IOException {
                Object result = invoke("cn.minerealms.rigworks.ysm.model.YsmGlbWriter", "toGlb", model);
                if (result instanceof byte[] glb) return glb;
                throw new IOException("YsmGlbWriter.toGlb produced no GLB data");
            }

            @Override public YsmModelView view(Object model) {
                if (model == null) return new YsmModelView(List.of(), List.of());
                List<String> names = new ArrayList<>();
                Object animations = read(model, "animations");
                if (animations instanceof Map<?, ?> map) for (Object key : map.keySet()) names.add(String.valueOf(key));
                List<YsmControllerView> controllers = new ArrayList<>();
                Object values = read(model, "controllers");
                if (values instanceof Iterable<?> iterable) for (Object controller : iterable) controllers.add(controller(controller));
                return new YsmModelView(names, controllers);
            }

            private YsmControllerView controller(Object value) {
                String name = String.valueOf(read(value, "name"));
                String initial = String.valueOf(read(value, "initialState"));
                Map<String, YsmStateView> states = new LinkedHashMap<>();
                Object source = read(value, "states");
                if (source instanceof Map<?, ?> map) for (Map.Entry<?, ?> entry : map.entrySet())
                    states.put(String.valueOf(entry.getKey()), state(entry.getValue()));
                return new YsmControllerView(name, initial, states);
            }

            private YsmStateView state(Object value) {
                Map<String, String> animations = new LinkedHashMap<>();
                Object source = read(value, "animations");
                if (source instanceof Map<?, ?> map) for (Map.Entry<?, ?> entry : map.entrySet())
                    animations.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                List<YsmTransitionView> transitions = new ArrayList<>();
                Object list = read(value, "transitions");
                if (list instanceof Iterable<?> iterable) for (Object transition : iterable)
                    transitions.add(new YsmTransitionView(text(read(transition, "to")), text(read(transition, "condition"))));
                return new YsmStateView(animations, transitions);
            }
        };
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Object read(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    private static Object invoke(String className, String name, Object argument) throws IOException {
        try {
            Class<?> type = Class.forName(className, false, YsmModelSource.class.getClassLoader());
            Method method = null;
            for (Method candidate : type.getMethods()) {
                if (candidate.getName().equals(name) && candidate.getParameterCount() == 1
                        && java.lang.reflect.Modifier.isStatic(candidate.getModifiers())) {
                    method = candidate;
                    break;
                }
            }
            if (method == null) throw new NoSuchMethodException(name);
            return method.invoke(null, argument);
        } catch (ClassNotFoundException | NoSuchMethodException absent) {
            throw new IOException("YSM model API is not on the runtime classpath", absent);
        } catch (IllegalAccessException denied) {
            throw new IOException("Cannot access YSM model API " + name, denied);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IOException("YSM model API " + name + " failed", cause);
        }
    }
}
