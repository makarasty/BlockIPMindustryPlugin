package BlockIp;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChallengeTest {
    private static final long HOUR = 3_600_000;

    private static int indexOfWord(Challenge challenge, String uuid) {
        return Arrays.asList(challenge.options(uuid)).indexOf(challenge.word(uuid));
    }

    @Test
    void theRightButtonPassesAndIsRemembered() {
        Challenge challenge = new Challenge(new Random(1));
        assertTrue(challenge.expect("u1", 0));
        assertTrue(challenge.isPending("u1"));

        String[] options = challenge.options("u1");
        assertEquals(Challenge.BUTTONS, new HashSet<>(Arrays.asList(options)).size());
        assertSame(options, challenge.options("u1"));

        assertEquals(Challenge.Verdict.pass, challenge.answer("u1", "1.1.1.1", indexOfWord(challenge, "u1"), 10, 24 * HOUR));
        assertFalse(challenge.isPending("u1"));
        assertTrue(challenge.hasPassed("u1", "1.1.1.1", 24 * HOUR));
        assertFalse(challenge.hasPassed("u1", "1.1.1.1", 24 * HOUR + 10));
        // The pass belongs to the uuid and the address together
        assertFalse(challenge.hasPassed("u1", "2.2.2.2", 20));
        assertFalse(challenge.hasPassed("someone-else", "1.1.1.1", 20));
    }

    @Test
    void aWrongButtonFailsAndClosingShowsItAgain() {
        Challenge challenge = new Challenge(new Random(2));
        challenge.expect("u2", 0);
        challenge.options("u2");
        int right = indexOfWord(challenge, "u2");

        assertEquals(Challenge.Verdict.reshow, challenge.answer("u2", "a", -1, 1, HOUR));
        assertTrue(challenge.isPending("u2"));
        assertEquals(Challenge.Verdict.fail, challenge.answer("u2", "a", (right + 1) % Challenge.BUTTONS, 2, HOUR));
        assertFalse(challenge.isPending("u2"));
        assertFalse(challenge.hasPassed("u2", "a", 3));
        assertEquals(Challenge.Verdict.none, challenge.answer("u2", "a", right, 4, HOUR));
    }

    @Test
    void aTimerOnlyExpiresTheChallengeItWasSetFor() {
        Challenge challenge = new Challenge(new Random(3));
        challenge.expect("u3", 0);
        challenge.options("u3");
        Object first = challenge.token("u3");
        challenge.leave("u3");
        challenge.expect("u3", 1_000);
        challenge.options("u3");

        assertFalse(challenge.expire("u3", first));
        assertTrue(challenge.isPending("u3"));
        assertTrue(challenge.expire("u3", challenge.token("u3")));
        assertFalse(challenge.isPending("u3"));
    }

    @Test
    void aConnectionThatClosesOrNeverJoinsIsDroppedAndHandedBack() {
        Challenge challenge = new Challenge(new Random(6));
        Set<Object> closed = new HashSet<>();
        List<String> dropped = new ArrayList<>();
        Object slow = "con-slow", ghost = "con-ghost", stuck = "con-stuck";
        challenge.expect("slow", 0, false, slow, null);
        challenge.options("slow");
        challenge.expect("ghost", 0, false, ghost, null);
        challenge.expect("stuck", 0, false, stuck, null);
        challenge.expect("in-game", 0, true);

        closed.add(ghost);
        challenge.dropNeverJoined(1_000, 180_000, c -> !closed.contains(c), (uuid, c) -> dropped.add(uuid + "@" + c));
        assertEquals(List.of("ghost@con-ghost"), dropped, "closed before joining: dropped at once");

        closed.add(slow);
        challenge.dropNeverJoined(180_001, 180_000, c -> !closed.contains(c), (uuid, c) -> dropped.add(uuid + "@" + c));
        assertEquals(List.of("ghost@con-ghost", "stuck@con-stuck"), dropped, "still not in past the grace");
        assertTrue(challenge.isPending("slow"), "joined, so its own join deadline decides");
        assertTrue(challenge.isPending("in-game"));
        assertFalse(challenge.isPending("ghost"));
    }

    @Test
    void oneUuidFromManyConnectionsGetsOneChallenge() {
        Challenge challenge = new Challenge(new Random(8));
        assertTrue(challenge.expect("flagged", 0, false, "con1", null));
        assertFalse(challenge.expect("flagged", 1, false, "con2", null), "still waiting on the first, which has not joined");
        assertEquals(1, challenge.pendingCount());
        challenge.dropNeverJoined(2, 180_000, c -> false, (uuid, c) -> assertEquals("con1", c));
        assertTrue(challenge.expect("flagged", 3, false, "con2", null), "the first closed: the next may try");
        challenge.joined("flagged");
        assertTrue(challenge.expect("flagged", 4, false, "con3", null), "one that joined can be challenged anew");
    }

    @Test
    void clientsThatNeverJoinCannotHoldEveryPlace() {
        Challenge challenge = new Challenge(new Random(4));
        List<String> dropped = new ArrayList<>();
        // Loaders that take the map and never confirm fill every place
        for (int i = 0; i < Challenge.MAX_PENDING; i++) {
            assertTrue(challenge.expect("loader" + i, i, false, "con" + i, (uuid, c) -> dropped.add(uuid)));
        }
        assertTrue(challenge.expect("newcomer", 1_000, true, "con-new", (uuid, c) -> dropped.add(uuid + "@" + c)),
                "a real newcomer still gets in");
        assertEquals(List.of("loader0@con0"), dropped, "the one waiting longest makes room");
        assertEquals(Challenge.MAX_PENDING, challenge.pendingCount());

        // Players in game take no place, however full it is
        for (int i = 0; i < 60; i++) assertTrue(challenge.expect("player" + i, 2_000, true));
        assertEquals(1, dropped.size());
        challenge.joined("newcomer");
        assertTrue(challenge.expect("one-more", 3_000, false, "con-more", (uuid, c) -> dropped.add(uuid)));
        assertEquals(1, dropped.size(), "the newcomer joined, which left a place");
        assertTrue(challenge.expect("two-more", 3_000, false, "con-two", (uuid, c) -> dropped.add(uuid)));
        assertEquals(List.of("loader0@con0", "loader1"), dropped);

        challenge.sweep(5 * 60_000L + 2_500, 5 * 60_000L);
        assertEquals(2, challenge.pendingCount(), "stale ones swept");
        assertTrue(challenge.isPending("one-more"));
    }

    @Test
    void leavingAnOpenChallengeInGameIsFailingIt() throws ReflectiveOperationException {
        Challenge challenge = new Challenge(new Random(9));
        challenge.expect("bot", 0, true, "connection", null);
        assertFalse(challenge.leave("bot"), "still to join: left to the sweep of those that never join");
        assertTrue(challenge.isPending("bot"));
        challenge.joined("bot");
        // The deadline timer's token no longer holds the connection, closed or not
        Object token = challenge.token("bot");
        Field connection = token.getClass().getDeclaredField("connection");
        connection.setAccessible(true);
        assertNull(connection.get(token));

        assertTrue(challenge.leave("bot"));
        assertFalse(challenge.isPending("bot"));
        assertFalse(challenge.expire("bot", token), "its timer then finds nothing to fail");
        assertFalse(challenge.leave("bot"), "nothing open: an ordinary leave");
    }

    @Test
    void aRaidChallengeCannotBeAnsweredBeforeItsButtonsAreOut() {
        Challenge challenge = new Challenge(new Random(7));
        assertTrue(challenge.expect("raider", 0, true));
        assertTrue(challenge.waitsForMove("raider"));
        // A script that answers straight away, whatever button: nothing to answer yet
        for (int option = 0; option < Challenge.BUTTONS; option++) {
            assertEquals(Challenge.Verdict.none, challenge.answer("raider", "a", option, 1, HOUR));
        }
        assertTrue(challenge.isPending("raider"));

        challenge.options("raider");
        assertFalse(challenge.waitsForMove("raider"));
        assertEquals(Challenge.Verdict.pass, challenge.answer("raider", "a", indexOfWord(challenge, "raider"), 2, HOUR));

        challenge.expect("plain", 0);
        assertFalse(challenge.waitsForMove("plain"), "outside raid mode the buttons come at join");
    }

    @Test
    void noChallengeMeansNothingToShowOrAnswer() {
        Challenge challenge = new Challenge(new Random(5));
        assertNull(challenge.options("nobody"));
        assertNull(challenge.word("nobody"));
        assertFalse(challenge.isPending(null));
        assertFalse(challenge.expect(null, 0));
        assertEquals(Challenge.Verdict.none, challenge.answer("nobody", "a", 0, 0, HOUR));
        challenge.expect("u5", 0);
        assertNotNull(challenge.options("u5"));
    }
}
