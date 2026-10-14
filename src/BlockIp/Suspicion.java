package BlockIp;

import arc.util.Strings;

import java.util.Collection;
import java.util.Locale;

/**
 * How much an online player looks like one of a raid's bots, 0 to 100, from signals each too weak
 * to act on alone: a short digit name is also a real player's, a shared default client is most
 * players'. The score never kicks anyone; it only keeps a player out of votes, where a wrong guess
 * costs a real player one vote. What turns it down is what bots could not fake cheaply: real active
 * time on several days, a passed challenge. Pure; BlockPlugin gathers the facts.
 */
final class Suspicion {
    private Suspicion() {
    }

    /** A uuid active this recently is a regular reconnecting (after a restart, say), not part of a burst. */
    static final long RECONNECT_MS = Tuning.Suspicion.RECONNECT_MS;

    /**
     * Whether a join counts toward a burst: anyone not yet trusted - a cohort farmed past the vote minimum
     * is a cohort still, and counting only low trust left it nothing that fired - unless the uuid, past the
     * vote minimum, was active minutes ago, which is a regular coming back after a restart, or the server
     * itself started minutes ago, when every regular comes back at once (with no record at all on a first
     * install). A low-trust uuid seen minutes ago counts: bots that walked a minute rejoined from fresh addresses.
     */
    static boolean burstJoin(boolean untrusted, TrustStore.Record record, long started, long now, ConfigManager.ConfigData c) {
        return untrusted && now - started > RECONNECT_MS
                && (record == null || record.minutes < c.voteMinActiveMinutes || now - record.lastSeen > RECONNECT_MS);
    }

    /**
     * Joined over {@link Tuning.Chat#NO_SNAPSHOT_GRACE_MS} ago and its client has sent no snapshot: no game is
     * running behind it, so its chat is a script's. A real client sends them from the moment it is in game.
     */
    static boolean headless(Session s, long now) {
        return s != null && !s.snapshot && now - s.joined > Tuning.Chat.NO_SNAPSHOT_GRACE_MS;
    }

    /** Online, its client sends snapshots and its unit really walked: what a headless bot does not do. */
    static boolean playing(Session s) {
        return s != null && s.snapshot && s.moved >= Session.ACTIVE_DISTANCE;
    }

    /** One of the burst that just started raid mode: a burst join within its window of {@code now}. */
    static boolean cameWithBurst(Session s, long now, ConfigManager.ConfigData c) {
        return s.burstJoin && now - s.joined <= c.raidJoinSeconds * 1000L;
    }

    /**
     * Joined in a burst: it and at least raidJoins - 1 other burst joins still online came within
     * raidJoinSeconds of each other, and at least half of them share its client fingerprint or a look-alike
     * name, the test {@link Raid} puts to a burst. Without that, regulars moving over together from a crashed
     * sibling were a cohort for their whole session. Counted from who is online, so nothing is kept for those who left.
     */
    static boolean inCohort(Session s, Collection<Session> online, ConfigManager.ConfigData c) {
        if (!s.burstJoin || c.raidJoins <= 0) return false;
        int cohort = 1, sameClient = 1, lookAlike = 1;
        for (Session o : online) {
            if (o == s || !o.burstJoin || Math.abs(o.joined - s.joined) > c.raidJoinSeconds * 1000L) continue;
            cohort++;
            if (o.fingerprint == s.fingerprint) sameClient++;
            if (similar(s.shape, s.plain, o.shape, o.plain)) lookAlike++;
        }
        return cohort >= c.raidJoins && Math.max(sameClient, lookAlike) * 2 >= cohort;
    }

    /** {@code s} may be null for a player who is not online; {@code online} may include {@code s}. */
    static int score(Session s, TrustStore.Record record, boolean flagged, boolean admin, Collection<Session> online, long now,
                     ConfigManager.ConfigData c, StringBuilder why) {
        if (admin) {
            why.append("admin");
            return 0;
        }
        int score = 0;
        int minutes = record == null ? 0 : record.minutes, days = record == null ? 0 : record.days;
        if (minutes == 0) score += add(why, Tuning.Suspicion.NO_ACTIVE_TIME_POINTS, "no active time");
        else if (minutes < c.voteMinActiveMinutes) score += add(why, Tuning.Suspicion.UNDER_VOTE_MINUTES_POINTS, "under " + c.voteMinActiveMinutes + " active min");
        else if (minutes < c.trustedMinutes) score += add(why, Tuning.Suspicion.NOT_TRUSTED_POINTS, "not trusted yet");
        else score += add(why, Tuning.Suspicion.TRUSTED_MINUTES_POINTS, minutes + " active min");
        if (days >= c.trustedDays) score += add(why, Tuning.Suspicion.TRUSTED_DAYS_POINTS, days + " days");

        if (s != null) {
            if (inCohort(s, online, c)) {
                int lookAlike = 0, sameClient = 0;
                for (Session o : online) {
                    if (o == s || !o.burstJoin || Math.abs(o.joined - s.joined) > c.raidJoinSeconds * 1000L) continue;
                    if (similar(s.shape, s.plain, o.shape, o.plain)) lookAlike++;
                    if (o.fingerprint == s.fingerprint) sameClient++;
                }
                score += add(why, Tuning.Suspicion.BURST_POINTS, "joined in a burst");
                if (lookAlike >= Tuning.Suspicion.LOOKALIKE_MIN_COUNT) score += add(why, Tuning.Suspicion.LOOKALIKE_POINTS, lookAlike + " look-alike names");
                if (sameClient >= Tuning.Suspicion.IDENTICAL_CLIENTS_MIN_COUNT) score += add(why, Tuning.Suspicion.IDENTICAL_CLIENTS_POINTS, sameClient + " identical clients");
            }
            long age = now - s.joined;
            if (!s.snapshot && age > Tuning.Suspicion.NO_SNAPSHOT_AFTER_MS) score += add(why, Tuning.Suspicion.NO_SNAPSHOT_POINTS, "no client snapshot");
            else if (s.moved < Session.ACTIVE_DISTANCE && age > Tuning.Suspicion.NOT_MOVED_AFTER_MS) score += add(why, Tuning.Suspicion.NOT_MOVED_POINTS, "has not moved");
            if (s.botLike) score += add(why, Tuning.Suspicion.BOT_LIKE_NAME_POINTS, "bot-like name");
            if (s.vpn) score += add(why, Tuning.Suspicion.VPN_POINTS, "VPN/hosting");
            if (s.coordinated > 0) score += add(why, Math.min(s.coordinated, Tuning.Suspicion.COORDINATED_MAX_TIMES) * Tuning.Suspicion.COORDINATED_POINTS_EACH, "coordinated x" + s.coordinated);
            if (s.passedChallenge) score += add(why, Tuning.Suspicion.PASSED_CHALLENGE_POINTS, "passed challenge");
        }
        if (flagged) score += add(why, Tuning.Suspicion.FLAGGED_POINTS, "flagged");
        return Math.max(0, Math.min(Tuning.Suspicion.MAX_SCORE, score));
    }

    private static int add(StringBuilder why, int points, String reason) {
        if (why.length() > 0) why.append(", ");
        why.append(points > 0 ? "+" : "").append(points).append(' ').append(reason);
        return points;
    }

    /** Short, letters and digits only, at least half digits: "004m", "0016". "k" or "svo" are not. */
    static boolean botLikeName(String name) {
        String plain = Strings.stripColors(name);
        if (plain.length() < Tuning.Names.BOT_LIKE_MIN_LENGTH || plain.length() > Tuning.Names.BOT_LIKE_MAX_LENGTH) return false;
        int digits = 0;
        for (int i = 0; i < plain.length(); i++) {
            char ch = plain.charAt(i);
            if (ch >= '0' && ch <= '9') digits++;
            else if (!(ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z')) return false;
        }
        return digits * 2 >= plain.length();
    }

    /** Digits as 9, letters as a: "004m" and "001s" are both "999a". */
    static String shape(String name) {
        return shapeOf(plain(name));
    }

    /** What names are compared by: no colours, lower case. */
    static String plain(String name) {
        return Strings.stripColors(name).toLowerCase(Locale.ROOT);
    }

    /** {@link #shape} of a name already made {@link #plain}. */
    static String shapeOf(String plain) {
        StringBuilder shape = new StringBuilder(Math.min(plain.length(), Tuning.Names.SHAPE_MAX_LENGTH));
        for (int i = 0; i < plain.length() && i < Tuning.Names.SHAPE_MAX_LENGTH; i++) {
            char ch = plain.charAt(i);
            shape.append(Character.isDigit(ch) ? '9' : Character.isLetter(ch) ? 'a' : ch);
        }
        return shape.toString();
    }

    /**
     * The same shape with digits in it ("004m", "001s"), or the same short length and first two characters
     * ("0016", "004m"). Two plain words of one length ("Fance", "Athos") share a shape but are not alike.
     */
    static boolean similar(String shape, String name, String other) {
        return similar(shape, plain(name), shape(other), plain(other));
    }

    /** {@link #similar(String, String, String)} on names made {@link #plain} and their shapes, computed once per name. */
    static boolean similar(String shape, String plain, String otherShape, String otherPlain) {
        if (shape.indexOf('9') >= 0 && shape.equals(otherShape)) return true;
        return plain.length() == otherPlain.length() && plain.length() <= Tuning.Names.LOOKALIKE_MAX_LENGTH
                && plain.length() >= Tuning.Names.LOOKALIKE_MIN_LENGTH
                && plain.regionMatches(0, otherPlain, 0, Tuning.Names.LOOKALIKE_SHARED_PREFIX);
    }

    /**
     * Why this caller may not start or cast a vote, or null when they may. {@code needMinutes}: active minutes
     * this vote takes - starting a kick more than casting one, or any other vote. {@code voteStarted} is when
     * the vote they would cast began (0: none, or a command that starts one). {@code snapshot}: the client has
     * sent one this session - a headless bot that never does is not playing, whatever else it earned.
     * {@code trustedOnly}: raid mode. A kick of a trusted player is held apart, until a trusted player votes
     * for it too (see {@link KickVote}), so a cohort farmed past these minutes cannot kick the regulars.
     */
    static String voteRefusal(boolean admin, boolean pending, boolean snapshot, boolean trustedOnly, boolean trusted, int minutes,
                              int needMinutes, int score, long connected, long voteStarted, ConfigManager.ConfigData c) {
        if (admin) return null;
        if (pending) return "challenge pending";
        if (!snapshot) return "no client snapshot";
        if (trustedOnly && !trusted) return "not trusted (raid mode)";
        if (minutes < needMinutes) return minutes + " of " + needMinutes + " active min";
        if (score >= c.voteMaxSuspicion) return "suspicion " + score;
        if (voteStarted != 0 && connected > voteStarted) return "joined after the vote started";
        return null;
    }

    /**
     * A vote refused for what a person does not do - no client snapshot, a suspicion score too high - not for
     * being short of active minutes: three newcomers calling /rtv at map end are refused, but are no raid.
     */
    static boolean refusedAsRaider(boolean snapshot, int score, ConfigManager.ConfigData c) {
        return !snapshot || score >= c.voteMaxSuspicion;
    }

    /**
     * Whether a vote command or chat line from a player not yet trusted counts toward a chorus: only one that
     * already looks like a raid's - it joined in a burst still online ({@code cohort}, see {@link #inCohort}),
     * was coordinated before, is flagged, or is refused on its own ({@code refused}: for a vote, a refusal a
     * person does not trigger, see {@link #refusedAsRaider}; for a chat line, being low trust at all). Not yet trusted is most regulars for days after an install, and nearly every one of their joins
     * counts toward a burst, so counting them made three regulars voting a griefer out, or calling /rtv at map
     * end, a raid. While trust is new ({@code newTrust}) every regular is under the vote minimum, so being
     * refused counts only for a uuid with no active minute at all; a regular has one after a minute.
     */
    static boolean chorusVoice(Session s, boolean cohort, boolean flagged, int minutes, boolean newTrust, boolean refused) {
        if (flagged || cohort || s != null && s.coordinated > 0) return true;
        return refused && (!newTrust || minutes == 0);
    }
}
