package BlockIp;

import arc.util.Log;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The connect filter. Arc's network thread calls it for every TCP connection before the server
 * allocates the connection's buffers, so refusing a flood here costs neither memory nor main-thread
 * time. It answers two questions, both by address alone: is this address serving a block, and has it
 * opened more connections than the window allows.
 */
final class FloodGuard {
    /** Past this many tracked addresses a sweep that frees nothing starts over: a flood from that many is not rate-limitable. */
    static final int MAX_TRACKED = 10_000;
    /** A block list this long stops growing; addresses already on it stay blocked until they expire. */
    static final int MAX_BLOCKED = 50_000;

    /** address -> blocked until (ms). Written by the main thread as well, when a join is refused. */
    private static final ConcurrentHashMap<String, Long> blocked = new ConcurrentHashMap<>();
    /** address -> its current window. Network thread only. */
    private static final HashMap<String, Window> windows = new HashMap<>();
    private static long nextSweep;

    /** Connections refused here since the last summary; read and reset by the main thread. */
    static final AtomicInteger refused = new AtomicInteger();

    private static final class Window {
        long start;
        int count;
    }

    private FloodGuard() {
    }

    /**
     * @param maxPerWindow connections one address may open per window; 0 or less turns rate limiting off
     */
    static boolean accept(String address, long now, int maxPerWindow, long windowMs, long blockMs) {
        Long until = blocked.get(address);
        if (until != null) {
            if (now < until) {
                refused.incrementAndGet();
                return false;
            }
            blocked.remove(address, until);
        }
        // Before the limit check: blocks placed by the main thread expire even with rate limiting off
        if (now >= nextSweep) sweep(now, windowMs);
        if (maxPerWindow <= 0) return true;

        Window window = windows.get(address);
        if (window == null) {
            window = new Window();
            window.start = now;
            windows.put(address, window);
        } else if (now - window.start >= windowMs) {
            window.start = now;
            window.count = 0;
        }
        if (++window.count <= maxPerWindow) return true;

        windows.remove(address);
        if (block(address, now + blockMs)) {
            Log.info("BlockIp: @ opened more than @ connections in @s, blocked for @s", address, maxPerWindow, windowMs / 1000, blockMs / 1000);
        }
        refused.incrementAndGet();
        return false;
    }

    /** Refuses the address at accept until {@code until}. Returns false when it was blocked already or the list is full. */
    static boolean block(String address, long until) {
        if (blocked.size() >= MAX_BLOCKED && !blocked.containsKey(address)) return false;
        Long previous = blocked.put(address, until);
        return previous == null;
    }

    static int blockedCount() {
        return blocked.size();
    }

    private static void sweep(long now, long windowMs) {
        nextSweep = now + Math.max(windowMs, 1_000);
        windows.values().removeIf(window -> now - window.start >= windowMs);
        if (windows.size() > MAX_TRACKED) windows.clear();
        for (Iterator<Map.Entry<String, Long>> it = blocked.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() <= now) it.remove();
        }
    }

    /** Tests only: forget everything. */
    static void reset() {
        blocked.clear();
        windows.clear();
        nextSweep = 0;
        refused.set(0);
    }
}
