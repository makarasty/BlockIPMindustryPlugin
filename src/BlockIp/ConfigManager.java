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
    static final Fi TRUST_FILE = CONFIG_DIR.child("trust.txt");

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

        /**
         * Hosting and VPN networks: "off"; "auto" refuses them only while at least vpnMinPlayers are
         * online or a burst of VPN joins says an attack is on, so a lone VPN player can join a quiet
         * server; "always" refuses them outright.
         */
        public String vpnMode = "auto";
        public int vpnMinPlayers = 10;
        /** This many VPN join attempts within vpnBurstSeconds turn VPN refusal on for vpnBurstHoldMinutes; 0 turns bursts off. */
        public int vpnBurstJoins = 8;
        public int vpnBurstSeconds = 60;
        public int vpnBurstHoldMinutes = 10;
        /**
         * Instead of refusing a VPN player (busy server, or "always"), let them in and ask them to press
         * the button with a given word: a person passes in a second, a join bot does not. Attack mode
         * still refuses outright - letting a flood in would send each bot the map.
         */
        public boolean vpnChallenge = true;
        public int challengeSeconds = 30;
        /** How long a passed player is not asked again. */
        public int challengePassHours = 24;
        public String challengeTitle = "Quick check";
        public String challengeText = "Press the button that says [accent]{word}[] to play.";
        public String challengeFailText = "Wrong button, or no answer in time.";
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

        /**
         * Votes. The vanilla votekick passes on three yes votes, and a raider brought seventeen bots that had
         * idled long enough to beat a playtime gate. So starting a kick vote needs voteStartMinutes active
         * minutes (moving or building, which idling does not earn), casting any vote or starting another
         * voteCastMinutes, and both a suspicion score below the max; during raid mode only trusted players
         * vote. A kick passes only once a trusted player votes for it, and of a trusted player once the yes votes
         * reach trustedTargetShare of the active players (three at least); Essentials' votes take a trusted starter. voteMinActiveMinutes is who counts
         * as low trust: challenged in raid mode, watched for chat floods. Admins always may vote. These
         * commands are gated, whichever of them is registered.
         */
        public ArrayList<String> voteCommands = new ArrayList<>(List.of("votekick", "vote", "evotekick", "evote", "rtv", "votemap"));
        public int voteStartMinutes = 20;
        public int voteCastMinutes = 10;
        public int voteMinActiveMinutes = 30;
        public int voteMaxSuspicion = 50;
        public double trustedTargetShare = 0.4;
        public String voteRefusedText = "[scarlet]You cannot vote yet:[] play a while on this server first.";
        /**
         * Trusted: this many active minutes over at least this many days, a day apart. Trusted players are never
         * challenged by raid mode, vote during it, and are hard to kick.
         */
        public int trustedMinutes = 120;
        public int trustedDays = 2;
        /** The same vote command from this many players not yet trusted within this many seconds is an attack: raid mode. */
        public int coordinatedCommands = 3;
        public int coordinatedSeconds = 3;

        /**
         * Raid mode: this many joins not yet trusted from different addresses within raidJoinSeconds (0 turns joins
         * off as a trigger), a coordinated vote or a chat flood start it for raidHoldMinutes, extended while
         * triggers go on. While on, every low-trust join, and the low-trust players who came in with the
         * burst, must walk a few steps and then press the button within raidChallengeSeconds.
         */
        public int raidJoins = 6;
        public int raidJoinSeconds = 30;
        /**
         * Joins of uuids this server has no record of, from this many addresses within raidJoinSeconds, start raid
         * mode with no check that they look scripted (0 turns this off). Reconnects after a restart never count.
         */
        public int raidNewJoins = 12;
        public int raidHoldMinutes = 10;
        public int raidChallengeSeconds = 90;
        public String raidMoveText = "[scarlet]Anti-bot check:[] walk a few steps with your unit, and a button will appear. Press the one it asks for.";

        /** The same chat line from this many low-trust players within chatFloodSeconds is dropped; from chatFloodRaidSenders, raid mode. Short lines need twice as many. */
        public int chatFloodSenders = 3;
        public int chatFloodRaidSenders = 5;
        public int chatFloodSeconds = 10;

        /**
         * A key written as null reads back as null, and a null kick text reaches NetConnection.kick,
         * which throws on it inside the connect handler. Nulls take the defaults instead.
         */
        VpnGuard.Mode mode() {
            return VpnGuard.Mode.parse(vpnMode);
        }

        void repairNulls() {
            ConfigData defaults = new ConfigData();
            if (blockedCountries == null) blockedCountries = defaults.blockedCountries;
            if (ipWhiteList == null) ipWhiteList = defaults.ipWhiteList;
            if (uuidWhiteList == null) uuidWhiteList = defaults.uuidWhiteList;
            if (vpnLists == null) vpnLists = defaults.vpnLists;
            if (kickText == null) kickText = defaults.kickText;
            if (vpnKickText == null) vpnKickText = defaults.vpnKickText;
            if (duplicateKickText == null) duplicateKickText = defaults.duplicateKickText;
            if (vpnMode == null) vpnMode = defaults.vpnMode;
            if (challengeTitle == null) challengeTitle = defaults.challengeTitle;
            if (challengeText == null) challengeText = defaults.challengeText;
            if (challengeFailText == null) challengeFailText = defaults.challengeFailText;
            if (voteCommands == null) voteCommands = defaults.voteCommands;
            if (voteRefusedText == null) voteRefusedText = defaults.voteRefusedText;
            if (raidMoveText == null) raidMoveText = defaults.raidMoveText;
            voteCommands.removeIf(name -> name == null);
            blockedCountries.remove(null);
            ipWhiteList.remove(null);
            uuidWhiteList.remove(null);
            vpnLists.removeIf(url -> url == null);
        }
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
                loaded.repairNulls();
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
