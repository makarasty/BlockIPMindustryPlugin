package BlockIp;

import arc.Events;
import arc.net.Server.ServerConnectFilter;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Time;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.game.EventType.ConnectPacketEvent;
import mindustry.game.EventType.ServerLoadEvent;
import mindustry.gen.Groups;
import mindustry.mod.Plugin;

public class BlockPlugin extends Plugin {
    // Main thread only, reported and reset by the minute summary
    private int kickedCountry, kickedVpn, kickedDuplicate;
    private Filter filter;
    private final VpnGuard vpnGuard = new VpnGuard();
    private boolean vpnAttackLogged;

    @Override
    public void init() {
        try {
            ConfigManager.load();
            Log.info("BlockIp loaded.");
        } catch (Exception e) {
            Log.err("BlockIp: Critical error loading config", e);
        }
        refreshVpnList(false);

        // ConnectionEvent fires before the client has sent its uuid, so the uuid whitelist never matched there
        Events.on(ConnectPacketEvent.class, this::handleConnection);
        // After every plugin's init, so a filter another plugin installed there is kept and called after ours
        Events.on(ServerLoadEvent.class, event -> installFilter());
        Timer.schedule(this::minuteTick, 60f, 60f);
    }

    /**
     * Runs on the main thread for every connect packet, cheapest check first. Checked inline: NetServer
     * stops handling a connection kicked here, so a refused client never gets further, and no thread is
     * started per connection. An address refused for what it is (VPN, country) is also blocked at accept
     * for a while, so its retries cost the main thread nothing.
     */
    private void handleConnection(ConnectPacketEvent event) {
        String ip = event.connection.address;
        String uuid = event.packet.uuid;
        if (ConfigManager.isUuidWhitelisted(uuid) || ConfigManager.isIpWhitelisted(ip)) return;
        ConfigManager.ConfigData config = ConfigManager.settings();

        // Every session counts, a stale one of this same uuid included: with the server's strict mode on,
        // vanilla refuses a uuid already in game anyway, and with it off, forgiving one let a client that
        // repeats its uuid past the cap
        if (config.maxPlayersPerIp > 0 && Groups.player.count(p -> ip.equals(p.ip())) >= config.maxPlayersPerIp) {
            event.connection.kick(config.duplicateKickText);
            kickedDuplicate++;
            return;
        }

        if (config.mode() != VpnGuard.Mode.off && VpnList.contains(ip)) {
            long now = Time.millis();
            VpnGuard.Reason why = vpnGuard.check(config, ip, now, Groups.player.size());
            if (vpnGuard.attackStarted()) onVpnAttack(config, now);
            if (why == VpnGuard.Reason.busy) {
                // The server being full says nothing about this address: no block, it may retry once a slot frees
                kickedVpn++;
                event.connection.kick(config.vpnKickText);
            } else if (why != null) {
                kickedVpn++;
                refuse(event, config, config.vpnKickText, "VPN, " + why);
            }
            return;
        }

        if (IpChecker.isBlocked(ip)) {
            kickedCountry++;
            refuse(event, config, config.kickText, "country");
        }
    }

    private static void refuse(ConnectPacketEvent event, ConfigManager.ConfigData config, String reason, String kind) {
        String ip = event.connection.address;
        event.connection.kick(reason);
        FloodGuard.block(ip, System.currentTimeMillis() + config.blockSeconds * 1000L);
        Log.info("BlockIp: kicked @ (@), blocked for @s", ip, kind, config.blockSeconds);
    }

    /**
     * A burst of VPN joins turned refusal on. The joins that made up the burst got in before it
     * tripped, and are most likely the first of the bots: those still online are kicked too.
     */
    private void onVpnAttack(ConfigManager.ConfigData config, long now) {
        long since = now - config.vpnBurstSeconds * 1000L;
        int[] kicked = {0};
        Groups.player.each(p -> p.con != null && p.con.connectTime >= since && VpnList.contains(p.ip())
                        && !ConfigManager.isUuidWhitelisted(p.uuid()) && !ConfigManager.isIpWhitelisted(p.ip()),
                p -> {
                    p.kick(config.vpnKickText);
                    kicked[0]++;
                });
        vpnAttackLogged = true;
        Log.warn("BlockIp: @ VPN joins within @s, refusing VPN connections for @ min; kicked @ who had just joined",
                config.vpnBurstJoins, config.vpnBurstSeconds, config.vpnBurstHoldMinutes, kicked[0]);
    }

    /**
     * The connect filter: flood limits and blocks, by address, on arc's network thread. It keeps the
     * filter it found and asks it second, so another plugin's filter (Essentials' protect module sets
     * one) still applies.
     */
    private static final class Filter implements ServerConnectFilter {
        final ServerConnectFilter previous;

        Filter(ServerConnectFilter previous) {
            this.previous = previous;
        }

        @Override
        public boolean accept(String address) {
            try {
                if (!ConfigManager.isIpWhitelisted(address)) {
                    ConfigManager.ConfigData config = ConfigManager.settings();
                    if (!FloodGuard.accept(address, System.currentTimeMillis(), config.maxConnectionsPerIp,
                            config.connectionWindowMs, config.blockSeconds * 1000L)) return false;
                }
            } catch (Throwable e) {
                // Arc's accept loop catches only IOException: anything else escaping here would stop the
                // network thread, and with it the server. A broken check lets the connection through.
                Log.err("BlockIp: connect filter failed, letting @ through", address, e);
            }
            return previous == null || previous.accept(address);
        }
    }

    private void installFilter() {
        filter = new Filter(Vars.net.getConnectFilter());
        Vars.net.setConnectFilter(filter);
    }

    private void minuteTick() {
        // A plugin that sets its filter later, instead of chaining, drops ours; take the slot back with it
        // chained. (One that wraps ours would get ours twice - BotEradicator did; it is what this replaces.)
        if (filter != null && Vars.net.getConnectFilter() != filter) {
            Log.warn("BlockIp: another plugin replaced the connect filter; chaining it behind ours again");
            installFilter();
        }

        if (vpnAttackLogged && !vpnGuard.underAttack(Time.millis())) {
            vpnAttackLogged = false;
            Log.info("BlockIp: no VPN burst for @ min, VPN connections follow the normal rules again", ConfigManager.settings().vpnBurstHoldMinutes);
        }

        int refused = FloodGuard.refused.getAndSet(0);
        if (refused + kickedCountry + kickedVpn + kickedDuplicate > 0) {
            Log.info("BlockIp: last minute refused @ connections at accept, kicked @ by country, @ as VPN, @ as duplicates",
                    refused, kickedCountry, kickedVpn, kickedDuplicate);
        }
        kickedCountry = kickedVpn = kickedDuplicate = 0;
    }

    private void refreshVpnList(boolean force) {
        ConfigManager.ConfigData config = ConfigManager.settings();
        if (config.mode() != VpnGuard.Mode.off) {
            VpnList.refresh(config.vpnLists, ConfigManager.VPN_CACHE, config.vpnListRefreshHours * 3_600_000L, force);
        } else {
            VpnList.clear();
        }
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        handler.register("blockipreload", "Reload BlockIp config, GeoIP database and VPN list", args -> {
            try {
                ConfigManager.load();
                refreshVpnList(true);
                Log.info("Configuration reloaded.");
            } catch (Exception e) {
                Log.err("Error reloading config", e);
            }
        });

        handler.register("blockipstats", "Show what BlockIp is holding", args -> Log.info(
                "BlockIp: VPN mode @@ (@ ranges), GeoIP database @, @ addresses blocked at accept, connect filter @",
                ConfigManager.settings().mode(), vpnGuard.underAttack(Time.millis()) ? ", VPN burst in progress" : "", VpnList.size(),
                ConfigManager.getDbReader() != null ? "loaded" : "missing", FloodGuard.blockedCount(),
                filter != null && Vars.net.getConnectFilter() == filter ? "installed" : "not installed"));

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
