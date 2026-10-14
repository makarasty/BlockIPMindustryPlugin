package BlockIp;

import arc.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * What this server knows about each uuid: how many minutes it was really active (moving or
 * building, never idling), on how many days, and whether it failed a challenge. A raider keeps his
 * uuids stable to beat playtime gates by letting bots idle; active minutes are what idling cannot
 * earn. Main thread only, except {@link #write}, which works on a {@link #snapshot}.
 *
 * A record is created only by an active minute, so a flood of fresh uuids that never moves costs no
 * memory. A flagged uuid without one sits in a smaller set of its own, so failed bots cannot push
 * real players' records out either.
 */
final class TrustStore {
    static final int MAX_RECORDS = Tuning.Trust.MAX_RECORDS;
    static final int MAX_FLAGGED = Tuning.Trust.MAX_FLAGGED;
    /**
     * Records under this many minutes are kept apart, and go first when the store is full: farming new
     * uuids evicts other new uuids, never a veteran, so pushing one out costs a raider this many minutes.
     */
    static final int PROBATION_MINUTES = Tuning.Trust.PROBATION_MINUTES;
    /**
     * A record on probation not seen for this long is dropped at load, its flag kept: otherwise every
     * passer-by who walked once stays for good, and the store settles at its cap, copied and written whole.
     */
    static final long PROBATION_KEEP_MS = Tuning.Trust.PROBATION_KEEP_MS;
    private static final String HEADER = "BlockIp trust 1";
    private static final long DAY = 86_400_000L;
    /** One save at a time, whether from the minute's daemon thread or from exit. */
    private static final Object WRITE_LOCK = new Object();

    static final class Record {
        int minutes;
        long firstSeen, lastSeen;
        int days;
        long lastDay = -1;
        boolean flagged;

        Record copy() {
            Record r = new Record();
            r.minutes = minutes;
            r.firstSeen = firstSeen;
            r.lastSeen = lastSeen;
            r.days = days;
            r.lastDay = lastDay;
            r.flagged = flagged;
            return r;
        }
    }

    /**
     * Veterans ({@link #PROBATION_MINUTES} or more) and the rest, apart. Insertion order is "least recently
     * seen first": a uuid seen again is moved to the end. A uuid is in one of the two, never both.
     */
    private final LinkedHashMap<String, Record> records = new LinkedHashMap<>(), probation = new LinkedHashMap<>();
    /** Flagged uuids that have no record, oldest first. */
    private final LinkedHashSet<String> flaggedOnly = new LinkedHashSet<>();
    private boolean dirty;

    Record get(String uuid) {
        if (uuid == null) return null;
        Record r = records.get(uuid);
        return r != null ? r : probation.get(uuid);
    }

    boolean isFlagged(String uuid) {
        Record r = get(uuid);
        return r != null ? r.flagged : uuid != null && flaggedOnly.contains(uuid);
    }

    int minutes(String uuid) {
        Record r = get(uuid);
        return r == null ? 0 : r.minutes;
    }

    /** At least {@code minutes} active minutes over at least {@code days} days a day apart, and never flagged since. */
    boolean isTrusted(String uuid, int minutes, int days) {
        Record r = get(uuid);
        return r != null && !r.flagged && r.minutes >= minutes && r.days >= days;
    }

    /**
     * One active minute for {@code uuid}. Counts a new day when the last active one was another (UTC) day and a
     * full day more has passed since the first: by date alone, one minute before midnight and the rest after it
     * made two days, and a uuid farmed for five hours was trusted.
     */
    void active(String uuid, long now) {
        Record r = remove(uuid);
        if (r == null) {
            r = new Record();
            r.firstSeen = now;
            r.flagged = flaggedOnly.remove(uuid);
            evictIfFull();
        }
        r.minutes++;
        long day = now / DAY;
        if (day != r.lastDay && now - r.firstSeen >= r.days * DAY) {
            r.days++;
            r.lastDay = day;
        }
        r.lastSeen = now;
        put(uuid, r);
        dirty = true;
    }

    /** At leave: a known uuid moves to the recently seen end. An unknown one is not recorded. */
    void seen(String uuid, long now) {
        Record r = remove(uuid);
        if (r == null) return;
        r.lastSeen = now;
        put(uuid, r);
        dirty = true;
    }

    private Record remove(String uuid) {
        Record r = records.remove(uuid);
        return r != null ? r : probation.remove(uuid);
    }

    private void put(String uuid, Record r) {
        (r.minutes < PROBATION_MINUTES ? probation : records).put(uuid, r);
    }

    void flag(String uuid) {
        if (uuid == null) return;
        Record r = get(uuid);
        if (r != null) {
            r.flagged = true;
        } else if (flaggedOnly.add(uuid) && flaggedOnly.size() > MAX_FLAGGED) {
            Iterator<String> oldest = flaggedOnly.iterator();
            oldest.next();
            oldest.remove();
        }
        dirty = true;
    }

    /** Returns false when the uuid was not flagged. */
    boolean unflag(String uuid) {
        Record r = get(uuid);
        boolean was = r != null ? r.flagged : uuid != null && flaggedOnly.remove(uuid);
        if (r != null) r.flagged = false;
        dirty |= was;
        return was;
    }

    int size() {
        return records.size() + probation.size();
    }

    /** When the first record still kept was made: about when this server started keeping trust. {@code now} if none. */
    long firstSeen(long now) {
        long first = now;
        for (Record r : records.values()) first = Math.min(first, r.firstSeen);
        for (Record r : probation.values()) first = Math.min(first, r.firstSeen);
        return first;
    }

    int flaggedCount() {
        int[] count = {flaggedOnly.size()};
        records.values().forEach(r -> count[0] += r.flagged ? 1 : 0);
        probation.values().forEach(r -> count[0] += r.flagged ? 1 : 0);
        return count[0];
    }

    /** Past the cap, the least recently seen record on probation goes; a veteran only when there is none. */
    private void evictIfFull() {
        if (size() < MAX_RECORDS) return;
        Iterator<Record> it = (probation.isEmpty() ? records : probation).values().iterator();
        it.next();
        it.remove();
    }

    /**
     * Null when nothing changed since the last one. Copies the records, so the write can run on another
     * thread while this one goes on changing them.
     */
    List<Map.Entry<String, Record>> snapshot() {
        if (!dirty) return null;
        dirty = false;
        ArrayList<Map.Entry<String, Record>> copy = new ArrayList<>(size() + flaggedOnly.size());
        for (String uuid : flaggedOnly) {
            Record r = new Record();
            r.flagged = true;
            copy.add(Map.entry(uuid, r));
        }
        records.forEach((uuid, r) -> copy.add(Map.entry(uuid, r.copy())));
        probation.forEach((uuid, r) -> copy.add(Map.entry(uuid, r.copy())));
        return copy;
    }

    /** A save that failed: the next minute tries again. */
    void markDirty() {
        dirty = true;
    }

    /** Into a temp file beside {@code file}, then renamed over it, so a crash mid-write leaves the old one whole. */
    static void write(List<Map.Entry<String, Record>> entries, Path file) throws IOException {
        synchronized (WRITE_LOCK) {
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (BufferedWriter out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                out.write(HEADER);
                out.newLine();
                StringBuilder line = new StringBuilder(96);
                for (Map.Entry<String, Record> e : entries) {
                    Record r = e.getValue();
                    line.setLength(0);
                    line.append(e.getKey()).append(' ').append(r.minutes).append(' ').append(r.firstSeen).append(' ')
                            .append(r.lastSeen).append(' ').append(r.days).append(' ').append(r.lastDay).append(' ')
                            .append(r.flagged ? 1 : 0);
                    out.write(line.toString());
                    out.newLine();
                }
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * A missing file is an empty store. A file that does not read back - truncated, edited by hand,
     * another format - is moved aside as {@code <name>.bad-<time>} and the store starts empty: losing
     * the trust is better than a server that does not start, and the bad copy is kept to look at.
     */
    static TrustStore load(Path file, long now) {
        TrustStore store = new TrustStore();
        if (!Files.exists(file)) return store;
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            if (!HEADER.equals(in.readLine())) throw new IOException("no header");
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                if (line.isEmpty()) continue;
                String[] f = line.split(" ");
                if (f.length != 7 || f[0].isEmpty()) throw new IOException("bad line: " + line);
                Record r = new Record();
                r.minutes = Integer.parseInt(f[1]);
                r.firstSeen = Long.parseLong(f[2]);
                r.lastSeen = Long.parseLong(f[3]);
                r.days = Integer.parseInt(f[4]);
                r.lastDay = Long.parseLong(f[5]);
                r.flagged = "1".equals(f[6]);
                if (r.minutes <= 0 || r.minutes < PROBATION_MINUTES && now - r.lastSeen > PROBATION_KEEP_MS) {
                    if (r.flagged) store.flag(f[0]);
                } else {
                    store.evictIfFull();
                    store.put(f[0], r);
                }
            }
            store.dirty = false;
            return store;
        } catch (Exception e) {
            Path aside = file.resolveSibling(file.getFileName() + ".bad-" + System.currentTimeMillis());
            try {
                Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailed) {
                aside = file;
            }
            Log.err("BlockIp: the trust file did not read back (@), starting with no trust; the file is kept as @", e.toString(), aside);
            return new TrustStore();
        }
    }
}
