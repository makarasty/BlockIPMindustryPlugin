package BlockIp;

/**
 * Every fixed tunable of the plugin in one place: score weights, limits, caps, timer periods and
 * timeouts that are not in config.json. Changing a value here changes behaviour, so rebuild and run
 * the tests after. Values already in config.json (see {@link ConfigManager}) and message texts
 * ({@link Texts}) are not repeated here. Units are in each name or comment: ms, seconds, minutes.
 */
final class Tuning {
    private Tuning() {}

    /** Timer periods set once when the plugin loads. */
    static final class Timers {
        private Timers() {}

        /**
         * Period of the tick that ends each player's active minute, saves trust, sweeps challenges
         * and logs the summary, in seconds. It also defines the "minute" that trust and activity
         * are counted in.
         */
        static final float MINUTE_TICK_SECONDS = 60f;

        /** Period (and first delay) of the sample of every player's unit, in seconds. */
        static final float SAMPLE_SECONDS = 2f;

        /**
         * How often the trust file is saved when it changed, in minutes (shutdown saves it as
         * well).
         */
        static final int SAVE_MINUTES = 10;

        /**
         * A challenge for someone who never finished joining is dropped after this long (a few
         * minutes), in ms.
         */
        static final long STALE_CHALLENGE_MS = 5 * 60_000L;
    }

    /** Caps on what the main plugin class remembers. */
    static final class Plugin {
        private Plugin() {}

        /**
         * Connect packets kept from PlayerConnect to the join; the oldest go first if clients pile
         * up that never join.
         */
        static final int MAX_ARRIVALS = 1_000;

        /**
         * Players remembered as having passed a challenge during one raid; past it nobody more is
         * added.
         */
        static final int MAX_PASSED_THIS_RAID = 10_000;
    }

    /**
     * The suspicion score: points per signal (see {@link BlockIp.Suspicion}). Plus raises the
     * score, minus lowers it.
     */
    static final class Suspicion {
        private Suspicion() {}

        /**
         * A uuid active this recently is a regular reconnecting (after a restart, say), not part of
         * a burst; in ms.
         */
        static final long RECONNECT_MS = 15 * 60_000L;

        /** Upper bound of the score. */
        static final int MAX_SCORE = 100;

        /** Points for a uuid with no active minute at all. */
        static final int NO_ACTIVE_TIME_POINTS = 25;

        /** Points for a uuid under voteMinActiveMinutes active minutes. */
        static final int UNDER_VOTE_MINUTES_POINTS = 15;

        /** Points for a uuid past voteMinActiveMinutes but under trustedMinutes. */
        static final int NOT_TRUSTED_POINTS = 10;

        /** Points (negative) for a uuid at trustedMinutes active minutes or more. */
        static final int TRUSTED_MINUTES_POINTS = -30;

        /** Points (negative) for a uuid active on trustedDays days or more. */
        static final int TRUSTED_DAYS_POINTS = -15;

        /** Points for having joined in a burst (see {@link BlockIp.Suspicion#inCohort}). */
        static final int BURST_POINTS = 15;

        /** Points for look-alike names in the burst, once this many others have one. */
        static final int LOOKALIKE_POINTS = 10;

        /** How many look-alike names in the burst it takes to score {@link #LOOKALIKE_POINTS}. */
        static final int LOOKALIKE_MIN_COUNT = 2;

        /**
         * Points for identical clients in the burst, once this many others share the fingerprint.
         */
        static final int IDENTICAL_CLIENTS_POINTS = 10;

        /**
         * How many identical clients in the burst it takes to score {@link
         * #IDENTICAL_CLIENTS_POINTS}.
         */
        static final int IDENTICAL_CLIENTS_MIN_COUNT = 2;

        /** A player online longer than this with no client snapshot is scored for it, in ms. */
        static final int NO_SNAPSHOT_AFTER_MS = 20_000;

        /** Points for no client snapshot after {@link #NO_SNAPSHOT_AFTER_MS}. */
        static final int NO_SNAPSHOT_POINTS = 20;

        /** A player online longer than this who has not moved is scored for it, in ms. */
        static final int NOT_MOVED_AFTER_MS = 60_000;

        /** Points for not having moved after {@link #NOT_MOVED_AFTER_MS}. */
        static final int NOT_MOVED_POINTS = 10;

        /** Points for a short digit name (see {@link Names}). */
        static final int BOT_LIKE_NAME_POINTS = 5;

        /** Points for a VPN or hosting address. */
        static final int VPN_POINTS = 15;

        /**
         * Points per time the player was one of several sending the same vote command or chat line
         * at once.
         */
        static final int COORDINATED_POINTS_EACH = 15;

        /** Times counted for {@link #COORDINATED_POINTS_EACH}, at most. */
        static final int COORDINATED_MAX_TIMES = 2;

        /** Points (negative) for having passed a challenge. */
        static final int PASSED_CHALLENGE_POINTS = -20;

        /** Points for a uuid flagged by a failed challenge. */
        static final int FLAGGED_POINTS = 50;
    }

    /** How names are judged and compared (see {@link BlockIp.Suspicion}). */
    static final class Names {
        private Names() {}

        /**
         * A bot-like name is at least this many characters (letters and digits only, at least half
         * digits).
         */
        static final int BOT_LIKE_MIN_LENGTH = 2;

        /** A bot-like name is at most this many characters. */
        static final int BOT_LIKE_MAX_LENGTH = 6;

        /** Characters of a name looked at when working out its shape. */
        static final int SHAPE_MAX_LENGTH = 16;

        /**
         * Look-alike by length: two names of one length are alike only from this many characters.
         */
        static final int LOOKALIKE_MIN_LENGTH = 2;

        /**
         * Look-alike by length: two names of one length are alike only up to this many characters.
         */
        static final int LOOKALIKE_MAX_LENGTH = 8;

        /** Look-alike by length: how many leading characters the two names must share. */
        static final int LOOKALIKE_SHARED_PREFIX = 2;
    }

    /** What a player's unit has to do to count (see {@link Session}). */
    static final class Movement {
        private Movement() {}

        /**
         * Distance (world units, 8 per tile) a minute needs to count as active: five tiles from
         * where it began.
         */
        static final float ACTIVE_DISTANCE = 40f;

        /**
         * Distance (world units) that shows a challenged player can move: three tiles from where
         * the challenge found them.
         */
        static final float CHALLENGE_DISTANCE = 24f;

        /**
         * Distance (world units) a minute with a build, break or config also needs to count as
         * active: one tile.
         */
        static final float ACTION_DISTANCE = 8f;

        /**
         * Distinct tiles acted on in a minute (with a fresh snapshot) that make it active however
         * little the unit moved: a builder at range.
         */
        static final int BUILDER_ACTIONS = 20;

        /** Distinct tiles remembered per player per minute; only needs to reach BUILDER_ACTIONS. */
        static final int MAX_ACTION_TILES = 64;

        /** Head room over the unit's top speed before a step reads as a teleport (factor). */
        static final float SPEED_SLACK = 1.5f;

        /**
         * Flat head room (world units) added to the largest step a unit could make between two
         * samples.
         */
        static final float STEP_SLACK = 8f;

        /**
         * A player with no active minute for this long is away: not counted among the players a
         * kick vote needs; in ms.
         */
        static final long AFK_MS = 5 * 60_000L;
    }

    /** The button challenge (see {@link BlockIp.Challenge}). */
    static final class Challenge {
        private Challenge() {}

        /**
         * Clients challenged at connect and still to join, at most: past it, each new one drops the
         * one waiting longest. Turning the newest away instead let a few dozen clients that take
         * the map and never confirm hold every place, and every real newcomer was refused. A player
         * challenged in game takes no place: the player limit bounds those already.
         */
        static final int MAX_PENDING = 200;

        /** Remembered passes past which the oldest are forgotten. */
        static final int MAX_PASSED = 10_000;

        /**
         * How long a client may take to download the map and join before it is treated as never
         * joining, in seconds.
         */
        static final int JOIN_GRACE_SECONDS = 180;

        /**
         * The shortest time to press the button after a join challenge, in seconds, whatever
         * challengeSeconds says.
         */
        static final int MIN_SECONDS = 5;

        /**
         * The shortest time to walk and press after a raid-mode challenge, in seconds, whatever
         * raidChallengeSeconds says.
         */
        static final int MIN_RAID_SECONDS = 15;

        /**
         * How long a VPN address that failed a challenge is kicked for (so it cannot reconnect at
         * once), in ms.
         */
        static final long VPN_FAIL_KICK_MS = 30_000L;
    }

    /** Votes held back or counted by the vote gate (see {@link KickVote}). */
    static final class Votes {
        private Votes() {}

        /** Yes votes a kick of a trusted player needs, never under this many. */
        static final int MIN_YES_VOTES = 3;

        /**
         * A map change by a player alone skips the active minutes at most once per this long,
         * server-wide: each one is a world load and a wipe of the map's history, and a fresh uuid
         * could otherwise repeat it at will.
         */
        static final long SOLO_FREE_MS = 5 * 60_000L;

        /**
         * How long the free pass the gate gave holds for Essentials' own check of the same /rtv, in
         * ms.
         */
        static final long SOLO_HOOK_MS = 5_000L;
    }

    /** Chat line floods (see {@link Chorus}). */
    static final class Chat {
        private Chat() {}

        /**
         * Chat from a client that has sent no snapshot this long after joining is dropped (a
         * headless bot); in ms.
         */
        static final long NO_SNAPSHOT_GRACE_MS = 5_000L;

        /** A line of up to this many characters ("gg", "da", "+") is a short line. */
        static final int SHORT_LINE_MAX_LENGTH = 3;

        /** A short line needs this many times the senders of a flood. */
        static final int SHORT_LINE_SENDER_FACTOR = 2;
    }

    /** The "same thing from several players" counter (see {@link BlockIp.Chorus}). */
    static final class Chorus {
        private Chorus() {}

        /**
         * Distinct vote commands or chat lines tracked; past it the least recently used key is
         * dropped.
         */
        static final int MAX_KEYS = 512;

        /** Senders tracked per key; past it the oldest is dropped. */
        static final int MAX_VOICES = 64;

        /** Characters of a chat line kept in its key. */
        static final int MAX_KEY_LENGTH = 64;
    }

    /** The connect filter that runs at accept (see {@link FloodGuard}). */
    static final class FloodGuard {
        private FloodGuard() {}

        /**
         * Past this many tracked addresses a sweep that frees nothing starts over: a flood from
         * that many is not rate-limitable.
         */
        static final int MAX_TRACKED = 10_000;

        /**
         * A block list this long stops growing; addresses already on it stay blocked until they
         * expire.
         */
        static final int MAX_BLOCKED = 50_000;

        /**
         * The shortest time between two sweeps of the tracked addresses and blocks, in ms, whatever
         * connectionWindowMs says.
         */
        static final int MIN_SWEEP_INTERVAL_MS = 1_000;
    }

    /** The trust store (see {@link TrustStore}). */
    static final class Trust {
        private Trust() {}

        /**
         * Records kept; past it the least recently seen record on probation goes, a veteran only
         * when there is none.
         */
        static final int MAX_RECORDS = 100_000;

        /** Flagged uuids without a record kept; past it the oldest goes first. */
        static final int MAX_FLAGGED = 20_000;

        /**
         * Records under this many minutes are kept apart, and go first when the store is full:
         * farming new uuids evicts other new uuids, never a veteran, so pushing one out costs a
         * raider this many minutes.
         */
        static final int PROBATION_MINUTES = 30;

        /**
         * A record on probation not seen for this long is dropped at load, its flag kept: otherwise
         * every passer-by who walked once stays for good, and the store settles at its cap, copied
         * and written whole. In ms.
         */
        static final long PROBATION_KEEP_MS = 30 * 86_400_000L;
    }

    /** The downloaded VPN and hosting list (see {@link VpnList}). */
    static final class VpnList {
        private VpnList() {}

        /**
         * Per source, in characters. The real lists are under 1 MB; anything past this is not a
         * list of networks. Tests lower it.
         */
        static final long MAX_SOURCE_CHARS = 16L << 20;

        /** Time allowed to connect to a list source, in ms. */
        static final int CONNECT_TIMEOUT_MS = 15_000;

        /** Time allowed to read from a list source, in ms. */
        static final int READ_TIMEOUT_MS = 30_000;
    }
}
