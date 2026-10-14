package BlockIp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KickVoteTest {
    /** Vanilla's counter and the votes held back from it, voted on as BlockPlugin and VoteSession.vote() do. */
    private int field = 1, held;

    private void vote(int lastVote, int sign, boolean mayPass) {
        int real = field + held;
        int counter = KickVote.counter(real, lastVote, sign, mayPass);
        held = real - counter;
        field = counter - lastVote + sign;
    }

    @Test
    void theFieldsAreThereInV1597() {
        assertTrue(KickVote.available(), "NetServer$VoteSession target, voted, votes");
    }

    @Test
    void neededIsAShareOfTheActivePlayersNeverUnderThree() {
        assertEquals(3, KickVote.needed(4, 0.4));
        assertEquals(4, KickVote.needed(10, 0.4));
        assertEquals(12, KickVote.needed(30, 0.4));
        assertFalse(KickVote.mayPass(15, false, true, false, 20, 0.4), "fifteen farmed bots, no trusted one");
        assertFalse(KickVote.mayPass(3, true, true, false, 20, 0.4), "a trusted one, too few");
        assertTrue(KickVote.mayPass(8, true, true, false, 20, 0.4));
    }

    @Test
    void theCounterStaysUnderEveryVotesRequiredUntilItMayPass() {
        // The initiator's vote is in (field 1); fifteen bots vote yes: never 2, the least votesRequired() is
        for (int i = 0; i < 15; i++) {
            vote(0, 1, false);
            assertTrue(field <= 1, "after vote " + i + ": " + field);
        }
        assertEquals(16, field + held, "every vote is kept");
        // One switches to no while held: it takes its vote back, as vanilla would
        vote(1, -1, false);
        assertTrue(field <= 1);
        assertEquals(14, field + held);
        // A trusted regular joins in: the whole count is given back, and vanilla passes it
        vote(0, 1, true);
        assertEquals(15, field);
        assertEquals(0, held);
    }

    @Test
    void aKickOfAnyoneTakesATrustedYesOnceTrustIsKept() {
        assertFalse(KickVote.mayPass(3, false, false, false, 8, 0.4), "three farmed bots kicking a newcomer");
        assertTrue(KickVote.mayPass(2, true, false, false, 8, 0.4), "a regular joins in: vanilla's own count decides");
        assertTrue(KickVote.mayPass(2, false, false, true, 8, 0.4), "trust is new, nobody is trusted: vanilla's count");
        assertFalse(KickVote.mayPass(15, false, true, true, 20, 0.4), "a whitelisted target is held even then");
    }

    @Test
    void essentialsVotesAndAnUnheldVanillaKickNeedATrustedStarter() {
        assertTrue(BlockPlugin.startsUnheldVote("evote", true), "gameover, back, map, skip, random, kick");
        assertTrue(BlockPlugin.startsUnheldVote("evotekick", true));
        assertTrue(BlockPlugin.startsUnheldVote("votemap", true));
        assertFalse(BlockPlugin.startsUnheldVote("votekick", true), "held instead");
        assertTrue(BlockPlugin.startsUnheldVote("votekick", false));
        assertFalse(BlockPlugin.startsUnheldVote("vote", false));
        assertTrue(BlockPlugin.startsUnheldVote("rtv", true), "Essentials ends the map at its share of /rtv");
    }

    @Test
    void anOpenRtvTakesMoreVotesWithoutATrustedPlayer() {
        long start = 1_000_000L;
        assertFalse(BlockPlugin.rtvOpen(0, start), "none opened");
        assertTrue(BlockPlugin.rtvOpen(start, start));
        assertTrue(BlockPlugin.rtvOpen(start, start + Tuning.Votes.RTV_OPEN_MS - 1));
        assertFalse(BlockPlugin.rtvOpen(start, start + Tuning.Votes.RTV_OPEN_MS), "timed out: the next opens a new one");
    }

    @Test
    void aPlayerAloneMayOnlyChangeTheMapOrSkipWaves() {
        assertTrue(BlockPlugin.harmlessAlone("votemap", new String[]{"3"}));
        assertTrue(BlockPlugin.harmlessAlone("rtv", new String[0]));
        assertTrue(BlockPlugin.harmlessAlone("evote", new String[]{"map", "3", "r"}));
        assertTrue(BlockPlugin.harmlessAlone("evote", new String[]{"skip", "5"}));
        for (String kind : new String[]{"back", "gg", "draw", "random", "kick", "MAP"}) {
            assertFalse(BlockPlugin.harmlessAlone("evote", new String[]{kind, "x"}), kind);
        }
        assertFalse(BlockPlugin.harmlessAlone("evote", new String[0]));
        assertFalse(BlockPlugin.harmlessAlone("evotekick", new String[]{"x"}));
        assertFalse(BlockPlugin.harmlessAlone("votekick", new String[]{"x", "r"}));
    }

    @Test
    void essentialsKickVotesStartAKickToo() {
        assertTrue(BlockPlugin.startsKick("votekick", new String[]{"x", "r"}));
        assertTrue(BlockPlugin.startsKick("evotekick", new String[]{"x"}));
        assertTrue(BlockPlugin.startsKick("evote", new String[]{"kick", "x", "r"}));
        assertFalse(BlockPlugin.startsKick("evote", new String[]{"map", "x"}));
        assertFalse(BlockPlugin.startsKick("evote", new String[0]));
        assertFalse(BlockPlugin.startsKick("vote", new String[]{"y"}));
    }
}
