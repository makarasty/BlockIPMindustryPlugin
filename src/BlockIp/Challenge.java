package BlockIp;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * "Press the button with this word": a check a person passes in a second and a generic join bot, which
 * does not answer menus at all, does not. It is not proof against a bot written for this server: the
 * word travels in the same packet as the buttons, as it must in a Mindustry menu. So a pass is kept
 * narrow - bound to the uuid and address that earned it, and no use during attack mode. Holds only the
 * state; BlockPlugin shows the menu and kicks. Main thread only.
 */
final class Challenge {
    static final String[] WORDS = {"apple", "river", "stone", "cloud", "tiger", "piano", "rocket", "candle",
            "forest", "anchor", "silver", "garden", "lemon", "castle", "planet", "bridge"};
    static final int BUTTONS = 4;
    /** Clients challenged at connect and still to join, at most; see {@link Tuning.Challenge#MAX_PENDING}. */
    static final int MAX_PENDING = Tuning.Challenge.MAX_PENDING;

    enum Verdict {pass, fail, reshow, none}

    private static final class Pending {
        final long created;
        String[] options;
        int answer = -1;
        /** Raid mode: the buttons are not shown, and so cannot be answered, until the player's unit has moved. */
        final boolean move;
        /**
         * The connection that is still to join; null for a player in game, challenged there or since joined.
         * Let go at join: this is also the token a deadline timer holds, which would keep a closed
         * connection's buffers alive until it fired.
         */
        Object connection;

        Pending(long created, boolean move, Object connection) {
            this.created = created;
            this.move = move;
            this.connection = connection;
        }
    }

    private final HashMap<String, Pending> pending = new HashMap<>();
    /** "uuid|address" -> passed until (ms). Insertion order is age order, so the oldest go first when it is full. */
    private final LinkedHashMap<String, Long> passed = new LinkedHashMap<>();
    private final Random random;

    Challenge(Random random) {
        this.random = random;
    }

    boolean hasPassed(String uuid, String address, long now) {
        Long until = uuid == null ? null : passed.get(uuid + '|' + address);
        return until != null && now < until;
    }

    /** A player in game is challenged. False while this uuid waits on a connection that has not joined yet. */
    boolean expect(String uuid, long now) {
        return expect(uuid, now, false);
    }

    /** With {@code move}, the player has to walk before the buttons are shown (see {@link #waitsForMove}). */
    boolean expect(String uuid, long now, boolean move) {
        return expect(uuid, now, move, null, null);
    }

    /**
     * For {@code connection}, which vanilla let in and is downloading the map. False while the uuid waits on
     * another connection that has not joined yet: one uuid sent from a thousand addresses gets one challenge,
     * not a thousand that each hold their connection until they time out. With {@link #MAX_PENDING} waiting,
     * the one waiting longest is dropped and handed to {@code dropped}, which is no fault of its own.
     */
    boolean expect(String uuid, long now, boolean move, Object connection, BiConsumer<String, Object> dropped) {
        if (uuid == null) return false;
        Pending old = pending.get(uuid);
        if (old != null && old.connection != null) return false;
        if (connection != null) {
            // A scan, at most MAX_PENDING plus the players online, and only for a join that is challenged
            int waiting = 0;
            String oldest = null;
            long oldestAt = Long.MAX_VALUE;
            for (Map.Entry<String, Pending> e : pending.entrySet()) {
                Pending p = e.getValue();
                if (p.connection == null) continue;
                waiting++;
                if (p.created < oldestAt) {
                    oldestAt = p.created;
                    oldest = e.getKey();
                }
            }
            if (waiting >= MAX_PENDING) dropped.accept(oldest, pending.remove(oldest).connection);
        }
        pending.put(uuid, new Pending(now, move, connection));
        return true;
    }

    /** The player joined: their join deadline no longer applies, and their connection is let go. */
    void joined(String uuid) {
        Pending p = pending.get(uuid);
        if (p != null) p.connection = null;
    }

    /**
     * A raid-mode challenge whose buttons are not out yet. The answer index exists only once they are, so
     * a script cannot answer before its unit has moved, whatever it sends.
     */
    boolean waitsForMove(String uuid) {
        Pending p = pending.isEmpty() || uuid == null ? null : pending.get(uuid);
        return p != null && p.move && p.options == null;
    }

    /** Cheap enough for the action and chat filters: nothing to look up while nobody is being challenged. */
    boolean isPending(String uuid) {
        return !pending.isEmpty() && uuid != null && pending.containsKey(uuid);
    }

    /**
     * At join, or again after the menu was closed: the buttons to show, with the word to press at index
     * {@link #word}. Null when this uuid has no challenge.
     */
    String[] options(String uuid) {
        Pending p = pending.get(uuid);
        if (p == null) return null;
        p.connection = null;
        if (p.options == null) {
            String[] options = new String[BUTTONS];
            int filled = 0;
            while (filled < BUTTONS) {
                String word = WORDS[random.nextInt(WORDS.length)];
                boolean taken = false;
                for (int i = 0; i < filled; i++) taken |= options[i].equals(word);
                if (!taken) options[filled++] = word;
            }
            p.options = options;
            p.answer = random.nextInt(BUTTONS);
        }
        return p.options;
    }

    String word(String uuid) {
        Pending p = pending.get(uuid);
        return p == null || p.options == null ? null : p.options[p.answer];
    }

    /** The player pressed {@code option}, or closed the menu (-1). */
    Verdict answer(String uuid, String address, int option, long now, long passMs) {
        Pending p = pending.get(uuid);
        if (p == null || p.options == null) return Verdict.none;
        if (option < 0) return Verdict.reshow;
        pending.remove(uuid);
        if (option != p.answer) return Verdict.fail;
        String key = uuid + '|' + address;
        passed.remove(key);
        passed.put(key, now + passMs);
        while (passed.size() > Tuning.Challenge.MAX_PASSED) {
            Iterator<String> oldest = passed.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        return Verdict.pass;
    }

    /**
     * Identifies this challenge, so a timer set for it cannot expire a later one for the same uuid. Taken once
     * joined, when it no longer holds the connection.
     */
    Object token(String uuid) {
        return pending.get(uuid);
    }

    /** Timed out: true if that same challenge was still waiting, and is now dropped. */
    boolean expire(String uuid, Object token) {
        return token != null && pending.remove(uuid, token);
    }

    /**
     * Drops each challenge whose connection closed before it joined, or is still not in after
     * {@code graceMs}, and hands it to {@code dropped}. A client that takes the map and never confirms
     * never reaches PlayerJoin and never leaves, so without this it would hold its place for good.
     */
    void dropNeverJoined(long now, long graceMs, Predicate<Object> connected, BiConsumer<String, Object> dropped) {
        if (pending.isEmpty()) return;
        for (Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Pending> e = it.next();
            Pending p = e.getValue();
            if (p.connection == null || now - p.created <= graceMs && connected.test(p.connection)) continue;
            it.remove();
            dropped.accept(e.getKey(), p.connection);
        }
    }

    /**
     * The player left. True when they left a challenge they were in game with still open, and it is now
     * dropped: leaving is failing it, or a bot leaves a second before its deadline and comes back clean. One
     * still to join, perhaps on another connection of the same uuid, is left to {@link #dropNeverJoined}.
     */
    boolean leave(String uuid) {
        Pending p = pending.get(uuid);
        if (p == null || p.connection != null) return false;
        pending.remove(uuid);
        return true;
    }

    /** Drops challenges for players who never finished joining, and passes that ran out. */
    void sweep(long now, long staleMs) {
        pending.values().removeIf(p -> now - p.created > staleMs);
        for (Iterator<Map.Entry<String, Long>> it = passed.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() <= now) it.remove();
        }
    }

    int pendingCount() {
        return pending.size();
    }
}
