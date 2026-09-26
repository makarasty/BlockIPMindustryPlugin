package BlockIp;

import java.util.ArrayDeque;
import java.util.Iterator;
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

    private static final class Join {
        final String address;
        final long time;

        Join(String address, long time) {
            this.address = address;
            this.time = time;
        }
    }

    /** Recent VPN joins that were let in, oldest first, one per address, never more than the burst threshold. */
    private final ArrayDeque<Join> recent = new ArrayDeque<>();
    private long attackUntil;
    private boolean started;

    /**
     * Called for every join attempt from a listed address. Returns why to refuse it, or null to let it in.
     *
     * A burst is counted from the joins that would have been let in, one per address: a refused attempt
     * does not count, so an attacker cannot hold attack mode open by retrying, and one player reconnecting
     * over and over is one address, not a burst. Attack mode lasts the hold time and then ends; a new
     * burst starts it again.
     */
    Reason check(ConfigManager.ConfigData config, String address, long now, int online) {
        Mode mode = config.mode();
        if (mode == Mode.always) return Reason.always;
        if (mode != Mode.auto) return null;

        if (now < attackUntil) return Reason.attack;
        if (config.vpnMinPlayers > 0 && online >= config.vpnMinPlayers) return Reason.busy;
        if (config.vpnBurstJoins <= 0) return null;

        long window = config.vpnBurstSeconds * 1000L;
        while (!recent.isEmpty() && now - recent.peekFirst().time > window) recent.pollFirst();
        for (Iterator<Join> it = recent.iterator(); it.hasNext(); ) {
            if (it.next().address.equals(address)) it.remove();
        }
        recent.addLast(new Join(address, now));
        while (recent.size() > config.vpnBurstJoins) recent.pollFirst();

        if (recent.size() < config.vpnBurstJoins) return null;
        recent.clear();
        attackUntil = now + config.vpnBurstHoldMinutes * 60_000L;
        started = true;
        return Reason.attack;
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
