package BlockIp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChorusTest {
    @Test
    void distinctSendersWithinTheWindowAreCounted() {
        Chorus chorus = new Chorus();
        assertEquals(1, chorus.add("votekick k", "bot1", 0, 3_000).size());
        assertEquals(
                1,
                chorus.add("votekick k", "bot1", 100, 3_000).size(),
                "one sender repeating is one");
        assertEquals(2, chorus.add("votekick k", "bot2", 200, 3_000).size());
        assertEquals(3, chorus.add("votekick k", "bot3", 300, 3_000).size());
        assertEquals(1, chorus.add("vote y", "bot1", 300, 3_000).size(), "keys are apart");
        // Past the window the early ones are gone
        assertEquals(2, chorus.add("votekick k", "bot4", 3_250, 3_000).size());
    }

    @Test
    void sendersComeOldestFirstSoTheOneEchoedIsKnown() {
        Chorus chorus = new Chorus();
        chorus.add("helo", "newcomer", 0, 10_000);
        chorus.add("helo", "bot1", 100, 10_000);
        assertEquals(
                List.of("newcomer", "bot1", "bot2"),
                new ArrayList<>(chorus.add("helo", "bot2", 200, 10_000)));
        assertTrue(chorus.heard("helo", "newcomer", 300, 10_000));
        assertFalse(chorus.heard("helo", "bot3", 300, 10_000));
        assertEquals(
                List.of("newcomer", "bot1", "bot2"),
                new ArrayList<>(chorus.add("helo", "newcomer", 300, 10_000)),
                "saying it again keeps the place first heard");
        // The window runs from the last time each was heard
        assertEquals(
                List.of("newcomer", "bot3"),
                new ArrayList<>(chorus.add("helo", "bot3", 10_250, 10_000)));
    }

    @Test
    void floodsOfKeysAndSendersStayBounded() {
        Chorus chorus = new Chorus();
        for (int i = 0; i < Chorus.MAX_KEYS * 3; i++) chorus.add("line " + i, "s", i, 10_000);
        assertEquals(Chorus.MAX_KEYS, chorus.size());
        int heard = 0;
        for (int i = 0; i < Chorus.MAX_VOICES * 3; i++)
            heard = chorus.add("same", "s" + i, 1, 10_000).size();
        assertEquals(Chorus.MAX_VOICES, heard);
    }

    @Test
    void chatLinesCompareWithoutDecoration() {
        String line = Chorus.chatKey("а нехуй политику в игру пихать суки");
        assertEquals(line, Chorus.chatKey("[red]А нехуй, политику в игру пихать суки!!"));
        assertEquals(
                line,
                Chorus.chatKey("а​ нехуй политику в игру пихать суки"),
                "zero-width and icon glyphs");
        assertEquals("da", Chorus.chatKey("Da"));
        assertEquals("da", Chorus.chatKey("d a  aa"));
        assertEquals("+", Chorus.chatKey(" + "), "a line of symbols keeps them");
        assertTrue(Chorus.chatKey("x".repeat(500)).length() <= Chorus.MAX_KEY_LENGTH);
        assertTrue(Chorus.chatKey("ab".repeat(500)).length() <= Chorus.MAX_KEY_LENGTH);
    }
}
