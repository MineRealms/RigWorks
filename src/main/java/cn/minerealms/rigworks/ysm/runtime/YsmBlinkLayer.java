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

import cn.minerealms.assimploader.animation.AnimationState;
import cn.minerealms.rigworks.character.capabilities.BlinkQuery;
import cn.minerealms.rigworks.character.capabilities.YsmBlinkController;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

public final class YsmBlinkLayer {
    public static final String BLINK_LAYER = "ysm:blink";
    public static final String WINK_LAYER = "ysm:wink";

    private final BlinkQuery blink;
    private final BlinkQuery winkQuery;
    private final String blinkClip;
    private final String winkClip;
    private final Map<String, Float> mask;
    private boolean blinkInstalled;
    private boolean winkInstalled;

    public YsmBlinkLayer(BlinkQuery blink, BlinkQuery winkQuery, String blinkClip, String winkClip, Map<String, Float> mask) {
        if (blink == null || blinkClip == null || blinkClip.isBlank())
            throw new IllegalArgumentException("Blink layer needs a query and a clip");
        this.blink = blink;
        this.winkQuery = winkQuery;
        this.blinkClip = blinkClip;
        this.winkClip = winkClip;
        this.mask = Map.copyOf(mask == null ? Map.of() : mask);
    }

    public static YsmBlinkLayer fromAnimations(Collection<String> animationNames, Collection<String> nodeNames,
                                               UUID uuid, IntSupplier renderTicks, BooleanSupplier sleeping) {
        String blink = blinkClip(animationNames);
        String wink = winkClip(animationNames);
        String clip = blink == null ? wink : blink;
        if (clip == null) return null;
        UUID id = uuid == null ? UUID.nameUUIDFromBytes("ysm-blink".getBytes(java.nio.charset.StandardCharsets.UTF_8)) : uuid;
        BlinkQuery query = new BlinkQuery(id, renderTicks, sleeping);
        long offset = Math.abs(id.getMostSignificantBits()) % 10;
        BlinkQuery winkSource = wink == null || wink.equals(clip) ? null
                : new BlinkQuery(id, () -> renderTicks.getAsInt() + (int) offset * 9, () -> false);
        return new YsmBlinkLayer(query, winkSource, clip, winkSource == null ? null : wink,
                YsmBlinkController.eyelidMask(nodeNames));
    }

    public static String blinkClip(Collection<String> animationNames) {
        if (animationNames == null) return null;
        String fallback = null;
        for (String name : animationNames) {
            if (name == null) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("blinkeye") || lower.contains("blink_eye")) return name;
            if (lower.contains("blink")) fallback = name;
            if (fallback == null && lower.contains("close_eye")) fallback = name;
        }
        return fallback;
    }

    public static String winkClip(Collection<String> animationNames) {
        if (animationNames == null) return null;
        for (String name : animationNames) {
            if (name != null && name.toLowerCase(Locale.ROOT).contains("wink")) return name;
        }
        return null;
    }

    public void update(AnimationState animation) {
        if (animation == null || animation.isClosed()) return;
        if (!available(animation, blinkClip)) return;
        float blinkWeight = blink.isCloseEyes() ? 1f : 0f;
        if (!blinkInstalled || animation.getLayer(BLINK_LAYER) == null) {
            animation.setLayer(BLINK_LAYER, blinkClip, blinkWeight, false, mask);
            blinkInstalled = true;
        } else animation.setLayerWeight(BLINK_LAYER, blinkWeight);

        if (winkClip == null || !available(animation, winkClip) || winkQuery == null) return;
        float winkWeight = winkQuery.isCloseEyes() ? 1f : 0f;
        if (!winkInstalled || animation.getLayer(WINK_LAYER) == null) {
            animation.setLayer(WINK_LAYER, winkClip, winkWeight, false, mask);
            winkInstalled = true;
        } else animation.setLayerWeight(WINK_LAYER, winkWeight);
    }

    private static boolean available(AnimationState animation, String clip) {
        for (String name : animation.getAvailableAnimations()) if (clip.equals(name)) return true;
        return false;
    }

    public String blinkClip() { return blinkClip; }
    public String winkClip() { return winkClip; }
    public Map<String, Float> mask() { return mask; }
}
