package BlockIp;

/**
 * One online player, from join to leave: what they joined with and how far their unit really moved.
 * Movement counts only from fresh client snapshots, so a headless bot that never sends one earns
 * nothing even while units pushing into it shove it about; a jump no unit could make between two
 * samples (a respawn, a teleport, a forged position) counts nothing either. Pure state; BlockPlugin
 * feeds it. Main thread only.
 */
final class Session {
    /** Distance (world units, 8 per tile) a minute needs to count as active: a few tiles of walking. */
    static final float ACTIVE_DISTANCE = Tuning.Movement.ACTIVE_DISTANCE;
    /** A player with no active minute for this long is away: not counted among the players a kick vote needs. */
    static final long AFK_MS = Tuning.Movement.AFK_MS;

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
    float moved, minuteMoved, challengeMoved;
    /** This minute: something built, broken or configured, and a fresh snapshot from the client. */
    boolean minuteAction, minuteSnapshot;
    /** Times this player was one of several sending the same vote command or chat line at once. */
    int coordinated;
    boolean passedChallenge;
    /** When the last active minute ended (see {@link #takeMinute}); 0 before the first. */
    long lastActive;

    private int unitId = -1;
    private float x, y;
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
        if (fresh && unitId == this.unitId) {
            float step = (float) Math.hypot(x - this.x, y - this.y);
            float max = speed * 60f * elapsed / 1000f * Tuning.Movement.SPEED_SLACK + Tuning.Movement.STEP_SLACK;
            if (step <= max) {
                moved += step;
                minuteMoved += step;
                challengeMoved += step;
            }
        }
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

    /** Not active this minute, nor in any minute that ended within {@link #AFK_MS}. */
    boolean afk(long now) {
        return now - lastActive > AFK_MS && minuteMoved < ACTIVE_DISTANCE && !(minuteAction && minuteSnapshot);
    }

    /**
     * At the minute: whether it was active (walked, or acted while its client sent snapshots), and a fresh
     * minute starts. An action alone is a packet a script can send with no unit, no client and no game.
     */
    boolean takeMinute(long now) {
        boolean active = minuteAction && minuteSnapshot || minuteMoved >= ACTIVE_DISTANCE;
        if (active) lastActive = now;
        minuteAction = minuteSnapshot = false;
        minuteMoved = 0;
        return active;
    }
}
