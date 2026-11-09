package BlockIp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrustStoreTest {
    private static final long DAY = 86_400_000L;

    @Test
    void activeMinutesAndDistinctDaysAddUp() {
        TrustStore store = new TrustStore();
        assertNull(store.get("u"));
        for (int i = 0; i < 200; i++) store.active("u", 10 * DAY + i * 60_000L);
        assertEquals(200, store.minutes("u"));
        assertEquals(1, store.get("u").days);
        assertFalse(store.isTrusted("u", 200, 2));

        store.active("u", 11 * DAY + 5);
        assertEquals(2, store.get("u").days);
        assertTrue(store.isTrusted("u", 200, 2));
        assertEquals(10 * DAY, store.get("u").firstSeen);
        assertEquals(11 * DAY + 5, store.get("u").lastSeen);
    }

    @Test
    void straddlingMidnightIsNotASecondDay() {
        TrustStore store = new TrustStore();
        store.active("bot", 11 * DAY - 60_000);
        for (int i = 0; i < 300; i++) store.active("bot", 11 * DAY + i * 60_000L);
        assertEquals(1, store.get("bot").days, "another date, but not a day later");
        assertFalse(store.isTrusted("bot", 120, 2));
        store.active("bot", 12 * DAY - 60_000);
        assertEquals(2, store.get("bot").days, "a full day after the first minute");
        assertTrue(store.isTrusted("bot", 120, 2));
    }

    @Test
    void anUnknownUuidLeavesNoRecordAndAFlagTakesTrustAway() {
        TrustStore store = new TrustStore();
        store.seen("idle-bot", 1);
        assertEquals(0, store.size());
        assertNull(store.snapshot(), "nothing changed, nothing to save");

        store.flag("idle-bot");
        assertTrue(store.isFlagged("idle-bot"));
        assertEquals(0, store.size(), "a flag alone does not make a record");

        for (int i = 0; i < 300; i++) store.active("vet", i * 60_000L);
        store.active("vet", 3 * DAY);
        assertTrue(store.isTrusted("vet", 300, 2));
        store.flag("vet");
        assertFalse(store.isTrusted("vet", 300, 2));
        assertTrue(store.unflag("vet"));
        assertTrue(store.isTrusted("vet", 300, 2));
        assertFalse(store.unflag("vet"));

        // A flagged uuid that later earns a record keeps its flag
        store.active("idle-bot", 5);
        assertTrue(store.get("idle-bot").flagged);
        assertEquals(1, store.flaggedCount());
    }

    @Test
    void itSurvivesASaveAndALoad(@TempDir Path dir) throws IOException {
        TrustStore store = new TrustStore();
        store.active("a+b/c=", 7 * DAY);
        store.active("a+b/c=", 8 * DAY);
        store.flag("bot");
        store.active("flagged-player", DAY);
        store.flag("flagged-player");
        Path file = dir.resolve("trust.txt");
        TrustStore.write(store.snapshot(), file);

        TrustStore loaded = TrustStore.load(file, 9 * DAY);
        assertEquals(2, loaded.minutes("a+b/c="));
        assertEquals(2, loaded.get("a+b/c=").days);
        assertEquals(7 * DAY, loaded.get("a+b/c=").firstSeen);
        assertTrue(loaded.isFlagged("bot"));
        assertNull(loaded.get("bot"));
        assertTrue(loaded.isFlagged("flagged-player"));
        assertEquals(1, loaded.minutes("flagged-player"));
        assertNull(loaded.snapshot(), "a fresh load is not dirty");
        assertFalse(Files.exists(dir.resolve("trust.txt.tmp")));
    }

    @Test
    void aMissingFileIsAnEmptyStoreAndACorruptOneIsKeptAside(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("trust.txt");
        assertEquals(0, TrustStore.load(file, 9 * DAY).size());

        Files.writeString(file, "BlockIp trust 1\nu 5 1 2 1 0 0\nthis line is broken\n");
        assertEquals(0, TrustStore.load(file, 9 * DAY).size());
        assertFalse(Files.exists(file));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(
                    1,
                    files.filter(f -> f.getFileName().toString().startsWith("trust.txt.bad-"))
                            .count());
        }
    }

    @Test
    void whenFullFreshUuidsEvictEachOtherBeforeVeterans() {
        TrustStore store = new TrustStore();
        // The least recently seen veteran first, then the store filled with one-minute uuids
        for (int i = 0; i < TrustStore.PROBATION_MINUTES; i++) store.active("veteran", i);
        for (int i = 1; i < TrustStore.MAX_RECORDS; i++) store.active("fresh" + i, 1_000_000 + i);
        assertEquals(TrustStore.MAX_RECORDS, store.size());

        store.active("one-more", 5_000_000);
        assertEquals(TrustStore.MAX_RECORDS, store.size());
        assertNotNull(store.get("veteran"));
        assertNull(store.get("fresh1"), "the oldest uuid still on probation went");
        assertNotNull(store.get("one-more"));
    }

    @Test
    void whenFullOfDormantVeteransFarmedUuidsEvictOnlyEachOther() {
        TrustStore store = new TrustStore();
        // Veterans not seen since, many more of them than any scan window, then one-minute farmed
        // uuids
        int veterans = 5_000;
        for (int v = 0; v < veterans; v++) {
            for (int i = 0; i < TrustStore.PROBATION_MINUTES; i++) store.active("vet" + v, v);
        }
        for (int i = veterans; i < TrustStore.MAX_RECORDS + 20_000; i++)
            store.active("farmed" + i, 1_000_000 + i);

        assertEquals(TrustStore.MAX_RECORDS, store.size());
        for (int v = 0; v < veterans; v++) assertNotNull(store.get("vet" + v), "vet" + v);
        assertNull(store.get("farmed" + veterans));
        // And a probation uuid that reaches the bar is a veteran from then on, kept like one
        for (int i = 0; i < TrustStore.PROBATION_MINUTES; i++)
            store.active("climber", 2_000_000 + i);
        for (int i = 0; i < 200_000; i++) store.active("more" + i, 3_000_000 + i);
        assertEquals(TrustStore.PROBATION_MINUTES, store.minutes("climber"));
    }

    @Test
    void bothKindsOfRecordSurviveASave(@TempDir Path dir) throws IOException {
        TrustStore store = new TrustStore();
        for (int i = 0; i < TrustStore.PROBATION_MINUTES; i++) store.active("vet", i);
        store.active("new", 100);
        Path file = dir.resolve("trust.txt");
        TrustStore.write(store.snapshot(), file);
        TrustStore loaded = TrustStore.load(file, 9 * DAY);
        assertEquals(2, loaded.size());
        assertEquals(TrustStore.PROBATION_MINUTES, loaded.minutes("vet"));
        assertEquals(1, loaded.minutes("new"));
    }

    @Test
    void passersByLongGoneAreDroppedAtLoadAndFlagsKept(@TempDir Path dir) throws IOException {
        TrustStore store = new TrustStore();
        store.active("passer-by", DAY);
        store.active("flagged-passer-by", DAY);
        store.flag("flagged-passer-by");
        for (int i = 0; i < TrustStore.PROBATION_MINUTES; i++) store.active("veteran", DAY + i);
        store.active("recent", 40 * DAY);
        assertEquals(
                DAY, store.firstSeen(50 * DAY), "about when this server started keeping trust");
        Path file = dir.resolve("trust.txt");
        TrustStore.write(store.snapshot(), file);

        TrustStore loaded = TrustStore.load(file, DAY + TrustStore.PROBATION_KEEP_MS + 1);
        assertNull(loaded.get("passer-by"));
        assertNull(loaded.get("flagged-passer-by"));
        assertTrue(loaded.isFlagged("flagged-passer-by"));
        assertNotNull(loaded.get("veteran"), "a veteran is kept however long away");
        assertNotNull(loaded.get("recent"));
        assertEquals(123, new TrustStore().firstSeen(123), "nothing kept yet: now");
    }

    @Test
    void flaggedUuidsWithoutARecordAreCapped() {
        TrustStore store = new TrustStore();
        for (int i = 0; i <= TrustStore.MAX_FLAGGED; i++) store.flag("bot" + i);
        assertEquals(TrustStore.MAX_FLAGGED, store.flaggedCount());
        assertFalse(store.isFlagged("bot0"));
        assertTrue(store.isFlagged("bot" + TrustStore.MAX_FLAGGED));
    }
}
