package cn.minerealms.rigworks.character.capabilities;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/** Client-visual computation of the YSM {@code is_close_eyes} flag. */
public final class BlinkQuery {
    private final UUID uuid;
    private final IntSupplier renderTicks;
    private final BooleanSupplier sleeping;

    public BlinkQuery(UUID uuid, IntSupplier renderTicks) {
        this(uuid, renderTicks, () -> false);
    }

    public BlinkQuery(UUID uuid, IntSupplier renderTicks, BooleanSupplier sleeping) {
        if (uuid == null || renderTicks == null || sleeping == null)
            throw new IllegalArgumentException("BlinkQuery requires a uuid, tick source and sleep source");
        this.uuid = uuid;
        this.renderTicks = renderTicks;
        this.sleeping = sleeping;
    }

    public boolean isCloseEyes() {
        if (sleeping.getAsBoolean()) return true;
        long offset = Math.abs(uuid.getLeastSignificantBits()) % 10;
        long phase = ((long) renderTicks.getAsInt() + offset) % 90;
        return phase > 85 && phase < 90;
    }

    public static boolean isCloseEyes(UUID uuid, int renderTicks, boolean sleeping) {
        return new BlinkQuery(uuid, () -> renderTicks, () -> sleeping).isCloseEyes();
    }
}
