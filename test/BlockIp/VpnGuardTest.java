package BlockIp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VpnGuardTest {
    private static final long MINUTE = 60_000;

    private static ConfigManager.ConfigData config(String mode) {
        ConfigManager.ConfigData config = new ConfigManager.ConfigData();
        config.vpnMode = mode;
        config.vpnMinPlayers = 10;
        config.vpnBurstJoins = 3;
        config.vpnBurstSeconds = 60;
        config.vpnBurstHoldMinutes = 10;
        return config;
    }

    @Test
    void aQuietServerLetsAVpnPlayerIn() {
        VpnGuard guard = new VpnGuard();
        assertNull(guard.check(config("auto"), "1.1.1.1", 0, 3));
        assertNull(guard.check(config("auto"), "1.1.1.2", 2 * MINUTE, 9));
    }

    @Test
    void aBusyServerRefusesVpnJoinsWithoutCountingThemAsABurst() {
        VpnGuard guard = new VpnGuard();
        ConfigManager.ConfigData config = config("auto");
        for (int i = 0; i < 10; i++) assertEquals(VpnGuard.Reason.busy, guard.check(config, "2.2.2." + i, i, 10));
        assertFalse(guard.attackStarted());
        assertNull(guard.check(config, "2.2.2.99", 20, 9));
    }

    @Test
    void distinctAddressesWithinTheWindowTripItOnceAndItEndsAfterTheHold() {
        VpnGuard guard = new VpnGuard();
        ConfigManager.ConfigData config = config("auto");
        assertNull(guard.check(config, "3.3.3.1", 0, 1));
        assertNull(guard.check(config, "3.3.3.2", 10_000, 1));
        assertFalse(guard.attackStarted());

        assertEquals(VpnGuard.Reason.attack, guard.check(config, "3.3.3.3", 20_000, 1));
        assertTrue(guard.attackStarted());
        assertFalse(guard.attackStarted());

        // Attempts during the hold are refused and do not extend it, however many there are
        for (int i = 0; i < 50; i++) {
            assertEquals(VpnGuard.Reason.attack, guard.check(config, "3.3.4." + i, 20_000 + i * 11_000L, 1));
        }
        assertFalse(guard.attackStarted());
        assertTrue(guard.underAttack(20_000 + 10 * MINUTE - 1));
        assertFalse(guard.underAttack(20_000 + 10 * MINUTE));
        assertNull(guard.check(config, "3.3.5.1", 20_000 + 10 * MINUTE, 1));
    }

    @Test
    void anotherBurstAfterTheHoldTripsItAgain() {
        VpnGuard guard = new VpnGuard();
        ConfigManager.ConfigData config = config("auto");
        for (int i = 0; i < 3; i++) guard.check(config, "4.4.4." + i, i, 1);
        assertTrue(guard.attackStarted());
        long after = 10 * MINUTE + 10;
        assertNull(guard.check(config, "4.4.5.1", after, 1));
        assertNull(guard.check(config, "4.4.5.2", after + 1, 1));
        assertEquals(VpnGuard.Reason.attack, guard.check(config, "4.4.5.3", after + 2, 1));
        assertTrue(guard.attackStarted());
    }

    @Test
    void oneAddressReconnectingIsNotABurst() {
        VpnGuard guard = new VpnGuard();
        ConfigManager.ConfigData config = config("auto");
        for (int i = 0; i < 20; i++) assertNull(guard.check(config, "5.5.5.5", i * 1_000L, 1));
        assertFalse(guard.attackStarted());
    }

    @Test
    void joinsSpreadWiderThanTheWindowAreNoBurst() {
        VpnGuard guard = new VpnGuard();
        ConfigManager.ConfigData config = config("auto");
        for (int i = 0; i < 10; i++) assertNull(guard.check(config, "6.6.6." + i, i * 31_000L, 1));
        assertFalse(guard.attackStarted());
    }

    @Test
    void modesAndTheirSpellings() {
        VpnGuard guard = new VpnGuard();
        assertEquals(VpnGuard.Reason.always, guard.check(config("always"), "7.7.7.7", 0, 0));
        assertNull(guard.check(config("off"), "7.7.7.7", 0, 100));
        assertEquals(VpnGuard.Mode.auto, VpnGuard.Mode.parse("typo"));
        assertEquals(VpnGuard.Mode.auto, VpnGuard.Mode.parse(null));
        assertEquals(VpnGuard.Mode.off, VpnGuard.Mode.parse(" OFF "));
        assertEquals(VpnGuard.Mode.always, VpnGuard.Mode.parse("true"));
    }

    @Test
    void zeroThresholdsTurnTheirRuleOff() {
        VpnGuard guard = new VpnGuard();
        ConfigManager.ConfigData config = config("auto");
        config.vpnMinPlayers = 0;
        config.vpnBurstJoins = 0;
        for (int i = 0; i < 20; i++) assertNull(guard.check(config, "8.8.8." + i, i, 500));
    }
}
