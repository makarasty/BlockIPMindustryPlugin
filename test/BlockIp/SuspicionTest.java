package BlockIp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuspicionTest {
    private static final ConfigManager.ConfigData CONFIG = new ConfigManager.ConfigData();
    private static final long NOW = 10 * 60_000L;

    private static TrustStore.Record record(int minutes, int days) {
        TrustStore.Record r = new TrustStore.Record();
        r.minutes = minutes;
        r.days = days;
        return r;
    }

    private static int score(Session s, TrustStore.Record r, List<Session> online) {
        return Suspicion.score(s, r, false, false, online, NOW, CONFIG, new StringBuilder());
    }

    /** The raid of 2026-10-13: short digit names, one client build, all within seconds, none moving. */
    private static List<Session> raidCohort() {
        List<Session> online = new ArrayList<>();
        String[] names = {"000x", "0016", "001s", "004m", "0054", "004q", "002k"};
        for (int i = 0; i < names.length; i++) online.add(new Session("bot" + i, "9.9.9." + i, names[i], i * 1_000L, 42, false, true));
        return online;
    }

    @Test
    void theRaidsBotsScoreHighAndCannotVote() {
        List<Session> online = raidCohort();
        StringBuilder why = new StringBuilder();
        int score = Suspicion.score(online.get(3), null, false, false, online, NOW, CONFIG, why);
        assertEquals(85, score, why.toString());
        assertTrue(Suspicion.inCohort(online.get(3), online, CONFIG));
        assertNotNull(Suspicion.voteRefusal(false, false, false, false, false, 0, CONFIG.voteCastMinutes, score, 0, 0, CONFIG));
    }

    @Test
    void aCohortFarmedPastTheVoteMinimumStillCountsAsABurstAndCannotVote() {
        long started = 0, now = 2 * 3_600_000L;
        TrustStore.Record farmed = record(40, 1);
        farmed.lastSeen = now - Suspicion.RECONNECT_MS - 1;
        assertTrue(Suspicion.burstJoin(true, farmed, started, now, CONFIG), "40 active minutes, not trusted: counts");
        assertFalse(Suspicion.burstJoin(false, farmed, started, now, CONFIG), "a trusted player never does");
        farmed.lastSeen = now - 60_000;
        assertFalse(Suspicion.burstJoin(true, farmed, started, now, CONFIG), "active a minute ago: reconnecting");
        assertFalse(Suspicion.burstJoin(true, null, now - 60_000, now, CONFIG), "the server just started");
        TrustStore.Record walkedAMinute = record(1, 1);
        walkedAMinute.lastSeen = now - 60_000;
        assertTrue(Suspicion.burstJoin(true, walkedAMinute, started, now, CONFIG), "a bot that walked a minute, back from a fresh address");

        // Six of them in 30 s, walking and sending snapshots like players: in the cohort, and kept out of votes
        List<Session> online = raidCohort().subList(0, CONFIG.raidJoins);
        for (Session s : online) {
            s.snapshot = true;
            s.moved = 500;
        }
        StringBuilder why = new StringBuilder();
        int score = Suspicion.score(online.get(3), record(40, 1), false, false, online, NOW, CONFIG, why);
        assertTrue(Suspicion.inCohort(online.get(3), online, CONFIG));
        assertTrue(score >= CONFIG.voteMaxSuspicion, why.toString());
        assertNotNull(Suspicion.voteRefusal(false, false, true, false, false, 40, CONFIG.voteCastMinutes, score, 0, 0, CONFIG));
    }

    @Test
    void aClientThatNeverSentASnapshotCannotVoteWhateverItEarned() {
        // An idle bot that farmed the minutes anyway, joined slowly, outside any burst: under the max score
        Session s = new Session("u", "1.1.1.1", "004m", NOW - 120_000, 7, false, false);
        int score = score(s, record(40, 1), List.of(s));
        assertTrue(score < CONFIG.voteMaxSuspicion, "score " + score);
        assertEquals("no client snapshot", Suspicion.voteRefusal(false, false, false, false, false, 40, CONFIG.voteCastMinutes, score, 0, 0, CONFIG));
    }

    @Test
    void theBurstThatStartedRaidModeIsEveryBurstJoinWithinItsWindow() {
        long now = 100_000;
        Session member = new Session("b", "1", "004m", now - CONFIG.raidJoinSeconds * 1000L, 7, false, true);
        assertTrue(Suspicion.cameWithBurst(member, now, CONFIG));
        assertFalse(Suspicion.inCohort(member, List.of(member), CONFIG), "alone online, the rest still loading");
        assertFalse(Suspicion.cameWithBurst(new Session("o", "2", "x", now - CONFIG.raidJoinSeconds * 1000L - 1, 7, false, true), now, CONFIG));
        assertFalse(Suspicion.cameWithBurst(new Session("r", "3", "x", now, 7, false, false), now, CONFIG), "a regular reconnecting");
    }

    @Test
    void noWeakSignalAloneKeepsAPlayerWithActiveTimeOutOfVotes() {
        // A short digit name, on a VPN, never moved yet this session: still under the max with 30+ active minutes
        Session s = new Session("u", "1.1.1.1", "k1", NOW - 120_000, 7, true, false);
        s.snapshot = true;
        int score = score(s, record(40, 1), List.of(s));
        assertTrue(score < CONFIG.voteMaxSuspicion, "score " + score);
        assertNull(Suspicion.voteRefusal(false, false, true, false, false, 40, CONFIG.voteCastMinutes, score, 0, 0, CONFIG));
        assertFalse(Suspicion.botLikeName("k"));
        assertFalse(Suspicion.botLikeName("svo"));
        assertTrue(Suspicion.botLikeName("[red]004m"));
    }

    @Test
    void realTimeOnSeveralDaysAndAPassTurnItDown() {
        Session s = new Session("u", "1.1.1.1", "Fance", NOW - 120_000, 7, true, false);
        s.snapshot = true;
        s.moved = 500;
        s.passedChallenge = true;
        assertEquals(0, score(s, record(900, 5), List.of(s)));
        assertEquals(0, Suspicion.score(s, null, true, true, List.of(s), NOW, CONFIG, new StringBuilder()), "admin");
    }

    @Test
    void aBurstNeedsEnoughJoinersCloseTogether() {
        List<Session> online = new ArrayList<>();
        for (int i = 0; i < CONFIG.raidJoins - 1; i++) online.add(new Session("p" + i, "a" + i, "n" + i, i * 1_000L, i, false, true));
        assertFalse(Suspicion.inCohort(online.get(0), online, CONFIG));
        online.add(new Session("late", "b", "late", CONFIG.raidJoinSeconds * 1000L + 10_000, 9, false, true));
        assertFalse(Suspicion.inCohort(online.get(0), online, CONFIG), "joined too long after");
        online.add(new Session("last", "c", "last", 5_000, 9, false, true));
        assertTrue(Suspicion.inCohort(online.get(0), online, CONFIG));
        // A regular reconnecting with them is not a burst join, and not in the cohort
        Session regular = new Session("r", "d", "regular", 2_000, 9, false, false);
        assertFalse(Suspicion.inCohort(regular, online, CONFIG));
    }

    @Test
    void regularsMovingOverFromACrashedSiblingAreNoCohort() {
        // Varied clients and names, all within seconds: what a sibling server crashing looks like
        List<Session> online = new ArrayList<>();
        String[] names = {"Fance", "Athos", "|TwX|twixun", "PÇpŕòpeŗ", "Kitsune", "лун", "jonasito"};
        for (int i = 0; i < names.length; i++) online.add(new Session("p" + i, "a" + i, names[i], i * 1_000L, 100 + i, false, true));
        for (Session s : online) assertFalse(Suspicion.inCohort(s, online, CONFIG), s.name);
        assertTrue(Suspicion.inCohort(raidCohort().get(0), raidCohort(), CONFIG));
    }

    @Test
    void namesThatLookAlike() {
        assertEquals("999a", Suspicion.shape("004m"));
        assertTrue(Suspicion.similar(Suspicion.shape("004m"), "004m", "001s"));
        assertTrue(Suspicion.similar(Suspicion.shape("0016"), "0016", "004m"), "same length, same first two");
        assertFalse(Suspicion.similar(Suspicion.shape("Fance"), "Fance", "Athos"));
    }

    /** The comparison before names were worked out once per session, kept to check the cached one against. */
    private static boolean similarAsBefore(String name, String other) {
        String shape = Suspicion.shape(name);
        if (shape.indexOf('9') >= 0 && shape.equals(Suspicion.shape(other))) return true;
        String a = arc.util.Strings.stripColors(name).toLowerCase(java.util.Locale.ROOT);
        String b = arc.util.Strings.stripColors(other).toLowerCase(java.util.Locale.ROOT);
        return a.length() == b.length() && a.length() <= 8 && a.length() >= 2 && a.startsWith(b.substring(0, 2));
    }

    @Test
    void sessionNamesCompareAsBefore() {
        // Colours, mixed case, Cyrillic look-alikes, fullwidth digits, a dotted capital I that lower-cases to two chars
        String[] names = {"004m", "[red]001s", "[#ff0000]00[]4M", "0016", "ОО4m", "００4m", "Fance", "Athos", "FANCE",
                "[blue]fa[]nce", "İstan", "istan", "Iİ", "ii", "k", "[red]", "", "x1", "X1", "verylongname123456789",
                "verylongname123456780", "a b", "a_b", "[[red]]00", "0O0o"};
        for (String a : names) {
            Session s = new Session("a", "1", a, 0, 7, false, true);
            assertEquals(Suspicion.shape(a), s.shape, a);
            assertEquals(Suspicion.botLikeName(a), s.botLike, a);
            for (String b : names) {
                Session o = new Session("b", "2", b, 0, 7, false, true);
                assertEquals(similarAsBefore(a, b), Suspicion.similar(s.shape, s.plain, o.shape, o.plain), a + " / " + b);
                assertEquals(similarAsBefore(a, b), Suspicion.similar(Suspicion.shape(a), a, b), a + " / " + b);
            }
        }
    }

    @Test
    void startingAKickTakesMoreActiveMinutesThanCastingAVote() {
        // A regular in their first session after the deploy: casts at 10 active minutes, starts a kick at 20
        Session s = new Session("u", "1.1.1.1", "Fance", NOW - 120_000, 7, false, false);
        s.snapshot = true;
        s.moved = 500;
        int score = score(s, record(CONFIG.voteCastMinutes, 1), List.of(s));
        assertTrue(score < CONFIG.voteMaxSuspicion, "score " + score);
        assertNull(Suspicion.voteRefusal(false, false, true, false, false, CONFIG.voteCastMinutes, CONFIG.voteCastMinutes, score, 0, 0, CONFIG), "/vote y, /rtv");
        assertNotNull(Suspicion.voteRefusal(false, false, true, false, false, CONFIG.voteCastMinutes, CONFIG.voteStartMinutes, score, 0, 0, CONFIG), "/votekick");
        assertNull(Suspicion.voteRefusal(false, false, true, false, false, CONFIG.voteStartMinutes, CONFIG.voteStartMinutes, score, 0, 0, CONFIG));
        assertNotNull(Suspicion.voteRefusal(false, true, true, false, true, 999, CONFIG.voteStartMinutes, 0, 0, 0, CONFIG), "a pending challenge");
    }

    @Test
    void onlyVotesThatLookLikeARaidsMakeAChorus() {
        Session regular = new Session("r", "1", "Fance", 0, 7, false, false);
        assertFalse(Suspicion.chorusVoice(regular, false, false, 100, false, false), "a regular the gate lets vote: /rtv at map end");
        assertTrue(Suspicion.chorusVoice(regular, false, false, 10, false, true), "one the gate refuses");
        assertTrue(Suspicion.chorusVoice(regular, false, true, 100, false, false), "flagged");
        regular.coordinated = 1;
        assertTrue(Suspicion.chorusVoice(regular, false, false, 100, false, false), "coordinated before");
        assertTrue(Suspicion.chorusVoice(new Session("b", "2", "004m", 0, 7, false, true), true, false, 100, false, false), "joined in a burst");
        assertFalse(Suspicion.chorusVoice(new Session("j", "2", "Athos", 0, 7, false, true), false, false, 100, false, false),
                "a join that counted toward a burst, with none online: most regulars' joins");

        // Trust just started: every regular is under the minimum, only a uuid with no active minute counts
        assertFalse(Suspicion.chorusVoice(new Session("n", "3", "Athos", 0, 7, false, false), false, false, 5, true, true));
        assertTrue(Suspicion.chorusVoice(new Session("x", "4", "0016", 0, 7, false, false), false, false, 0, true, true));
        // The chat chorus asks with refused = low trust: fifteen bots that walked a minute each, joined a few at a time
        assertTrue(Suspicion.chorusVoice(new Session("g", "5", "0016", 0, 7, false, false), false, false, 1, false, true));
        assertFalse(Suspicion.chorusVoice(new Session("g", "5", "0016", 0, 7, false, false), false, false, 1, true, true), "trust is new");
    }

    @Test
    void newcomersShortOfMinutesCallingRtvTogetherAreNoRaid() {
        Session newcomer = new Session("n", "1", "Athos", 0, 7, false, false);
        newcomer.snapshot = true;
        newcomer.moved = 500;
        int score = score(newcomer, record(5, 1), List.of(newcomer));
        assertNotNull(Suspicion.voteRefusal(false, false, true, false, false, 5, CONFIG.voteCastMinutes, score, 0, 0, CONFIG), "refused, on minutes");
        assertFalse(Suspicion.chorusVoice(newcomer, false, false, 5, false, Suspicion.refusedAsRaider(newcomer.snapshot, score, CONFIG)));
        assertTrue(Suspicion.refusedAsRaider(false, score, CONFIG), "a headless bot sends no snapshot");
        assertTrue(Suspicion.refusedAsRaider(true, CONFIG.voteMaxSuspicion, CONFIG));
    }

    @Test
    void theVoteRules() {
        ConfigManager.ConfigData c = CONFIG;
        assertNull(Suspicion.voteRefusal(true, true, false, true, false, 0, c.voteCastMinutes, 100, 0, 0, c), "an admin always votes ('/vote c' too)");
        assertEquals("challenge pending", Suspicion.voteRefusal(false, true, true, false, true, 999, c.voteCastMinutes, 0, 0, 0, c));
        assertNotNull(Suspicion.voteRefusal(false, false, false, false, true, 999, c.voteCastMinutes, 0, 0, 0, c), "no snapshot: not a client playing");
        assertNotNull(Suspicion.voteRefusal(false, false, true, true, false, 999, c.voteCastMinutes, 0, 0, 0, c), "raid mode: trusted only");
        assertNull(Suspicion.voteRefusal(false, false, true, true, true, 999, c.voteCastMinutes, 0, 0, 0, c));
        assertNotNull(Suspicion.voteRefusal(false, false, true, false, false, c.voteCastMinutes - 1, c.voteCastMinutes, 0, 0, 0, c));
        assertNotNull(Suspicion.voteRefusal(false, false, true, false, false, 999, c.voteCastMinutes, c.voteMaxSuspicion, 0, 0, c));
        assertNotNull(Suspicion.voteRefusal(false, false, true, false, false, 999, c.voteCastMinutes, 0, 2_000, 1_000, c), "joined after the vote began");
        assertNull(Suspicion.voteRefusal(false, false, true, false, false, 999, c.voteCastMinutes, 0, 500, 1_000, c));
    }

    @Test
    void playingTakesASnapshotAndRealWalking() {
        Session s = new Session("u", "1.1.1.1", "hello", 0, 7, false, false);
        assertFalse(Suspicion.playing(null));
        assertFalse(Suspicion.playing(s));
        s.snapshot = true;
        assertFalse(Suspicion.playing(s), "a client that sends snapshots but stands still");
        s.moved = Session.ACTIVE_DISTANCE;
        assertTrue(Suspicion.playing(s));
        s.snapshot = false;
        assertFalse(Suspicion.playing(s));
    }
}
