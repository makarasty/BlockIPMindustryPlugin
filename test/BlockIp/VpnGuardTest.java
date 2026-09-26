package BlockIp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VpnGuardTest {
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
        assertNull(guard.check(config("auto"), 0, 3));
        assertNull(guard.check(config("auto"), 61_000, 9));
    }

    @Test
    void aBusyServerRefusesVpnJoins() {
        VpnGuard guard = new VpnGuard();
        assertEquals(VpnGuard.Reason.busy, guard.check(config("auto"), 0, 10));
    }

    @Test
    void aBurstTurnsRefusalOnForTheHoldTimeAndSaysSoOnce() {
        VpnGuard guard = new VpnGuard();
        ConfigManager.ConfigData config = config("auto");
        assertNull(guard.check(config, 0, 1));
        assertNull(guard.check(config, 10_000, 1));
        assertFalse(guard.attackStarted());

        assertEquals(VpnGuard.Reason.attack, guard.check(config, 20_000, 1));
        assertTrue(guard.attackStarted());
        assertFalse(guard.attackStarted());

        // Held after the burst, even on a quiet server, until the hold time runs out
        assertEquals(VpnGuard.Reason.attack, guard.check(config, 20_000 + 5 * 60_000, 1));
        assertFalse(guard.attackStarted());
        assertTrue(guard.underAttack(20_000 + 9 * 60_000));
        assertFalse(guard.underAttack(20_000 + 10 * 60_000));
        assertNull(guard.check(config, 20_000 + 10 * 60_000, 1));
    }

    @Test
    void joinsSpreadWiderThanTheWindowAreNoBurst() {
        VpnGuard guard = new VpnGuard();
        ConfigManager.ConfigData config = config("auto");
        for (int i = 0; i < 10; i++) assertNull(guard.check(config, i * 31_000L, 1));
        assertFalse(guard.attackStarted());
    }

    @Test
    void modesAndTheirSpellings() {
        VpnGuard guard = new VpnGuard();
        assertEquals(VpnGuard.Reason.always, guard.check(config("always"), 0, 0));
        assertNull(guard.check(config("off"), 0, 100));
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
        for (int i = 0; i < 20; i++) assertNull(guard.check(config, i, 500));
    }
}
