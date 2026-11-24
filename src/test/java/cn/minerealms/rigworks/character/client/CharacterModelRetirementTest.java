package cn.minerealms.rigworks.character.client;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CharacterModelRetirementTest {
    @Test void cacheRetirementWaitsForAllLiveDeletedOwnersBeforeGlobalInvalidation() {
        var retired = new CharacterModelRetirement<Object>();
        Object first = new Object(), second = new Object(), unrelated = new Object();
        var cached = new HashMap<>(Map.of("first#dynamic", first, "second#dynamic", second, "other#dynamic", unrelated));
        retired.request("first#dynamic", first);
        retired.request("second#dynamic", second);
        assertTrue(retired.ready(cached::get, scene -> scene == second).isEmpty());
        var ready = retired.ready(cached::get, scene -> false);
        assertEquals(Set.of("first#dynamic", "second#dynamic"), ready.keySet());
        ready.keySet().forEach(cached::remove);
        retired.completed(ready.keySet());
        assertTrue(retired.isEmpty());
        assertSame(unrelated, cached.get("other#dynamic"));
    }

    @Test void reimportOrNewSavedReferenceCancelsOnlyTheMatchingRetirement() {
        var retired = new CharacterModelRetirement<Object>();
        Object original = new Object(), replacement = new Object(), saved = new Object();
        retired.request("reimport#dynamic", original);
        retired.request("saved#dynamic", saved);
        retired.cancel("saved#dynamic");
        assertTrue(retired.ready(Map.of("reimport#dynamic", replacement)::get, scene -> false).isEmpty());
        assertTrue(retired.isEmpty());
    }
}
