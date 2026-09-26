package BlockIp;

import arc.Events;
import arc.util.CommandHandler;
import arc.util.Log;
import mindustry.game.EventType.ConnectPacketEvent;
import mindustry.mod.Plugin;

public class BlockPlugin extends Plugin {

    @Override
    public void init() {
        try {
            ConfigManager.load();
            Log.info("BlockIp loaded.");
        } catch (Exception e) {
            Log.err("BlockIp: Critical error loading config", e);
        }

        // ConnectionEvent fires before the client has sent its uuid, so the uuid whitelist never matched there
        Events.on(ConnectPacketEvent.class, this::handleConnection);
    }

    private void handleConnection(ConnectPacketEvent event) {
        String ip = event.connection.address;
        if (ConfigManager.isUuidWhitelisted(event.packet.uuid) || ConfigManager.isIpWhitelisted(ip)) return;

        // Checked inline: NetServer stops handling a connection kicked here, so a blocked
        // client never gets further, and no thread is started per connection
        if (IpChecker.isBlocked(ip)) {
            event.connection.kick(ConfigManager.getKickText());
            Log.info("Kicked connection @ (IP blocked)", ip);
        }
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        handler.register("blockipreload", "Reload BlockIp plugin config", args -> {
            try {
                ConfigManager.load();
                Log.info("Configuration reloaded.");
            } catch (Exception e) {
                Log.err("Error reloading config", e);
            }
        });

        handler.register("addcountry", "<country_code>", "Block a country", args -> {
            if (ConfigManager.addBlockedCountry(args[0])) Log.info("Country added.");
            else Log.err("Could not add country (invalid code or already exists).");
        });

        handler.register("removecountry", "<country_code>", "Unblock a country", args -> {
            if (ConfigManager.removeBlockedCountry(args[0])) Log.info("Country removed.");
            else Log.err("Country not found.");
        });

        handler.register("addwhitelist", "<ip>", "Whitelist an IP", args -> {
            if (ConfigManager.addIpWhitelist(args[0])) Log.info("IP whitelisted.");
            else Log.err("Could not add IP.");
        });

        handler.register("removewhitelist", "<ip>", "Remove IP from whitelist", args -> {
            if (ConfigManager.removeIpWhitelist(args[0])) Log.info("IP removed from whitelist.");
            else Log.err("IP not found in whitelist.");
        });

        handler.register("adduuid", "<uuid>", "Whitelist a player UUID", args -> {
            if (ConfigManager.addUuidWhitelist(args[0])) Log.info("UUID whitelisted.");
            else Log.err("Could not add UUID.");
        });

        handler.register("removeuuid", "<uuid>", "Remove UUID from whitelist", args -> {
            if (ConfigManager.removeUuidWhitelist(args[0])) Log.info("UUID removed from whitelist.");
            else Log.err("UUID not found in whitelist.");
        });
    }
}