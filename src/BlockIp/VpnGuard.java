package BlockIp;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Decides when an address on the VPN list is refused. {@code always} refuses every one; {@code auto}
 * only while the server is busy or a burst of VPN joins says an attack is on, so a player on a VPN
 * can still join a quiet server. Main thread only.
 */
final class VpnGuard {
    enum Mode {
        off, auto, always;

        /** Lenient, so a typo in the config means the safe default rather than a reset config file. */
        static Mode parse(String text) {
            if (text == null) return auto;
            switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "off": case "false": case "no": return off;
                case "always": case "on": case "true": case "yes": return always;
                default: return auto;
            }
        }
    }

    /** Why a join was refused. */
    enum Reason {always, busy, attack}

    /** Times of recent VPN join attempts, oldest first, never more than the burst threshold. */
    private final ArrayDeque<Long> recent = new ArrayDeque<>();
    private long attackUntil;
    private boolean started;

    /**
     * Called for every join attempt from a listed address, allowed or not: those attempts are what a
     * burst is counted from. Returns why to refuse it, or null to let it in.
     */
    Reason check(ConfigManager.ConfigData config, long now, int online) {
        Mode mode = config.mode();
        if (mode == Mode.always) return Reason.always;
        if (mode != Mode.auto) return null;

        if (config.vpnBurstJoins > 0) {
            long window = config.vpnBurstSeconds * 1000L;
            while (!recent.isEmpty() && now - recent.peekFirst() > window) recent.pollFirst();
            recent.addLast(now);
            while (recent.size() > config.vpnBurstJoins) recent.pollFirst();
            if (recent.size() >= config.vpnBurstJoins) {
                if (now >= attackUntil) started = true;
                attackUntil = now + config.vpnBurstHoldMinutes * 60_000L;
            }
        }

        if (now < attackUntil) return Reason.attack;
        if (config.vpnMinPlayers > 0 && online >= config.vpnMinPlayers) return Reason.busy;
        return null;
    }

    /** True once, after the check that switched attack mode on. */
    boolean attackStarted() {
        boolean was = started;
        started = false;
        return was;
    }

    boolean underAttack(long now) {
        return now < attackUntil;
    }
}
