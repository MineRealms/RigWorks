package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.animation.AnimationPoseLayer;
import cn.minerealms.assimploader.animation.AnimationState;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Installs and drives a single named, eyelid-masked blink layer on an existing animation state. */
public final class YsmBlinkController {
    public static final String LAYER_ID = "blink";
    private final BlinkQuery query;
    private final String clip;
    private final Map<String, Float> mask;
    private final boolean additive;
    private boolean installed;

    public YsmBlinkController(BlinkQuery query, String clip) {
        this(query, clip, Map.of(), false);
    }

    public YsmBlinkController(BlinkQuery query, String clip, Map<String, Float> mask, boolean additive) {
        if (query == null || clip == null || clip.isBlank())
            throw new IllegalArgumentException("Blink needs a query and a clip name");
        this.query = query;
        this.clip = clip;
        this.mask = Map.copyOf(mask);
        this.additive = additive;
    }

    public void update(AnimationState animation) {
        if (animation == null || animation.isClosed()) return;
        boolean present = false;
        for (String available : animation.getAvailableAnimations()) if (clip.equals(available)) present = true;
        if (!present) return;
        float weight = query.isCloseEyes() ? 1f : 0f;
        if (!installed || animation.getLayer(LAYER_ID) == null) {
            animation.setLayer(LAYER_ID, clip, weight, additive, mask);
            installed = true;
        } else {
            animation.setLayerWeight(LAYER_ID, weight);
        }
    }

    public AnimationPoseLayer layer(AnimationState animation) {
        return animation == null ? null : animation.getLayer(LAYER_ID);
    }

    public boolean installed() { return installed; }

    /** Eyelid bones by node name; an empty mask means the clip already targets only eyelids. */
    public static Map<String, Float> eyelidMask(Collection<String> nodes) {
        var selected = new LinkedHashMap<String, Float>();
        if (nodes != null) for (String node : nodes) {
            if (node == null) continue;
            String lower = node.toLowerCase(Locale.ROOT);
            if ((lower.contains("eye") && (lower.contains("lid") || lower.contains("blink")))
                    || lower.contains("eyelid") || lower.contains("blink"))
                selected.put(node, 1f);
        }
        return Map.copyOf(selected);
    }
}
