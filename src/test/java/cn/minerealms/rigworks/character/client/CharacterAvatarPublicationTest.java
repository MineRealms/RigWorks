package cn.minerealms.rigworks.character.client;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CharacterAvatarPublicationTest {
    private static final class Prepared {
        private int closed;
        private void close(){closed++;}
    }

    @Test void oldControllerRemainsPublishedWhileTheReplacementIsLoading() {
        var state=new CharacterAvatarPublication<Prepared>(Prepared::close);var owner=UUID.randomUUID();
        var old=new Prepared();state.stage(owner,"old",old);assertTrue(state.activate(owner,"old"));
        var replacement=new Prepared();state.stage(owner,"new",replacement);
        assertSame(old,state.active(owner));assertEquals(0,old.closed);assertEquals(0,replacement.closed);
        assertTrue(state.activate(owner,"new"));assertSame(replacement,state.active(owner));assertEquals(1,old.closed);
    }

    @Test void failedOrReplacedCandidatesCannotChangeOrCloseTheRunningController() {
        var state=new CharacterAvatarPublication<Prepared>(Prepared::close);var owner=UUID.randomUUID();
        var old=new Prepared();state.stage(owner,"old",old);state.activate(owner,"old");
        var failed=new Prepared();state.stage(owner,"failed",failed);state.cancel(owner);
        assertEquals(1,failed.closed);assertEquals(0,old.closed);assertSame(old,state.active(owner));
        assertFalse(state.activate(owner,"failed"));
        var obsolete=new Prepared();var latest=new Prepared();
        state.stage(owner,"obsolete",obsolete);state.stage(owner,"latest",latest);
        assertEquals(1,obsolete.closed);assertFalse(state.activate(owner,"obsolete"));assertSame(old,state.active(owner));
        assertTrue(state.activate(owner,"latest"));assertSame(latest,state.active(owner));
    }

    @Test void removingScriptsAlsoWaitsForSuccessfulApplication() {
        var state=new CharacterAvatarPublication<String>(value->{});var owner=UUID.randomUUID();
        state.stage(owner,"old","running controller");state.activate(owner,"old");
        state.stage(owner,"without-scripts","");assertEquals("running controller",state.active(owner));
        state.cancel(owner);assertEquals("running controller",state.active(owner));
        state.stage(owner,"without-scripts","");state.activate(owner,"without-scripts");assertEquals("",state.active(owner));
    }

    @Test void ownerRemovalAndLogoutReleaseEveryUnusedInterpreterExactlyOnce() {
        var state=new CharacterAvatarPublication<Prepared>(Prepared::close);var first=UUID.randomUUID();var second=UUID.randomUUID();
        var active=new Prepared();var candidate=new Prepared();var other=new Prepared();
        state.stage(first,"a",active);state.activate(first,"a");state.stage(first,"b",candidate);state.stage(second,"c",other);
        state.remove(first);assertNull(state.active(first));assertEquals(1,active.closed);assertEquals(1,candidate.closed);assertEquals(0,other.closed);
        state.clear();state.clear();assertEquals(1,other.closed);assertEquals(1,active.closed);assertEquals(1,candidate.closed);
    }
}
