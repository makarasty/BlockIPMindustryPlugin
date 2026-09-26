package BlockIp;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

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
    /** Challenges waiting at once past which new VPN joins are refused outright: that many is a flood. */
    static final int MAX_PENDING = 200;
    /** Remembered passes past which the oldest are forgotten. */
    static final int MAX_PASSED = 10_000;

    enum Verdict {pass, fail, reshow, none}

    private static final class Pending {
        final long created;
        String[] options;
        int answer = -1;
        boolean joined;

        Pending(long created) {
            this.created = created;
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

    /** At the connect packet: this uuid gets a challenge once it joins. False when too many are waiting already. */
    boolean expect(String uuid, long now) {
        if (uuid == null) return false;
        if (!pending.containsKey(uuid) && pending.size() >= MAX_PENDING) return false;
        pending.put(uuid, new Pending(now));
        return true;
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
        p.joined = true;
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
        while (passed.size() > MAX_PASSED) {
            Iterator<String> oldest = passed.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        return Verdict.pass;
    }

    /** Identifies this challenge, so a timer set for it cannot expire a later one for the same uuid. */
    Object token(String uuid) {
        return pending.get(uuid);
    }

    /** Timed out: true if that same challenge was still waiting, and is now dropped. */
    boolean expire(String uuid, Object token) {
        return token != null && pending.remove(uuid, token);
    }

    /** The connect-time deadline: drops the challenge only if its client never finished joining. */
    boolean expireIfNeverJoined(String uuid, Object token) {
        return token instanceof Pending p && !p.joined && pending.remove(uuid, token);
    }

    void leave(String uuid) {
        pending.remove(uuid);
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
