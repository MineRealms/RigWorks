package cn.minerealms.rigworks.character.capabilities;

import java.util.Locale;
import java.util.Map;

/** Static translation from YSM locator node names to existing lowercase equipment socket names. */
public final class YsmLocatorSockets {
    public static final Map<String, String> LOCATOR_TO_SOCKET = Map.ofEntries(
            Map.entry("Head", "head"),
            Map.entry("Body", "body"),
            Map.entry("Chest", "chest"),
            Map.entry("Hips", "hips"),
            Map.entry("LeftHandLocator", "left_hand"),
            Map.entry("RightHandLocator", "right_hand"),
            Map.entry("LeftArmLocator", "left_arm"),
            Map.entry("RightArmLocator", "right_arm"),
            Map.entry("LeftLegLocator", "left_leg"),
            Map.entry("RightLegLocator", "right_leg"),
            Map.entry("LeftFootLocator", "left_foot"),
            Map.entry("RightFootLocator", "right_foot"),
            Map.entry("CapeLocator", "cape"),
            Map.entry("BackLocator", "back"),
            Map.entry("ElytraLocator", "back"),
            Map.entry("OffhandLocator", "offhand")
    );

    private YsmLocatorSockets() {}

    public static String socketFor(String locator) {
        if (locator == null) return null;
        String direct = LOCATOR_TO_SOCKET.get(locator);
        if (direct != null) return direct;
        return LOCATOR_TO_SOCKET.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(locator))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    public static boolean isLocator(String node) {
        return socketFor(node) != null || node != null && node.toLowerCase(Locale.ROOT).endsWith("locator");
    }
}
