package BlockIp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SessionTest {
    /** Alpha's top speed per tick. */
    private static final float SPEED = 3f;

    private static Session session() {
        return new Session("u", "1.1.1.1", "player", 0, 0, false, false);
    }

    @Test
    void walkingWithFreshSnapshotsMakesAnActiveMinute() {
        Session s = session();
        for (int i = 1; i <= 10; i++) s.sample(7, i * 10f, 0, SPEED, i * 2_000L, i * 2_000L);
        assertTrue(s.snapshot);
        assertEquals(90f, s.moved, 0.01f, "the first sample only sets where the unit is");
        assertTrue(s.takeMinute(0));
        assertFalse(s.takeMinute(0), "a new minute starts empty");
    }

    @Test
    void idlingNeverCounts() {
        Session s = session();
        for (int i = 1; i <= 30; i++) s.sample(7, 100, 100, SPEED, i * 2_000L, i * 2_000L);
        assertTrue(s.snapshot);
        assertEquals(0f, s.moved);
        assertFalse(s.takeMinute(0));
    }

    @Test
    void movementWithoutASnapshotIsNotThePlayers() {
        // A headless bot that never sends one, shoved about by the units around it
        Session s = session();
        for (int i = 1; i <= 10; i++) s.sample(7, i * 20f, 0, SPEED, 0, i * 2_000L);
        assertFalse(s.snapshot);
        assertEquals(0f, s.moved);
        // A client that sent one snapshot and then went quiet: that one sample, and nothing after
        for (int i = 11; i <= 20; i++) s.sample(7, i * 20f, 0, SPEED, 5_000, i * 2_000L);
        assertEquals(20f, s.moved, 0.01f);
    }

    @Test
    void teleportsRespawnsAndImpossibleSpeedsCountNothing() {
        Session s = session();
        s.sample(1, 0, 0, SPEED, 1, 2_000);
        s.sample(1, 5_000, 0, SPEED, 2, 4_000);
        assertEquals(0f, s.moved, "farther than the unit can go in 2 s");
        s.sample(2, 5_010, 0, SPEED, 3, 6_000);
        assertEquals(0f, s.moved, "a new unit starts from where it is");
        s.sample(2, 5_030, 0, SPEED, 4, 8_000);
        assertEquals(20f, s.moved, 0.01f);
        s.sampleDead(5, 10_000);
        s.sample(2, 5_040, 0, SPEED, 6, 12_000);
        assertEquals(20f, s.moved, 0.01f, "dead in between: no step across it");
    }

    @Test
    void anActionMakesTheMinuteActiveOnlyFromAClientThatSendsSnapshots() {
        // A bot that cannot walk, sending one free action a minute and no snapshot
        Session s = session();
        s.action(1);
        assertFalse(s.takeMinute(0));

        s.sample(7, 100, 100, SPEED, 2_000, 2_000);
        s.action(1);
        assertFalse(s.takeMinute(0), "a snapshot and an action, standing still");

        s.sample(7, 100, 100, SPEED, 3_000, 4_000);
        s.sample(7, 108, 100, SPEED, 4_000, 6_000);
        s.action(1);
        assertTrue(s.takeMinute(0), "a tile of movement with it");
        s.action(1);
        assertFalse(s.takeMinute(0), "the snapshot has to be this minute's");
    }

    @Test
    void aBuilderStandingStillCountsButOneActionAndJitterDoesNot() {
        Session s = session();
        for (int i = 1; i <= 30; i++)
            s.sample(7, i % 2 == 0 ? 6 : 0, 0, SPEED, i * 2_000L, i * 2_000L);
        s.action(1);
        assertFalse(s.takeMinute(60_000), "one action and 6 units of jitter");

        for (int i = 31; i <= 60; i++) s.sample(7, 6, 0, SPEED, i * 2_000L, i * 2_000L);
        for (int i = 0; i < 500; i++) s.action(42);
        assertFalse(s.takeMinute(120_000), "one stuck plan checked on every snapshot is one tile");

        for (int i = 61; i <= 90; i++) s.sample(7, 6, 0, SPEED, i * 2_000L, i * 2_000L);
        for (int i = 0; i < Tuning.Movement.BUILDER_ACTIONS; i++) s.action(i);
        assertTrue(s.takeMinute(180_000), "building at range without moving");
        for (int i = 0; i < 1_000; i++) s.action(i);
        assertTrue(s.minuteTiles.size <= Tuning.Movement.MAX_ACTION_TILES);
        assertFalse(s.takeMinute(240_000), "no snapshot this minute");
    }

    @Test
    void steppingBackAndForthGoesNowhere() {
        Session s = session();
        for (int i = 1; i <= 30; i++)
            s.sample(7, i % 2 == 0 ? 30 : 0, 0, SPEED, i * 2_000L, i * 2_000L);
        assertEquals(30f, s.minuteMoved, 0.01f, "the farthest from the start, not 870 of path");
        assertFalse(s.takeMinute(60_000));
        // Next minute measured from where the unit is: walking 40 from there counts
        for (int i = 31; i <= 35; i++)
            s.sample(7, 30 + (i - 30) * 10f, 0, SPEED, i * 2_000L, i * 2_000L);
        assertEquals(50f, s.minuteMoved, 0.01f, "from 30, where the last minute left it");
        assertTrue(s.takeMinute(120_000));
    }

    @Test
    void legsEitherSideOfAJumpDoNotAddUp() {
        Session s = session();
        s.sample(1, 0, 0, SPEED, 1, 2_000);
        s.sample(1, 30, 0, SPEED, 2, 4_000);
        s.sample(2, 1_000, 0, SPEED, 3, 6_000);
        s.sample(2, 1_030, 0, SPEED, 4, 8_000);
        assertEquals(30f, s.minuteMoved, 0.01f, "a respawn, then 30 again: not 60");
        assertFalse(s.takeMinute(60_000));
    }

    @Test
    void theChallengeWalkIsMeasuredFromWhereItBegan() {
        Session s = session();
        s.sample(7, 0, 0, SPEED, 1, 2_000);
        s.sample(7, 100, 0, SPEED, 2, 4_000);
        s.startChallenge();
        for (int i = 3; i <= 20; i++) s.sample(7, i % 2 == 0 ? 100 : 120, 0, SPEED, i, i * 2_000L);
        assertEquals(20f, s.challengeMoved, 0.01f, "jitter of 20 is under the 24 a walk needs");
        s.sample(7, 130, 0, SPEED, 21, 42_000);
        assertTrue(s.challengeMoved >= Tuning.Movement.CHALLENGE_DISTANCE);
    }

    @Test
    void awayUntilActiveAndAgainFiveMinutesAfter() {
        Session s = session();
        assertTrue(s.afk(Session.AFK_MS + 1), "no active minute yet");
        for (int i = 1; i <= 10; i++) s.sample(7, i * 10f, 0, SPEED, i * 2_000L, i * 2_000L);
        assertFalse(s.afk(20_000), "walking this minute");
        assertTrue(s.takeMinute(60_000));
        assertFalse(s.afk(60_000 + Session.AFK_MS));
        assertTrue(s.afk(60_000 + Session.AFK_MS + 1));
    }
}
