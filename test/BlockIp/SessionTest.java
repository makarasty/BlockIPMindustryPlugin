package BlockIp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        s.minuteAction = true;
        assertFalse(s.takeMinute(0));

        s.sample(7, 100, 100, SPEED, 2_000, 2_000);
        s.minuteAction = true;
        assertTrue(s.takeMinute(0));
        s.minuteAction = true;
        assertFalse(s.takeMinute(0), "the snapshot has to be this minute's");
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
