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
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ConfigManager {
    private static final String MOD_NAME = "blockip";
    private static final Fi CONFIG_DIR = Core.settings.getDataDirectory().child("mods").child(MOD_NAME);
    private static final Fi CONFIG_FILE = CONFIG_DIR.child("config.json");
    private static final Fi DB_FILE = CONFIG_DIR.child("ip.mmdb");

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private static ConfigData data = new ConfigData();
    private static Reader dbReader;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConfigData {
        public Set<String> blockedCountries = new HashSet<>();
        public Set<String> ipWhiteList = new HashSet<>();
        public Set<String> uuidWhiteList = new HashSet<>();
        public String kickText = "Your country is blocked on this server.";
    }

    public static void load() {
        lock.writeLock().lock();
        try {
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
                    saveNoLock();
                }
            } else {
                saveNoLock();
            }

            if (dbReader != null) {
                try {
                    dbReader.close();
                } catch (IOException ignored) {
                }
            }

            File database = DB_FILE.file();
            if (database.exists()) {
                dbReader = new Reader(database, new com.maxmind.db.CHMCache());
            } else {
                Log.warn("MaxMind DB file not found at: " + database.getAbsolutePath());
                dbReader = null;
            }

            IpChecker.clearCache();

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static void saveNoLock() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(CONFIG_FILE.file(), data);
        } catch (IOException e) {
            Log.err("Failed to save config", e);
        }
    }

    public static void save() {
        lock.writeLock().lock();
        try {
            saveNoLock();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static Reader getDbReader() {
        return dbReader;
    }

    public static ReentrantReadWriteLock getLock() {
        return lock;
    }

    public static boolean isIpWhitelisted(String ip) {
        lock.readLock().lock();
        try {
            return data.ipWhiteList.contains(ip);
        } finally {
            lock.readLock().unlock();
        }
    }

    public static boolean isUuidWhitelisted(String uuid) {
        if (uuid == null) return false;
        lock.readLock().lock();
        try {
            return data.uuidWhiteList.contains(uuid);
        } finally {
            lock.readLock().unlock();
        }
    }

    public static boolean isCountryBlocked(String code) {
        if (code == null) return false;
        lock.readLock().lock();
        try {
            return data.blockedCountries.contains(code.toUpperCase());
        } finally {
            lock.readLock().unlock();
        }
    }

    public static String getKickText() {
        lock.readLock().lock();
        try {
            return data.kickText;
        } finally {
            lock.readLock().unlock();
        }
    }

    public static boolean addBlockedCountry(String code) {
        if (code == null || code.length() != 2) return false;
        lock.writeLock().lock();
        try {
            boolean added = data.blockedCountries.add(code.toUpperCase());
            if (added) saveNoLock();
            return added;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static boolean removeBlockedCountry(String code) {
        lock.writeLock().lock();
        try {
            boolean removed = data.blockedCountries.remove(code.toUpperCase());
            if (removed) saveNoLock();
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static boolean addIpWhitelist(String ip) {
        if (!isValidIpPattern(ip)) return false;
        lock.writeLock().lock();
        try {
            boolean added = data.ipWhiteList.add(ip);
            if (added) saveNoLock();
            return added;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static boolean removeIpWhitelist(String ip) {
        lock.writeLock().lock();
        try {
            boolean removed = data.ipWhiteList.remove(ip);
            if (removed) saveNoLock();
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static boolean addUuidWhitelist(String uuid) {
        if (uuid == null || uuid.isEmpty()) return false;
        lock.writeLock().lock();
        try {
            boolean added = data.uuidWhiteList.add(uuid);
            if (added) saveNoLock();
            return added;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static boolean removeUuidWhitelist(String uuid) {
        lock.writeLock().lock();
        try {
            boolean removed = data.uuidWhiteList.remove(uuid);
            if (removed) saveNoLock();
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static boolean isValidIpPattern(String ip) {
        return ip != null && ip.matches("^([0-9]{1,3}\\.){3}[0-9]{1,3}$");
    }
}