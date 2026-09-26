package BlockIp;

import arc.util.Log;
import com.maxmind.db.Reader;

import java.net.InetAddress;
import java.util.Map;

public class IpChecker {

    /**
     * Runs on the main thread. The database is memory-mapped and the Reader caches decoded records,
     * so a lookup costs microseconds and needs no cache or worker thread of its own.
     */
    @SuppressWarnings("unchecked")
    public static boolean isBlocked(String ip) {
        Reader reader = ConfigManager.getDbReader();
        // "steam:" addresses are not IP literals, and getByName would resolve them over DNS on the main thread
        if (reader == null || ip == null || ip.startsWith("steam:")) return false;

        try {
            Map<String, Object> result = reader.get(InetAddress.getByName(ip), Map.class);
            if (result == null) return false;

            Map<String, Object> country = (Map<String, Object>) result.get("country");
            return country != null && ConfigManager.isCountryBlocked((String) country.get("iso_code"));
        } catch (Exception e) {
            Log.err("BlockIp: GeoIP lookup failed for " + ip, e);
            return false;
        }
    }
}
