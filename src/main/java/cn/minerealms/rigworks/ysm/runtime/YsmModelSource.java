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

import cn.minerealms.rigworks.ysm.model.YsmChannel;
import cn.minerealms.rigworks.ysm.model.YsmBone;
import cn.minerealms.rigworks.ysm.model.Vec3;

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

    record YsmStateView(Map<String, String> animations, List<YsmTransitionView> transitions,
                        List<cn.minerealms.rigworks.ysm.model.YsmAnimationReference> animationReferences) {
        public YsmStateView(Map<String, String> animations, List<YsmTransitionView> transitions) {
            this(animations, transitions, List.of());
        }
    }

    record YsmControllerView(String name, String sourceGroup, int sourceGroupId,
                             String initialState, Map<String, YsmStateView> states) {
        public YsmControllerView(String name, String initialState, Map<String, YsmStateView> states) {
            this(name, "", -1, initialState, states);
        }
    }

    record YsmAnimationView(String name, String category, int categoryId, float durationSeconds,
                            int loopMode, List<YsmChannel> channels) {
        public YsmAnimationView {
            channels = List.copyOf(channels);
        }
    }

    record YsmBoneView(String name, Vec3 initialRotation) {}

    record YsmModelView(List<String> animationNames, List<YsmControllerView> controllers,
                        List<YsmAnimationView> animations, List<YsmBoneView> bones) {
        public YsmModelView {
            animationNames = List.copyOf(animationNames);
            controllers = List.copyOf(controllers);
            animations = List.copyOf(animations);
            bones = List.copyOf(bones);
        }

        public YsmModelView(List<String> animationNames, List<YsmControllerView> controllers) {
            this(animationNames, controllers, List.of(), List.of());
        }

        public YsmModelView(List<String> animationNames, List<YsmControllerView> controllers,
                            List<YsmAnimationView> animations) {
            this(animationNames, controllers, animations, List.of());
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
                if (model == null) return new YsmModelView(List.of(), List.of(), List.of());
                List<String> names = new ArrayList<>();
                List<YsmAnimationView> animationViews = new ArrayList<>();
                Object animations = read(model, "animations");
                if (animations instanceof Map<?, ?> map) for (Object key : map.keySet()) names.add(String.valueOf(key));
                Object clips = read(model, "animationClips");
                if (clips instanceof Iterable<?> iterable) for (Object animation : iterable) {
                    String animationName = text(read(animation, "name"));
                    String category = text(read(animation, "category"));
                    Object categoryId = read(animation, "categoryId");
                    Object duration = read(animation, "duration");
                    Object loopMode = read(animation, "loopMode");
                    List<YsmChannel> channels = new ArrayList<>();
                    Object sourceChannels = read(animation, "channels");
                    if (sourceChannels instanceof Iterable<?> channelList)
                        for (Object channel : channelList) if (channel instanceof YsmChannel typed) channels.add(typed);
                    animationViews.add(new YsmAnimationView(animationName, category,
                            categoryId instanceof Number number ? number.intValue() : -1,
                            duration instanceof Number number ? number.floatValue() : 0f,
                            loopMode instanceof Number number ? number.intValue() : 0, channels));
                    if (!names.contains(animationName)) names.add(animationName);
                }
                List<YsmControllerView> controllers = new ArrayList<>();
                Object values = read(model, "controllers");
                if (values instanceof Iterable<?> iterable) for (Object controller : iterable) controllers.add(controller(controller));
                List<YsmBoneView> bones = new ArrayList<>();
                Object sourceBones = read(model, "bones");
                if (sourceBones instanceof Iterable<?> iterable) for (Object bone : iterable)
                    if (bone instanceof YsmBone typed) bones.add(new YsmBoneView(typed.name(), typed.rotation()));
                return new YsmModelView(names, controllers, animationViews, bones);
            }

            private YsmControllerView controller(Object value) {
                String name = String.valueOf(read(value, "name"));
                String sourceGroup = text(read(value, "sourceGroup"));
                Object sourceGroupId = read(value, "sourceGroupId");
                String initial = String.valueOf(read(value, "initialState"));
                Map<String, YsmStateView> states = new LinkedHashMap<>();
                Object source = read(value, "states");
                if (source instanceof Map<?, ?> map) for (Map.Entry<?, ?> entry : map.entrySet())
                    states.put(String.valueOf(entry.getKey()), state(entry.getValue()));
                return new YsmControllerView(name, sourceGroup,
                        sourceGroupId instanceof Number number ? number.intValue() : -1, initial, states);
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
                List<cn.minerealms.rigworks.ysm.model.YsmAnimationReference> references = new ArrayList<>();
                Object refs = read(value, "animationReferences");
                if (refs instanceof Iterable<?> iterable) for (Object reference : iterable)
                    references.add(new cn.minerealms.rigworks.ysm.model.YsmAnimationReference(
                            text(read(reference, "name")), text(read(reference, "condition"))));
                return new YsmStateView(animations, transitions, references);
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
