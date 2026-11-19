package cn.minerealms.rigworks.character.client;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CharacterPackageApplicationsTest {
    @Test void downloadedReplacementCannotReleaseTheLastAppliedPackage() {
        var state=new CharacterPackageApplications();var owner=UUID.randomUUID();
        var oldBinding=new Object();
        assertTrue(state.begin(owner,"old",null));assertNull(state.applied(owner));
        assertTrue(state.confirm(owner,"old",oldBinding));assertTrue(state.begin(owner,"new",oldBinding));
        assertEquals("old",state.applied(owner));assertEquals("new",state.applying(owner));
        assertEquals(Set.of("old","new"),state.retained());
        assertEquals("new",state.reject(owner));assertEquals("old",state.applied(owner));
        assertEquals(Set.of("old"),state.retained());
    }

    @Test void onlyTheCurrentApplicationCanCommitAndReleaseItsPredecessor() {
        var state=new CharacterPackageApplications();var owner=UUID.randomUUID();
        var oldBinding=new Object();var replacement=new Object();
        state.begin(owner,"old",null);state.confirm(owner,"old",oldBinding);
        state.begin(owner,"obsolete",oldBinding);state.begin(owner,"latest",oldBinding);
        assertFalse(state.confirm(owner,"obsolete",replacement));assertEquals("old",state.applied(owner));
        assertTrue(state.confirm(owner,"latest",replacement));assertEquals("latest",state.applied(owner));
        assertEquals(Set.of("latest"),state.retained());assertNull(state.applying(owner));
        assertFalse(state.begin(owner,"latest",replacement));
    }

    @Test void disablingOneOwnerReleasesOnlyThatOwnersRuntimePackages() {
        var state=new CharacterPackageApplications();var first=UUID.randomUUID();var second=UUID.randomUUID();
        state.begin(first,"shared",null);state.confirm(first,"shared",new Object());
        state.begin(second,"shared",null);state.confirm(second,"shared",new Object());state.begin(first,"pending",new Object());
        state.forget(first);
        assertEquals(Set.of(second),state.owners());assertEquals(Set.of("shared"),state.retained());
        assertFalse(state.confirm(first,"pending",new Object()));
        state.clear();assertTrue(state.owners().isEmpty());assertTrue(state.retained().isEmpty());
    }

    @Test void editingTheOldBindingsTransformCannotActivateTheCandidateController() {
        var state=new CharacterPackageApplications();var owner=UUID.randomUUID();var oldBinding=new Object();
        state.begin(owner,"old",null);state.confirm(owner,"old",oldBinding);state.begin(owner,"candidate",oldBinding);
        assertFalse(state.confirmable(owner,"candidate",oldBinding));assertFalse(state.confirm(owner,"candidate",oldBinding));
        assertFalse(state.confirm(owner,"candidate",null));assertEquals("old",state.applied(owner));
        var replacement=new Object();assertTrue(state.confirmable(owner,"candidate",replacement));
        assertTrue(state.confirm(owner,"candidate",replacement));assertEquals("candidate",state.applied(owner));
    }
}
