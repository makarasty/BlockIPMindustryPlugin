package BlockIp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KickVoteTest {
    /**
     * Vanilla's counter and the votes held back from it, voted on as BlockPlugin and
     * VoteSession.vote() do.
     */
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
        assertFalse(
                KickVote.mayPass(15, false, true, false, 20, 0.4),
                "fifteen farmed bots, no trusted one");
        assertFalse(KickVote.mayPass(3, true, true, false, 20, 0.4), "a trusted one, too few");
        assertTrue(KickVote.mayPass(8, true, true, false, 20, 0.4));
    }

    @Test
    void theCounterStaysUnderEveryVotesRequiredUntilItMayPass() {
        // The initiator's vote is in (field 1); fifteen bots vote yes: never 2, the least
        // votesRequired() is
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
        assertFalse(
                KickVote.mayPass(3, false, false, false, 8, 0.4),
                "three farmed bots kicking a newcomer");
        assertTrue(
                KickVote.mayPass(2, true, false, false, 8, 0.4),
                "a regular joins in: vanilla's own count decides");
        assertTrue(
                KickVote.mayPass(2, false, false, true, 8, 0.4),
                "trust is new, nobody is trusted: vanilla's count");
        assertFalse(
                KickVote.mayPass(15, false, true, true, 20, 0.4),
                "a whitelisted target is held even then");
    }

    @Test
    void essentialsVotesAndAnUnheldVanillaKickNeedATrustedStarter() {
        assertTrue(
                BlockPlugin.startsUnheldVote("evote", true),
                "gameover, back, map, skip, random, kick");
        assertTrue(BlockPlugin.startsUnheldVote("evotekick", true));
        assertTrue(BlockPlugin.startsUnheldVote("votemap", true));
        assertFalse(BlockPlugin.startsUnheldVote("votekick", true), "held instead");
        assertTrue(BlockPlugin.startsUnheldVote("votekick", false));
        assertFalse(BlockPlugin.startsUnheldVote("vote", false));
        assertFalse(
                BlockPlugin.startsUnheldVote("rtv", false),
                "cast rules; trusted-only in a locked raid anyway");
    }

    @Test
    void aPlayerAloneMayOnlyChangeTheMapOrSkipWaves() {
        assertTrue(BlockPlugin.harmlessAlone("votemap", new String[] {"3"}));
        assertTrue(BlockPlugin.harmlessAlone("evote", new String[] {"map", "3", "r"}));
        assertTrue(BlockPlugin.harmlessAlone("evote", new String[] {"skip", "5"}));
        assertTrue(BlockPlugin.harmlessAlone("rtv", new String[0]));
        for (String kind : new String[] {"back", "gg", "draw", "random", "kick", "MAP"}) {
            assertFalse(BlockPlugin.harmlessAlone("evote", new String[] {kind, "x"}), kind);
        }
        assertFalse(BlockPlugin.harmlessAlone("evote", new String[0]));
        assertFalse(BlockPlugin.harmlessAlone("evotekick", new String[] {"x"}));
        assertFalse(BlockPlugin.harmlessAlone("votekick", new String[] {"x", "r"}));
    }

    @Test
    void aPlayerAloneChangesTheMapWithoutActiveMinutes() {
        ConfigManager.ConfigData c = new ConfigManager.ConfigData();
        assertEquals(0, BlockPlugin.voteMinutes(false, true, c), "/votemap, /rtv alone");
        assertEquals(c.voteCastMinutes, BlockPlugin.voteMinutes(false, false, c));
        assertEquals(c.voteStartMinutes, BlockPlugin.voteMinutes(true, true, c), "a kick never");
        // A newcomer in their first minute: alone, only the minutes stood in the way; the rest
        // still refuses
        int score = Tuning.Suspicion.NO_ACTIVE_TIME_POINTS;
        assertNull(Suspicion.voteRefusal(false, false, true, false, false, 0, 0, score, 0, 0, c));
        assertEquals(
                "challenge pending",
                Suspicion.voteRefusal(false, true, true, false, false, 0, 0, score, 0, 0, c));
        assertNotNull(
                Suspicion.voteRefusal(false, false, false, false, false, 0, 0, score, 0, 0, c),
                "a headless bot");
        assertNotNull(
                Suspicion.voteRefusal(false, false, true, true, false, 0, 0, score, 0, 0, c),
                "raid mode");
    }

    /**
     * A fresh uuid alone on an empty server must not reload the map with every command: one free
     * pass a while.
     */
    @Test
    void theSoloFreePassComesOncePerWindowAndHoldsOnlyForItsOwnRtv() {
        BlockPlugin plugin = new BlockPlugin();
        long t = 1_000_000_000L;
        assertTrue(plugin.soloPassOpen(t));
        plugin.offerSoloPass("uuid-a", t);
        assertTrue(plugin.soloPassOpen(t + 1_000), "a refused /rtv 99 spends nothing");
        // /rtv: the game ends inside the command, the next map loads 12 s later
        plugin.soloMapChanged(t + 5);
        plugin.soloMapChanged(t + 12_000);

        assertFalse(plugin.soloPassOpen(t + 1_000), "a second change right after");
        assertTrue(plugin.holdsSoloPass("uuid-a", t + 1_000), "Essentials' check of the same /rtv");
        assertFalse(plugin.holdsSoloPass("uuid-b", t + 1_000));
        assertFalse(plugin.holdsSoloPass("uuid-a", t + Tuning.Votes.SOLO_HOOK_MS), "a later /rtv");
        assertTrue(plugin.soloPassOpen(t + Tuning.Votes.SOLO_FREE_MS));

        // Refused by Essentials: the offer is withdrawn after the command, so a core dying a second
        // later spends nothing
        long u = t + Tuning.Votes.SOLO_FREE_MS;
        plugin.offerSoloPass("uuid-c", u);
        plugin.withdrawSoloOffer();
        plugin.soloMapChanged(u + 1_000);
        assertTrue(plugin.soloPassOpen(u + 2_000));
        assertFalse(plugin.holdsSoloPass("uuid-c", u + 10));
    }

    @Test
    void essentialsKickVotesStartAKickToo() {
        assertTrue(BlockPlugin.startsKick("votekick", new String[] {"x", "r"}));
        assertTrue(BlockPlugin.startsKick("evotekick", new String[] {"x"}));
        assertTrue(BlockPlugin.startsKick("evote", new String[] {"kick", "x", "r"}));
        assertFalse(BlockPlugin.startsKick("evote", new String[] {"map", "x"}));
        assertFalse(BlockPlugin.startsKick("evote", new String[0]));
        assertFalse(BlockPlugin.startsKick("vote", new String[] {"y"}));
    }
}
