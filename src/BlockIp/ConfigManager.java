package BlockIp;

import arc.Core;
import arc.files.Fi;
import arc.util.Log;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maxmind.db.Reader;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * No locking: the connect handler, init and the console commands all run on the main thread
 * (ServerControl posts each console line there).
 */
public class ConfigManager {
    private static final String MOD_NAME = "blockip";
    private static final Fi CONFIG_DIR = Core.settings.getDataDirectory().child("mods").child(MOD_NAME);
    private static final Fi CONFIG_FILE = CONFIG_DIR.child("config.json");
    private static final Fi DB_FILE = CONFIG_DIR.child("ip.mmdb");

    private static final ObjectMapper mapper = new ObjectMapper();

    private static ConfigData data = new ConfigData();
    private static Reader dbReader;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConfigData {
        public Set<String> blockedCountries = new HashSet<>();
        public Set<String> ipWhiteList = new HashSet<>();
        public Set<String> uuidWhiteList = new HashSet<>();
        public String kickText = "Your country is blocked on this server.";
    }

    public static void load() throws IOException {
        if (!CONFIG_DIR.exists()) CONFIG_DIR.mkdirs();

        if (CONFIG_FILE.exists()) {
            try {
                data = mapper.readValue(CONFIG_FILE.file(), ConfigData.class);
                if (data.ipWhiteList == null) data.ipWhiteList = new HashSet<>();
                if (data.uuidWhiteList == null) data.uuidWhiteList = new HashSet<>();
                if (data.blockedCountries == null) data.blockedCountries = new HashSet<>();
            } catch (IOException e) {
                Log.err("Failed to parse config, creating backup and resetting", e);
                CONFIG_FILE.moveTo(CONFIG_DIR.child("config_backup_" + System.currentTimeMillis() + ".json"));
                data = new ConfigData();
                save();
            }
        } else {
            save();
        }

        if (dbReader != null) {
            try {
                dbReader.close();
            } catch (IOException ignored) {
            }
            dbReader = null;
        }

        File database = DB_FILE.file();
        if (database.exists()) {
            dbReader = new Reader(database, new com.maxmind.db.CHMCache());
        } else {
            Log.warn("MaxMind DB file not found at: " + database.getAbsolutePath());
        }
    }

    private static void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(CONFIG_FILE.file(), data);
        } catch (IOException e) {
            Log.err("Failed to save config", e);
        }
    }

    public static Reader getDbReader() {
        return dbReader;
    }

    public static boolean isIpWhitelisted(String ip) {
        return data.ipWhiteList.contains(ip);
    }

    public static boolean isUuidWhitelisted(String uuid) {
        return uuid != null && data.uuidWhiteList.contains(uuid);
    }

    /** {@code code} comes from the GeoIP database, which already writes ISO codes in upper case. */
    public static boolean isCountryBlocked(String code) {
        return code != null && data.blockedCountries.contains(code);
    }

    public static String getKickText() {
        return data.kickText;
    }

    public static boolean addBlockedCountry(String code) {
        if (code == null || code.length() != 2) return false;
        return saveIf(data.blockedCountries.add(code.toUpperCase()));
    }

    public static boolean removeBlockedCountry(String code) {
        return saveIf(data.blockedCountries.remove(code.toUpperCase()));
    }

    public static boolean addIpWhitelist(String ip) {
        return isValidIpPattern(ip) && saveIf(data.ipWhiteList.add(ip));
    }

    public static boolean removeIpWhitelist(String ip) {
        return saveIf(data.ipWhiteList.remove(ip));
    }

    public static boolean addUuidWhitelist(String uuid) {
        if (uuid == null || uuid.isEmpty()) return false;
        return saveIf(data.uuidWhiteList.add(uuid));
    }

    public static boolean removeUuidWhitelist(String uuid) {
        return saveIf(data.uuidWhiteList.remove(uuid));
    }

    private static boolean saveIf(boolean changed) {
        if (changed) save();
        return changed;
    }

    private static boolean isValidIpPattern(String ip) {
        return ip != null && ip.matches("^([0-9]{1,3}\\.){3}[0-9]{1,3}$");
    }
}
