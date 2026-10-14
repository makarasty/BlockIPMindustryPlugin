package BlockIp;

/**
 * One online player, from join to leave: what they joined with and how far their unit really moved.
 * Movement counts only from fresh client snapshots, so a headless bot that never sends one earns
 * nothing even while units pushing into it shove it about; a jump no unit could make between two
 * samples (a respawn, a teleport, a forged position) counts nothing either. Movement is displacement,
 * the farthest the unit got from where the minute (or the challenge) found it, not the path walked:
 * stepping back and forth between two points goes nowhere. After a jump the unit is measured again from
 * where it landed, and only a farther reach than before counts, so legs either side of jumps never add up.
 * Pure state; BlockPlugin feeds it. Main thread only.
 */
final class Session {
    /** Distance (world units, 8 per tile) a minute needs to count as active: a few tiles of walking. */
    static final float ACTIVE_DISTANCE = Tuning.Movement.ACTIVE_DISTANCE;
    /** A player with no active minute for this long is away: not counted among the players a kick vote needs. */
    static final long AFK_MS = Tuning.Movement.AFK_MS;
    /** Distance a minute with an action needs as well, so an action packet and a snapshot alone do not make it. */
    static final float ACTION_DISTANCE = Tuning.Movement.ACTION_DISTANCE;

    final String uuid, address, name;
    final long joined;
    /** Locale, version, mods, colour and device of the connect packet, hashed: a script sends the same one every time. */
    final int fingerprint;
    final boolean vpn;
    /** Counted toward a join burst when it joined: low trust, and not someone reconnecting after a restart. */
    final boolean burstJoin;
    /** The name as {@link Suspicion} compares it, worked out once: the name never changes in a session. */
    final String plain, shape;
    final boolean botLike;

    boolean snapshot;
    /** The farthest the unit got this minute and this challenge from where they began; moved sums the minutes. */
    float moved, minuteMoved, challengeMoved;
    /** This minute: world actions (built, broken, configured...), and whether a fresh snapshot came from the client. */
    int minuteActions;
    boolean minuteSnapshot;
    /** Times this player was one of several sending the same vote command or chat line at once. */
    int coordinated;
    boolean passedChallenge;
    /** When the last active minute ended (see {@link #takeMinute}); 0 before the first. */
    long lastActive;

    private int unitId = -1;
    private float x, y;
    /** Where this minute's and this challenge's displacement is measured from. */
    private float minuteX, minuteY, challengeX, challengeY;
    private long clientTime, sampledAt;

    Session(String uuid, String address, String name, long joined, int fingerprint, boolean vpn, boolean burstJoin) {
        this.uuid = uuid;
        this.address = address;
        this.name = name;
        this.joined = joined;
        this.fingerprint = fingerprint;
        this.vpn = vpn;
        this.burstJoin = burstJoin;
        plain = Suspicion.plain(name);
        shape = Suspicion.shapeOf(plain);
        botLike = Suspicion.botLikeName(name);
        sampledAt = joined;
    }

    /**
     * A sample of the player's unit at {@code now}. {@code lastClientTime} is when the server last got a
     * snapshot from this client (0 if never); {@code speed} is the unit's top speed per tick.
     */
    void sample(int unitId, float x, float y, float speed, long lastClientTime, long now) {
        long elapsed = Math.max(now - sampledAt, 1);
        sampledAt = now;
        boolean fresh = lastClientTime != 0 && lastClientTime != clientTime;
        clientTime = lastClientTime;
        snapshot |= fresh;
        minuteSnapshot |= fresh;
        float step = (float) Math.hypot(x - this.x, y - this.y);
        float max = speed * 60f * elapsed / 1000f * Tuning.Movement.SPEED_SLACK + Tuning.Movement.STEP_SLACK;
        if (!fresh || unitId != this.unitId || step > max) {
            // Not the player's own move: measure again from here
            minuteX = challengeX = x;
            minuteY = challengeY = y;
        }
        float minute = Math.max(minuteMoved, (float) Math.hypot(x - minuteX, y - minuteY));
        moved += minute - minuteMoved;
        minuteMoved = minute;
        challengeMoved = Math.max(challengeMoved, (float) Math.hypot(x - challengeX, y - challengeY));
        this.unitId = unitId;
        this.x = x;
        this.y = y;
    }

    /** A sample while the player has no unit: only whether the client still talks. */
    void sampleDead(long lastClientTime, long now) {
        sampledAt = now;
        boolean fresh = lastClientTime != 0 && lastClientTime != clientTime;
        snapshot |= fresh;
        minuteSnapshot |= fresh;
        clientTime = lastClientTime;
        unitId = -1;
    }

    /** A raid challenge's walk starts here, from where the unit is now. */
    void startChallenge() {
        challengeMoved = 0;
        challengeX = x;
        challengeY = y;
    }

    /** Not active this minute, nor in any minute that ended within {@link #AFK_MS}. */
    boolean afk(long now) {
        return now - lastActive > AFK_MS && !activeMinute();
    }

    /**
     * Walked; or acted while its client sent snapshots this minute, and either moved a little or acted a lot -
     * a builder stands still and builds at range, a script sending one action a minute does not.
     */
    private boolean activeMinute() {
        return minuteMoved >= ACTIVE_DISTANCE || minuteSnapshot && minuteActions > 0
                && (minuteMoved >= ACTION_DISTANCE || minuteActions >= Tuning.Movement.BUILDER_ACTIONS);
    }

    /**
     * At the minute: whether it was active (see {@link #activeMinute}), and a fresh minute starts, measured from
     * where the unit is now. An action alone is a packet a script can send with no unit, no client and no game.
     */
    boolean takeMinute(long now) {
        boolean active = activeMinute();
        if (active) lastActive = now;
        minuteActions = 0;
        minuteSnapshot = false;
        minuteMoved = 0;
        minuteX = x;
        minuteY = y;
        return active;
    }
}
