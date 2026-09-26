package BlockIp;

import arc.Core;
import arc.files.Fi;
import arc.util.Log;
import arc.util.serialization.Json;
import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;
import arc.util.serialization.JsonWriter.OutputType;
import arc.util.serialization.SerializationException;
import com.maxmind.db.Reader;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything but the connect filter runs on the main thread (init, the connect handler, and the
 * console commands, which ServerControl posts there), so the config needs no locking. The filter
 * runs on arc's network thread and reads only what is published for it: the settings object, which
 * a reload replaces in one volatile write, and a copy of the IP whitelist.
 */
public class ConfigManager {
    private static final String MOD_NAME = "blockip";
    private static final Fi CONFIG_DIR = Core.settings.getDataDirectory().child("mods").child(MOD_NAME);
    private static final Fi CONFIG_FILE = CONFIG_DIR.child("config.json");
    private static final Fi DB_FILE = CONFIG_DIR.child("ip.mmdb");
    static final Fi VPN_CACHE = CONFIG_DIR.child("vpn-ipv4.txt");

    // Arc's own Json instead of a bundled Jackson: nothing extra on the classpath or in metaspace
    private static final Json json = new Json(OutputType.json);

    static {
        json.setIgnoreUnknownFields(true);
        json.setUsePrototypes(false); // write every key, even at its default, so the file shows what can be set
    }

    private static volatile ConfigData data = new ConfigData();
    private static volatile Set<String> ipWhitelistView = Set.of();
    private static Reader dbReader;

    // Concrete collection types: Arc's Json fills an interface-typed collection field with an ArrayList
    public static class ConfigData {
        public HashSet<String> blockedCountries = new HashSet<>();
        public HashSet<String> ipWhiteList = new HashSet<>();
        public HashSet<String> uuidWhiteList = new HashSet<>();
        public String kickText = "Your country is blocked on this server.";

        /** Refuse hosting and VPN networks. Off by default: it also turns away players on a VPN. */
        public boolean blockVpn = false;
        public ArrayList<String> vpnLists = new ArrayList<>(List.of(
                "https://raw.githubusercontent.com/X4BNet/lists_vpn/main/output/datacenter/ipv4.txt",
                "https://raw.githubusercontent.com/X4BNet/lists_vpn/main/output/vpn/ipv4.txt"));
        public int vpnListRefreshHours = 24;
        public String vpnKickText = "VPN and hosting connections are not allowed on this server.";

        /** Players one address may have on the server at once; 0 for no limit. */
        public int maxPlayersPerIp = 2;
        public String duplicateKickText = "Too many players are connected from your address.";

        /** Connections one address may open per window before it is blocked; 0 turns this off. */
        public int maxConnectionsPerIp = 4;
        public int connectionWindowMs = 1000;
        /** How long an address that broke a rule is refused outright, before it can cost the server anything. */
        public int blockSeconds = 60;
    }

    static ConfigData settings() {
        return data;
    }

    public static void load() throws IOException {
        if (!CONFIG_DIR.exists()) CONFIG_DIR.mkdirs();

        if (CONFIG_FILE.exists()) {
            try {
                JsonValue root = new JsonReader().parse(CONFIG_FILE);
                ConfigData loaded = json.readValue(ConfigData.class, root);
                if (loaded.ipWhiteList == null) loaded.ipWhiteList = new HashSet<>();
                if (loaded.uuidWhiteList == null) loaded.uuidWhiteList = new HashSet<>();
                if (loaded.blockedCountries == null) loaded.blockedCountries = new HashSet<>();
                if (loaded.vpnLists == null) loaded.vpnLists = new ArrayList<>();
                publish(loaded);
                // A file from an older build lacks the newer keys; write them in so the file shows them
                if (missesAKey(root)) save();
            } catch (SerializationException e) {
                Log.err("Failed to parse config, creating backup and resetting", e);
                CONFIG_FILE.moveTo(CONFIG_DIR.child("config_backup_" + System.currentTimeMillis() + ".json"));
                publish(new ConfigData());
                save();
            }
        } else {
            publish(new ConfigData());
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

    private static boolean missesAKey(JsonValue root) {
        for (Field field : ConfigData.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !root.has(field.getName())) return true;
        }
        return false;
    }

    private static void publish(ConfigData loaded) {
        data = loaded;
        ipWhitelistView = Set.copyOf(loaded.ipWhiteList);
    }

    private static void save() {
        try {
            CONFIG_FILE.writeString(json.prettyPrint(data));
        } catch (Exception e) {
            Log.err("Failed to save config", e);
        }
    }

    public static Reader getDbReader() {
        return dbReader;
    }

    /** Safe from any thread. */
    public static boolean isIpWhitelisted(String ip) {
        return ip != null && ipWhitelistView.contains(ip);
    }

    public static boolean isUuidWhitelisted(String uuid) {
        return uuid != null && data.uuidWhiteList.contains(uuid);
    }

    /** Normalised like addBlockedCountry, so a database with lower-case codes cannot slip past. */
    public static boolean isCountryBlocked(String code) {
        // toUpperCase returns the same instance when nothing changes, so MaxMind's codes cost no allocation
        return code != null && data.blockedCountries.contains(code.toUpperCase());
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
        if (changed) {
            ipWhitelistView = Set.copyOf(data.ipWhiteList);
            save();
        }
        return changed;
    }

    private static boolean isValidIpPattern(String ip) {
        return ip != null && ip.matches("^([0-9]{1,3}\\.){3}[0-9]{1,3}$");
    }
}
