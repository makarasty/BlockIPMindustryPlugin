package BlockIp;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;

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
    void waitingChallengesAreCappedAndStaleOnesSwept() {
        Challenge challenge = new Challenge(new Random(4));
        for (int i = 0; i < Challenge.MAX_PENDING; i++) assertTrue(challenge.expect("p" + i, 0));
        assertFalse(challenge.expect("one-too-many", 0));
        assertTrue(challenge.expect("p0", 1), "a uuid already waiting can be renewed");

        challenge.sweep(5 * 60_000L + 1, 5 * 60_000L);
        assertEquals(1, challenge.pendingCount());
        assertTrue(challenge.isPending("p0"));
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
