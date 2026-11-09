package BlockIp;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Raid mode: on while a raid is going on, for everyone, not only VPN addresses - the bots that beat
 * the vote came through residential proxies no list knows. It starts on a burst of joins not yet
 * trusted from distinct addresses, on a coordinated vote, on a chat flood, or by hand, and holds
 * for a while after the last trigger. While on, low-trust joins are challenged; once a
 * scripted-looking join burst (or an admin) started or extended it, only trusted players vote too.
 * A chorus or a burst of new uuids does not lock votes: regulars not yet trusted keep voting. Holds
 * only the state; BlockPlugin acts on it. Main thread only.
 */
final class Raid {
    private static final class Join {
        final String address, plain, shape;
        final int fingerprint;
        final long time;

        Join(String address, int fingerprint, String name, long time) {
            this.address = address;
            this.fingerprint = fingerprint;
            plain = Suspicion.plain(name);
            shape = Suspicion.shapeOf(plain);
            this.time = time;
        }
    }

    /**
     * Recent joins not yet trusted, oldest first, one per address, never more than the burst
     * threshold.
     */
    private final ArrayDeque<Join> joins = new ArrayDeque<>();

    /** The same, of uuids this server has no record of at all (see {@link #newJoin}). */
    private final ArrayDeque<Join> newJoins = new ArrayDeque<>();

    private long until, started;
    private String reason;

    /** Only trusted players vote: set by every trigger but a chorus, until it ends. */
    private boolean votesLocked;

    /**
     * A join not yet trusted from {@code address}, with its connect {@code fingerprint} and {@code
     * name}. Returns true when it is the one that started raid mode. One address reconnecting over
     * and over is one join; a burst during raid mode extends it. A burst counts only when it looks
     * scripted (see {@link #scripted}).
     */
    boolean join(
            String address,
            int fingerprint,
            String name,
            long now,
            int burst,
            long windowMs,
            long holdMs) {
        if (!add(joins, new Join(address, fingerprint, name, now), burst, windowMs) || !scripted())
            return false;
        joins.clear();
        return trigger("join burst", now, holdMs);
    }

    /**
     * A join of a uuid with no trust record here, never seen active. {@code burst} of them from
     * distinct addresses within the window start raid mode however varied their clients and names -
     * a script that varies both still needs fresh uuids - at a threshold above {@link #join}'s,
     * since that test is not put to them. Like a chorus it does not lock votes: a dozen real
     * newcomers from a Discord post look the same, and newcomers cannot vote yet.
     */
    boolean newJoin(String address, long now, int burst, long windowMs, long holdMs) {
        if (!add(newJoins, new Join(address, 0, "", now), burst, windowMs)) return false;
        newJoins.clear();
        return trigger("new uuid burst", now, holdMs, false);
    }

    /**
     * Adds {@code join} to {@code recent}, one per address within the window; true once it holds
     * {@code burst}.
     */
    private static boolean add(ArrayDeque<Join> recent, Join join, int burst, long windowMs) {
        if (burst <= 0) return false;
        while (!recent.isEmpty() && join.time - recent.peekFirst().time > windowMs)
            recent.pollFirst();
        for (Iterator<Join> it = recent.iterator(); it.hasNext(); ) {
            if (it.next().address.equals(join.address)) it.remove();
        }
        recent.addLast(join);
        while (recent.size() > burst) recent.pollFirst();
        return recent.size() >= burst;
    }

    /**
     * At least half the burst shares one client fingerprint or has look-alike names. Regulars
     * moving over from a crashed sibling server, which has its own trust file, have none here, and
     * come with varied locales, versions, colours and devices; a script's bots share one client and
     * one name pattern.
     */
    private boolean scripted() {
        for (Join j : joins) {
            int sameClient = 0, lookAlike = 0;
            for (Join o : joins) {
                if (o.fingerprint == j.fingerprint) sameClient++;
                if (o == j || Suspicion.similar(j.shape, j.plain, o.shape, o.plain)) lookAlike++;
            }
            if (Math.max(sameClient, lookAlike) * 2 >= joins.size()) return true;
        }
        return false;
    }

    /**
     * Starts raid mode, or extends it. Returns true when this started it. One that ran out but
     * whose end is not announced yet is extended, so every start is followed by exactly one end.
     */
    boolean trigger(String why, long now, long holdMs) {
        return trigger(why, now, holdMs, true);
    }

    /**
     * {@code lockVotes}: this trigger keeps votes to trusted players; one that does wins over one
     * that does not.
     */
    private boolean trigger(String why, long now, long holdMs, boolean lockVotes) {
        boolean starting = reason == null;
        if (starting) {
            started = now;
            reason = why;
            votesLocked = false;
        }
        votesLocked |= lockVotes;
        until = Math.max(until, now + holdMs);
        return starting;
    }

    /**
     * Starts raid mode, never extends it: false while it is on, or ran out with its end not
     * announced yet (the caller calls {@link #ended} first). How a chorus starts it: bots that
     * passed this raid's challenge are not asked again during it and a chorus costs them nothing,
     * so one that extended it held it on for good.
     */
    boolean start(String why, long now, long holdMs) {
        return reason == null && trigger(why, now, holdMs, false);
    }

    /** On until {@link #stop}. Returns true when this started it. */
    boolean startManually(long now) {
        boolean starting = trigger("manual", now, 0);
        until = Long.MAX_VALUE;
        return starting;
    }

    /** Ends it now. Returns true when it was on, or ran out without its end announced yet. */
    boolean stop() {
        boolean was = reason != null;
        until = 0;
        reason = null;
        return was;
    }

    boolean active(long now) {
        return now < until;
    }

    /** On, and only trusted players vote (see {@link #trigger}). */
    boolean votesLocked(long now) {
        return active(now) && votesLocked;
    }

    /** True once, at the first call after the hold ran out; the caller announces the end. */
    boolean ended(long now) {
        if (reason == null || now < until) return false;
        reason = null;
        return true;
    }

    long started() {
        return started;
    }

    long until() {
        return until;
    }

    String reason() {
        return reason;
    }
}
