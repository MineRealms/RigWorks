package cn.minerealms.rigworks.character.client;

import cn.minerealms.rigworks.character.capabilities.BlinkQuery;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BlinkQueryTest {
    private static final UUID UUID_A = new UUID(1L, 1234567L);
    private static final UUID UUID_B = new UUID(1L, 9876543L);

    private static long phase(UUID uuid, int ticks) {
        return ((long) ticks + Math.abs(uuid.getLeastSignificantBits()) % 10) % 90;
    }

    @Test void sleepingAlwaysClosesEyes() {
        for (int tick = 0; tick < 180; tick++) {
            int current = tick;
            assertTrue(new BlinkQuery(UUID_A, () -> current, () -> true).isCloseEyes());
        }
    }

    @Test void blinkWindowIsExclusiveEightyFiveToNinety() {
        for (UUID uuid : new UUID[]{UUID_A, UUID_B}) {
            long offset = Math.abs(uuid.getLeastSignificantBits()) % 10;
            for (int tick = 0; tick < 360; tick++) {
                int current = tick;
                boolean expected = phase(uuid, current) > 85 && phase(uuid, current) < 90;
                assertEquals(expected, new BlinkQuery(uuid, () -> current).isCloseEyes(), "tick " + current + " uuid " + uuid);
            }
            assertTrue(new BlinkQuery(uuid, () -> (int) (86 - offset)).isCloseEyes());
            assertFalse(new BlinkQuery(uuid, () -> (int) (85 - offset)).isCloseEyes());
            assertFalse(new BlinkQuery(uuid, () -> (int) (90 - offset)).isCloseEyes());
        }
    }

    @Test void phaseWrapsEveryNinetyTicks() {
        int open = -1, closed = -1;
        for (int tick = 0; tick < 90; tick++) {
            int current = tick;
            if (!new BlinkQuery(UUID_A, () -> current).isCloseEyes() && open < 0) open = current;
            if (new BlinkQuery(UUID_A, () -> current).isCloseEyes() && closed < 0) closed = current;
        }
        assertEquals(0, open);
        assertEquals((int) (86 - Math.abs(UUID_A.getLeastSignificantBits()) % 10), closed);
    }

    @Test void injectableTickSourceIsReadEachCall() {
        int[] tick = {0};
        var query = new BlinkQuery(UUID_A, () -> tick[0]);
        int target = (int) (86 - Math.abs(UUID_A.getLeastSignificantBits()) % 10);
        tick[0] = target;
        assertTrue(query.isCloseEyes());
        tick[0] = target + 1;
        assertTrue(query.isCloseEyes());
        tick[0] = target + 4;
        assertFalse(query.isCloseEyes());
    }
}
