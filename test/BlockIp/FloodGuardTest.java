package BlockIp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FloodGuardTest {
    private static final long WINDOW = 10_000, BLOCK = 60_000;

    @BeforeEach
    void clean() {
        FloodGuard.reset();
    }

    private static boolean connect(String address, long now) {
        return FloodGuard.accept(address, now, 3, WINDOW, BLOCK);
    }

    @Test
    void anAddressOverTheLimitIsBlockedForTheBlockTime() {
        assertTrue(connect("1.1.1.1", 0));
        assertTrue(connect("1.1.1.1", 1));
        assertTrue(connect("1.1.1.1", 2));
        assertFalse(connect("1.1.1.1", 3));
        // Blocked even after the window has passed, until the block runs out
        assertFalse(connect("1.1.1.1", WINDOW + 5));
        assertTrue(connect("1.1.1.1", 3 + BLOCK));
        assertEquals(2, FloodGuard.refused.get());
    }

    @Test
    void theCountStartsOverEachWindowAndIsPerAddress() {
        for (int i = 0; i < 3; i++) assertTrue(connect("2.2.2.2", i));
        assertTrue(connect("3.3.3.3", 3));
        assertTrue(connect("2.2.2.2", WINDOW));
        assertTrue(connect("2.2.2.2", WINDOW + 1));
    }

    @Test
    void aBlockFromTheMainThreadIsHonouredAndExpires() {
        assertTrue(FloodGuard.block("4.4.4.4", 1_000));
        assertFalse(FloodGuard.block("4.4.4.4", 2_000));
        assertFalse(connect("4.4.4.4", 500));
        assertTrue(connect("4.4.4.4", 2_000));
    }

    @Test
    void aZeroLimitTurnsRateLimitingOff() {
        for (int i = 0; i < 100; i++) assertTrue(FloodGuard.accept("5.5.5.5", i, 0, WINDOW, BLOCK));
    }

    @Test
    void expiredEntriesAreSweptAway() {
        FloodGuard.block("6.6.6.6", 100);
        connect("7.7.7.7", 0);
        connect("8.8.8.8", WINDOW * 2);
        assertEquals(0, FloodGuard.blockedCount());
    }
}
