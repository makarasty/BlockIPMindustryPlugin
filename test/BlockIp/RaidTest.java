package BlockIp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaidTest {
    private static final long WINDOW = 30_000, HOLD = 10 * 60_000L;

    @Test
    void aBurstOfDistinctAddressesStartsItOnce() {
        Raid raid = new Raid();
        for (int i = 0; i < 5; i++) assertFalse(raid.join("1.1.1." + i, 7, "004m", i * 1_000L, 6, WINDOW, HOLD));
        assertTrue(raid.join("1.1.1.5", 7, "004m", 5_000, 6, WINDOW, HOLD));
        assertTrue(raid.active(5_000));
        assertEquals("join burst", raid.reason());
        // A second burst while on extends it and is no new start
        for (int i = 0; i < 6; i++) assertFalse(raid.join("2.2.2." + i, 7, "004m", 60_000 + i, 6, WINDOW, HOLD));
        assertTrue(raid.active(60_000 + HOLD - 1));
    }

    @Test
    void aBurstOfNewUuidsStartsItWithoutLookingScripted() {
        Raid raid = new Raid();
        for (int i = 0; i < 11; i++) assertFalse(raid.newJoin("5.5.5." + i, i * 1_000L, 12, WINDOW, HOLD));
        assertFalse(raid.newJoin("5.5.5.0", 11_000, 12, WINDOW, HOLD), "one address twice is one join");
        assertTrue(raid.newJoin("5.5.5.11", 12_000, 12, WINDOW, HOLD));
        assertEquals("new uuid burst", raid.reason());
        Raid slow = new Raid();
        for (int i = 0; i < 30; i++) assertFalse(slow.newJoin("6.6.6." + i, i * 3_000L, 12, WINDOW, HOLD), "one every 3 s");
        assertFalse(new Raid().newJoin("x", 0, 0, WINDOW, HOLD), "0 turns it off");
    }

    @Test
    void oneAddressOrSlowJoinsAreNoBurst() {
        Raid raid = new Raid();
        for (int i = 0; i < 30; i++) assertFalse(raid.join("3.3.3.3", 7, "004m", i * 100L, 6, WINDOW, HOLD));
        for (int i = 0; i < 30; i++) assertFalse(raid.join("4.4.4." + i, 7, "004m", 60_000 + i * 7_000L, 6, WINDOW, HOLD));
        assertFalse(raid.active(0));
        assertFalse(new Raid().join("x", 7, "004m", 0, 0, WINDOW, HOLD), "0 turns joins off");
    }

    @Test
    void regularsFromACrashedSiblingAreNoBurstButOneClientOrNamePatternIs() {
        // Six players from another server: each its own client and name
        String[] names = {"Fance", "Athos", "svo", "Kirill_UA", "k", "MaKarastY"};
        Raid raid = new Raid();
        for (int i = 0; i < 6; i++) assertFalse(raid.join("6.6.6." + i, i, names[i], i * 1_000L, 6, WINDOW, HOLD));
        assertFalse(raid.active(6_000));
        // Half on one client is a script, whatever the names
        raid = new Raid();
        for (int i = 0; i < 6; i++) {
            boolean last = i == 5;
            assertEquals(last, raid.join("7.7.7." + i, i % 2 == 0 ? 42 : i, names[i], i * 1_000L, 6, WINDOW, HOLD));
        }
        // Look-alike names are, whatever the clients
        String[] bots = {"000x", "0016", "001s", "Fance", "Athos", "svo"};
        raid = new Raid();
        for (int i = 0; i < 5; i++) assertFalse(raid.join("8.8.8." + i, i, bots[i], i * 1_000L, 6, WINDOW, HOLD));
        assertTrue(raid.join("8.8.8.5", 5, bots[5], 5_000, 6, WINDOW, HOLD));
    }

    @Test
    void itHoldsAfterTheLastTriggerAndEndsExactlyOnce() {
        Raid raid = new Raid();
        assertTrue(raid.trigger("chat flood", 0, HOLD));
        assertFalse(raid.trigger("coordinated votes", 5 * 60_000L, HOLD));
        assertEquals("chat flood", raid.reason());
        assertTrue(raid.active(5 * 60_000L + HOLD - 1));
        assertFalse(raid.ended(5 * 60_000L + HOLD - 1));
        // Ran out, not announced yet: a trigger now extends it rather than starting a second one
        assertFalse(raid.trigger("chat flood", 5 * 60_000L + HOLD + 1, HOLD));
        assertTrue(raid.ended(5 * 60_000L + 2 * HOLD + 1));
        assertFalse(raid.ended(5 * 60_000L + 2 * HOLD + 2));
        assertNull(raid.reason());
        assertTrue(raid.trigger("chat flood", 30 * 60_000L, HOLD));
    }

    @Test
    void aChorusStartsItButNeverExtendsIt() {
        Raid raid = new Raid();
        assertTrue(raid.start("coordinated votes", 0, HOLD));
        // Bots that passed this raid's challenge sending their chorus again: no later end
        assertFalse(raid.start("coordinated votes", HOLD - 1, HOLD));
        assertEquals(HOLD, raid.until());
        // Ran out, end not announced yet: neither a start nor an extension until it is
        assertFalse(raid.start("chat flood", HOLD + 1, HOLD));
        assertFalse(raid.active(HOLD + 1));
        assertTrue(raid.ended(HOLD + 1));
        assertTrue(raid.start("chat flood", HOLD + 1, HOLD));
    }

    @Test
    void aBurstJustAfterTheHoldRanOutIsAStartOnceTheEndIsAnnounced() {
        Raid raid = new Raid();
        assertTrue(raid.trigger("chat flood", 0, HOLD));
        // What BlockPlugin does before counting a join: announce the end first, so the burst is a real start
        assertTrue(raid.ended(HOLD + 1));
        for (int i = 0; i < 5; i++) assertFalse(raid.join("5.5.5." + i, 7, "004m", HOLD + 1 + i, 6, WINDOW, HOLD));
        assertTrue(raid.join("5.5.5.5", 7, "004m", HOLD + 6, 6, WINDOW, HOLD));
        assertEquals("join burst", raid.reason());
    }

    @Test
    void byHandItStaysOnUntilTurnedOff() {
        Raid raid = new Raid();
        assertTrue(raid.startManually(0));
        assertTrue(raid.active(Long.MAX_VALUE - 1));
        assertFalse(raid.ended(1_000_000_000L));
        assertTrue(raid.stop());
        assertFalse(raid.active(1));
        assertFalse(raid.stop());
        assertFalse(raid.ended(2));
    }
}
