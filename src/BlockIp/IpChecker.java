package BlockIp;

import arc.util.Log;
import com.maxmind.db.Reader;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class IpChecker {
    private static final ConcurrentHashMap<String, Boolean> resultCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, InetAddress> addressCache = new ConcurrentHashMap<>();

    private static final long CACHE_TTL = TimeUnit.HOURS.toMillis(1);
    private static long lastCacheClear = System.currentTimeMillis();

    public static boolean checkIp(String ip) {
        if (ip == null) return false;

        if (ConfigManager.isIpWhitelisted(ip)) return false;

        Boolean cachedBlock = resultCache.get(ip);
        if (cachedBlock != null) return cachedBlock;

        if (System.currentTimeMillis() - lastCacheClear > CACHE_TTL) {
            clearCache();
        }

        boolean shouldBlock = resolveBlockStatus(ip);

        resultCache.put(ip, shouldBlock);
        return shouldBlock;
    }

    private static boolean resolveBlockStatus(String ip) {
        ConfigManager.getLock().readLock().lock();
        Reader reader = ConfigManager.getDbReader();

        try {
            if (reader == null) return false;

            InetAddress addr = addressCache.get(ip);
            if (addr == null) {
                try {
                    addr = InetAddress.getByName(ip);
                    addressCache.put(ip, addr);
                } catch (Exception e) {
                    return false;
                }
            }

            @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) reader.get(addr, Map.class);

            if (result == null) return false;

            @SuppressWarnings("unchecked") Map<String, Object> country = (Map<String, Object>) result.get("country");
            if (country != null) {
                String isoCode = (String) country.get("iso_code");
                return ConfigManager.isCountryBlocked(isoCode);
            }

        } catch (IOException e) {
            Log.err("Error reading GeoIP DB", e);
        } catch (Exception e) {
            Log.err("Unexpected error in IpChecker", e);
        } finally {
            ConfigManager.getLock().readLock().unlock();
        }

        return false;
    }

    public static void clearCache() {
        resultCache.clear();
        addressCache.clear();
        lastCacheClear = System.currentTimeMillis();
    }
}