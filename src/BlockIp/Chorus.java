package BlockIp;

import arc.util.Strings;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "The same thing from several players at once": twelve bots sending one /votekick within a second,
 * fifteen posting one chat line. Counts distinct senders per key within a window. Bounded both ways,
 * so a flood of distinct lines only churns it: past {@link #MAX_KEYS} the least recently used key is
 * dropped, past {@link #MAX_VOICES} senders the oldest. Main thread only.
 */
final class Chorus {
    static final int MAX_KEYS = Tuning.Chorus.MAX_KEYS;
    static final int MAX_VOICES = Tuning.Chorus.MAX_VOICES;
    static final int MAX_KEY_LENGTH = Tuning.Chorus.MAX_KEY_LENGTH;

    /**
     * key -> sender -> last heard (ms), in the order first heard; access order, so the eldest key is the least
     * recently used. First heard, not last: one who says it again stays the one the others echoed.
     */
    private final LinkedHashMap<String, LinkedHashMap<String, Long>> keys = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, LinkedHashMap<String, Long>> eldest) {
            return size() > MAX_KEYS;
        }
    };

    /**
     * Hears {@code sender} say {@code key}; returns everyone who said it within the window, {@code sender}
     * included, first heard first.
     */
    Set<String> add(String key, String sender, long now, long windowMs) {
        LinkedHashMap<String, Long> voices = keys.computeIfAbsent(key, k -> new LinkedHashMap<>());
        // A full scan, since first-heard order is not time order: at most MAX_VOICES
        voices.values().removeIf(heard -> now - heard > windowMs);
        // A sender heard already keeps their place: re-putting a key leaves a LinkedHashMap's order as it was
        voices.put(sender, now);
        if (voices.size() > MAX_VOICES) {
            Iterator<String> oldest = voices.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        return voices.keySet();
    }

    /** Whether {@code sender} said {@code key} within the window already: saying it again counts them once. */
    boolean heard(String key, String sender, long now, long windowMs) {
        LinkedHashMap<String, Long> voices = keys.get(key);
        Long heard = voices == null ? null : voices.get(sender);
        return heard != null && now - heard <= windowMs;
    }

    int size() {
        return keys.size();
    }

    /**
     * What a chat line is compared by: no colours, lower case, letters and digits only (no spaces,
     * punctuation, zero-width or icon glyphs, which are private-use characters), runs of a letter
     * collapsed, so "Da", "d a", "daaa" and "[red]da!" are one line. A line of nothing but symbols
     * ("+", "?") keeps its symbols instead of becoming empty.
     */
    static String chatKey(String text) {
        String plain = Strings.stripColors(text).toLowerCase(Locale.ROOT);
        StringBuilder key = new StringBuilder(Math.min(plain.length(), MAX_KEY_LENGTH));
        int last = -1;
        for (int i = 0; i < plain.length() && key.length() < MAX_KEY_LENGTH; ) {
            int cp = plain.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.PRIVATE_USE || cp == last) continue;
            key.appendCodePoint(cp);
            last = cp;
        }
        if (key.length() > 0) return key.toString();
        for (int i = 0; i < plain.length() && key.length() < MAX_KEY_LENGTH; i++) {
            char ch = plain.charAt(i);
            if (!Character.isWhitespace(ch)) key.append(ch);
        }
        return key.toString();
    }
}
