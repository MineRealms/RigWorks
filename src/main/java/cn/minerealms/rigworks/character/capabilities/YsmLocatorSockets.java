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
            Map.entry("LeftHandLocator2", "left_hand_2"),
            Map.entry("LeftHandLocator3", "left_hand_3"),
            Map.entry("LeftHandLocator4", "left_hand_4"),
            Map.entry("LeftHandLocator5", "left_hand_5"),
            Map.entry("LeftHandLocator6", "left_hand_6"),
            Map.entry("LeftHandLocator7", "left_hand_7"),
            Map.entry("LeftHandLocator8", "left_hand_8"),
            Map.entry("RightHandLocator2", "right_hand_2"),
            Map.entry("RightHandLocator3", "right_hand_3"),
            Map.entry("RightHandLocator4", "right_hand_4"),
            Map.entry("RightHandLocator5", "right_hand_5"),
            Map.entry("RightHandLocator6", "right_hand_6"),
            Map.entry("RightHandLocator7", "right_hand_7"),
            Map.entry("RightHandLocator8", "right_hand_8"),
            Map.entry("LeftArmLocator", "left_arm"),
            Map.entry("RightArmLocator", "right_arm"),
            Map.entry("LeftLegLocator", "left_leg"),
            Map.entry("RightLegLocator", "right_leg"),
            Map.entry("LeftFootLocator", "left_foot"),
            Map.entry("RightFootLocator", "right_foot"),
            Map.entry("PistolLocator", "pistol"),
            Map.entry("RifleLocator", "rifle"),
            Map.entry("LeftWaistLocator", "left_waist"),
            Map.entry("RightWaistLocator", "right_waist"),
            Map.entry("LeftShoulderLocator", "left_shoulder"),
            Map.entry("RightShoulderLocator", "right_shoulder"),
            Map.entry("BladeLocator", "blade"),
            Map.entry("SheathLocator", "sheath"),
            Map.entry("BackpackLocator", "backpack"),
            Map.entry("PassengerLocator", "passenger"),
            Map.entry("PassengerLocator2", "passenger_2"),
            Map.entry("PassengerLocator3", "passenger_3"),
            Map.entry("PassengerLocator4", "passenger_4"),
            Map.entry("PassengerLocator5", "passenger_5"),
            Map.entry("PassengerLocator6", "passenger_6"),
            Map.entry("PassengerLocator7", "passenger_7"),
            Map.entry("PassengerLocator8", "passenger_8"),
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
