package BlockIp;

import arc.files.Fi;
import arc.util.Log;
import arc.util.Threads;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hosting and VPN networks, held as {@link IpRanges}.
 *
 * The lists are downloaded into one cache file beside the config and parsed from there, so a
 * restart reads the disk instead of the network, and a failed download keeps the last good copy.
 * Downloading and parsing run on a short-lived daemon thread; the finished ranges replace the old
 * ones in one volatile write, so a join never waits on either.
 */
final class VpnList {
    /** Per source. The real lists are under 1 MB; anything past this is not a list of networks. Tests lower it. */
    static long maxSourceChars = 16L << 20;

    private static volatile IpRanges ranges = IpRanges.EMPTY;
    private static final AtomicBoolean loading = new AtomicBoolean();

    private VpnList() {
    }

    static boolean contains(String address) {
        return ranges.contains(address);
    }

    static int size() {
        return ranges.size();
    }

    /**
     * Loads the cache, and downloads first when it is missing, older than {@code maxAgeMs}, or
     * {@code force} is set. Returns at once; a load already running is not started twice.
     */
    static void refresh(List<String> urls, Fi cache, long maxAgeMs, boolean force) {
        if (!loading.compareAndSet(false, true)) return;
        Threads.daemon("BlockIp-vpn-list", () -> {
            try {
                boolean stale = !cache.exists() || System.currentTimeMillis() - cache.lastModified() > maxAgeMs;
                if ((force || stale) && !urls.isEmpty()) download(urls, cache);
                if (cache.exists()) load(cache);
            } catch (Throwable e) {
                Log.err("BlockIp: could not load the VPN list", e);
            } finally {
                loading.set(false);
            }
        });
    }

    static boolean isLoading() {
        return loading.get();
    }

    static void clear() {
        ranges = IpRanges.EMPTY;
    }

    /** Every source into a temp file, which replaces the cache only once all of them arrived. */
    private static void download(List<String> urls, Fi cache) {
        Fi partial = cache.sibling(cache.name() + ".part");
        try (BufferedWriter out = new BufferedWriter(partial.writer(false, "UTF-8"))) {
            for (String url : urls) {
                URLConnection connection = new URL(url).openConnection();
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(30_000);
                connection.setRequestProperty("User-Agent", "BlockIp");
                if (connection instanceof HttpURLConnection http && http.getResponseCode() / 100 != 2) {
                    throw new IOException(url + " answered " + http.getResponseCode());
                }
                try (Reader in = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] buffer = new char[8192];
                    long total = 0;
                    for (int read; (read = in.read(buffer)) > 0; ) {
                        total += read;
                        if (total > maxSourceChars) throw new IOException(url + " sent more than " + maxSourceChars + " characters");
                        out.write(buffer, 0, read);
                    }
                }
                out.newLine();
            }
        } catch (Exception e) {
            partial.delete();
            Log.warn("BlockIp: VPN list download failed, keeping the cached copy: @", e.toString());
            return;
        }
        partial.moveTo(cache);
    }

    private static void load(Fi cache) throws IOException {
        IpRanges.Builder builder = new IpRanges.Builder();
        try (BufferedReader in = cache.reader(64 * 1024, "UTF-8")) {
            for (String line = in.readLine(); line != null; line = in.readLine()) builder.add(line);
        }
        ranges = builder.build();
        Log.info("BlockIp: VPN list holds @ ranges", ranges.size());
    }
}
