package BlockIp;

import arc.ApplicationListener;
import arc.Core;
import arc.Events;
import arc.net.Server.ServerConnectFilter;
import arc.struct.ObjectIntMap;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Strings;
import arc.util.Threads;
import arc.util.Time;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.game.EventType.ConnectPacketEvent;
import mindustry.game.EventType.PlayerChatEvent;
import mindustry.game.EventType.PlayerConnect;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.game.EventType.ServerLoadEvent;
import mindustry.game.EventType.WorldLoadEndEvent;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.mod.Plugin;
import mindustry.net.Administration;
import mindustry.net.Administration.ActionType;
import mindustry.net.NetConnection;
import mindustry.net.Packets.ConnectPacket;
import mindustry.ui.Menus;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class BlockPlugin extends Plugin {
    // Main thread only, reported and reset by the minute summary
    private int kickedCountry, kickedVpn, kickedDuplicate, challenged, challengesPassed, challengesFailed, votesRefused, chatDropped, chatHeadless;
    private Filter filter;
    private final VpnGuard vpnGuard = new VpnGuard();
    private final Challenge challenge = new Challenge(new Random());
    private int menuId = -1;
    private boolean vpnAttackLogged;

    private final Raid raid = new Raid();
    private final Chorus voteChorus = new Chorus(), chatChorus = new Chorus();
    private TrustStore trust = new TrustStore();
    private final AtomicBoolean saving = new AtomicBoolean(), saveFailed = new AtomicBoolean();
    /** Online players by uuid, from join to leave. */
    private final HashMap<String, Session> sessions = new HashMap<>();
    /** What the connect packet said, kept from PlayerConnect to the join; the oldest go first if clients pile up that never join. */
    private final LinkedHashMap<String, Arrival> arrivals = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Arrival> eldest) {
            return size() > Tuning.Plugin.MAX_ARRIVALS;
        }
    };
    /** The connect packet just let through, until vanilla's own checks finish with it in the same handler. */
    private Admitted admitted;
    /** When this server started: the players reconnecting right after are no burst. */
    private final long started = Time.millis();
    /** About when this server started keeping trust: the first record's first active minute, or now. */
    private long trustSince;
    private int minutesSinceSave;
    /** Passed a challenge since this raid started: not asked again during it. */
    private final HashSet<String> passedThisRaid = new HashSet<>();
    /** The vote in progress, vanilla's and Essentials', as last seen, and since when (Time.millis). */
    private Object vanillaVote, essentialsVote;
    private long vanillaVoteStart, essentialsVoteStart;
    /** When the gate let through the /rtv that opened Essentials' rtv (Time.millis), 0 for none; see {@link #rtvOpen}. */
    private long rtvStart;
    /** The vanilla kick vote whose counter is held (see {@link KickVote}), and how many votes it holds back. */
    private Object heldVote;
    private int heldVotes;
    private final CommandGuard.Gate challengeGate = this::challengeAllows, voteGate = this::voteAllows;

    /** {@code fresh}: a burst join of a uuid with no trust record. */
    private record Arrival(int fingerprint, boolean burst, boolean fresh) {
    }

    private record Admitted(NetConnection connection, int fingerprint, boolean lowTrust, boolean untrusted, boolean vpnChallenge) {
    }

    /** Essentials' VoteSystem adds a chat filter of its own while a vote runs; its class name tells it apart. */
    private static final String ESSENTIALS_VOTE = "essential.core.service.vote.VoteSystem";
    /**
     * The actions that count as activity: they change the world, on a tile. The others - commanding units,
     * dropping plans, taking control - a script sends empty, with no unit and nothing to command.
     */
    private static final EnumSet<ActionType> WORLD_ACTIONS = EnumSet.of(ActionType.breakBlock, ActionType.placeBlock,
            ActionType.rotate, ActionType.configure, ActionType.withdrawItem, ActionType.depositItem);
    /** The words Essentials counts as a vote (VoteSystem.kt, yesWords and noWords). */
    private static final Set<String> VOTE_WORDS = Set.of("y", "yes", "так", "+", "n", "no", "ні", "-");

    @Override
    public void init() {
        try {
            ConfigManager.load();
            Log.info("BlockIp loaded.");
        } catch (Exception e) {
            Log.err("BlockIp: Critical error loading config", e);
        }
        refreshVpnList(false);
        trust = TrustStore.load(trustPath(), Time.millis());
        trustSince = trust.firstSeen(Time.millis());
        // Saved every few minutes when it changed, and once more as the server shuts down
        Core.app.addListener(new ApplicationListener() {
            @Override
            public void dispose() {
                saveTrustNow();
            }
        });

        // ConnectionEvent fires before the client has sent its uuid, so the uuid whitelist never matched there
        Events.on(ConnectPacketEvent.class, this::handleConnection);
        Events.on(PlayerConnect.class, event -> onConnect(event.player));
        // After every plugin's init, so a filter another plugin installed there is kept and called after ours,
        // and after every plugin registered its commands, so the vote commands are there to guard
        Events.on(ServerLoadEvent.class, event -> {
            installFilter();
            guardCommands();
        });
        // Essentials registers its commands again on reload, bare. A command message fires this before the
        // command runs, so a command registered since is guarded before anyone can use it - a '.' one too,
        // which Essentials' keyboard-layout fix runs as a command.
        Events.on(PlayerChatEvent.class, event -> {
            if (event.message != null && (event.message.startsWith("/") || event.message.startsWith("."))) guardCommands();
        });
        // Essentials' protect module sets its own filter, without chaining, on every map load. A frame later,
        // after every handler of this event has run, take the slot back with theirs chained behind ours.
        // A new map ends Essentials' rtv as well
        Events.on(WorldLoadEndEvent.class, event -> {
            rtvStart = 0;
            arc.Core.app.post(this::keepFilter);
        });
        Timer.schedule(this::minuteTick, Tuning.Timers.MINUTE_TICK_SECONDS, Tuning.Timers.MINUTE_TICK_SECONDS);
        Timer.schedule(this::sample, Tuning.Timers.SAMPLE_SECONDS, Tuning.Timers.SAMPLE_SECONDS);

        // Menus.registerMenu hands out the next free id, so it cannot take one another plugin holds
        menuId = Menus.registerMenu(this::onChallengeAnswer);
        Events.on(PlayerJoin.class, event -> onJoin(event.player));
        Events.on(PlayerLeave.class, event -> onLeave(event.player));
        // A player still being challenged can neither act nor talk, but may respawn: that is how a player
        // leaves a turret or another unit that cannot walk, and walking is what they are asked to do.
        Vars.netServer.admins.addActionFilter(action -> {
            if (action.player == null || action.type == ActionType.respawn) return true;
            if (challenge.isPending(action.player.uuid())) return false;
            if (action.tile != null && WORLD_ACTIONS.contains(action.type)) {
                Session session = sessions.get(action.player.uuid());
                if (session != null) session.action(action.tile.pos());
            }
            return true;
        });
        Vars.netServer.admins.addChatFilter(this::filterChat);
    }

    /**
     * Runs on the main thread for every connect packet, cheapest check first. Checked inline: NetServer
     * stops handling a connection kicked here, so a refused client never gets further, and no thread is
     * started per connection. An address refused for what it is (VPN, country) is also blocked at accept
     * for a while, so its retries cost the main thread nothing.
     */
    private void handleConnection(ConnectPacketEvent event) {
        admitted = null;
        String ip = event.connection.address;
        String uuid = event.packet.uuid;
        if (ConfigManager.isUuidWhitelisted(uuid) || ConfigManager.isIpWhitelisted(ip)) return;
        ConfigManager.ConfigData config = ConfigManager.settings();

        // Every session counts, a stale one of this same uuid included: with the server's strict mode on,
        // vanilla refuses a uuid already in game anyway, and with it off, forgiving one let a client that
        // repeats its uuid past the cap
        if (config.maxPlayersPerIp > 0 && sameAddress(Vars.net.getConnections(), event.connection) >= config.maxPlayersPerIp) {
            event.connection.kick(Texts.local(config.duplicateKickText, event.packet.locale));
            kickedDuplicate++;
            return;
        }

        long now = Time.millis();
        // Not admins.isAdmin: it creates a saved PlayerInfo for every uuid it is asked about, bots' included,
        // and this runs before vanilla's own check that the packet has a usid at all
        Administration.PlayerInfo info = uuid == null ? null : Vars.netServer.admins.getInfoOptional(uuid);
        boolean admin = info != null && info.admin && info.adminUsid != null && info.adminUsid.equals(event.packet.usid);
        boolean untrusted = uuid != null && !admin && !trusted(uuid, config);
        boolean lowTrust = untrusted && lowTrust(uuid, config);
        boolean vpnChallenge = false;
        if (config.mode() != VpnGuard.Mode.off && VpnList.contains(ip)) {
            // Proved to be a person from this address recently: let in outside attack mode, and not counted
            // toward a burst - several regulars reconnecting after a restart are not an attack
            boolean passed = challenge.hasPassed(uuid, ip, now);
            if (!passed || vpnGuard.underAttack(now)) {
                VpnGuard.Reason why = vpnGuard.check(config, ip, now, Groups.player.size());
                if (vpnGuard.attackStarted()) onVpnAttack(config, now);
                if (why != null && why != VpnGuard.Reason.attack && config.vpnChallenge) {
                    // Let in, and asked once joined
                    vpnChallenge = true;
                } else if (why == VpnGuard.Reason.busy) {
                    // The server being full says nothing about this address: no block, it may retry once a slot frees
                    kickedVpn++;
                    event.connection.kick(Texts.local(config.vpnKickText, event.packet.locale));
                    return;
                } else if (why != null) {
                    kickedVpn++;
                    refuse(event, config, config.vpnKickText, "VPN, " + why);
                    return;
                }
            }
        }
        // For a listed address too: the VPN rules may let it in, and that says nothing about its country
        if (IpChecker.isBlocked(ip)) {
            kickedCountry++;
            refuse(event, config, config.kickText, "country");
            return;
        }

        // Vanilla checks the rest after this event; onConnect takes it from there if it got in
        admitted = new Admitted(event.connection, fingerprint(event.packet), lowTrust, untrusted, vpnChallenge);
    }

    /**
     * Other connections from {@code connection}'s address past their connect packet, in game or still loading
     * the map. Not Groups.player: a player is in it only once it confirms, so one address could load four bots
     * a second, each seeing none ahead of it. Bounded by the connections open; the list is copy-on-write, so
     * iterating it copies nothing.
     */
    static int sameAddress(Iterable<NetConnection> connections, NetConnection connection) {
        int count = 0;
        for (NetConnection con : connections) {
            if (con != connection && con.hasBegunConnecting && !con.kicked && con.isConnected()
                    && connection.address.equals(con.address)) count++;
        }
        return count;
    }

    /**
     * PlayerConnect: vanilla let the connection in - uuid, usid, bans, recent kicks, player limit, version
     * and mods all passed - and is sending it the map. Only now can it get a challenge. A bare connect packet,
     * or a banned bot retrying, is turned away by vanilla right after the connect packet event, so it costs
     * nothing to send: counted there, a few of them held raid mode open and filled the challenges waiting,
     * and none of their addresses was ever blocked. It counts toward a raid only once it joins (see onJoin).
     */
    private void onConnect(Player player) {
        Admitted a = admitted;
        admitted = null;
        // Whitelisted (nothing to check), or not the connection the packet event saw
        if (a == null || a.connection != player.con) return;
        String uuid = player.uuid();
        long now = Time.millis();
        TrustStore.Record record = trust.get(uuid);
        boolean burst = Suspicion.burstJoin(a.untrusted, record, started, now, ConfigManager.settings());
        arrivals.put(uuid, new Arrival(a.fingerprint, burst, burst && record == null));
        // During a raid every low-trust join is checked, VPN or not; a uuid that failed one before, always
        boolean raidCheck = a.lowTrust && (raid.active(now) && !passedThisRaid.contains(uuid) || trust.isFlagged(uuid));
        if (!a.vpnChallenge && !raidCheck) return;
        dropNeverJoined(now);
        if (challenge.expect(uuid, now, raid.active(now) && a.lowTrust, player.con, this::dropForRoom)) {
            challenged++;
            guardCommands();
        } else {
            // This uuid already waits on another connection. No block: that says nothing about this address,
            // and 0, not even vanilla's 30 s recent-kick hold on it, which may be a carrier NAT's
            ConfigManager.ConfigData config = ConfigManager.settings();
            player.con.kick(Texts.local(a.vpnChallenge ? config.vpnKickText : config.challengeFailText, player.locale), 0);
        }
    }

    /**
     * A join toward a burst (see {@link Suspicion#burstJoin}). At join, not at connect: a fresh uuid passes
     * vanilla's checks too, so six connect packets closed at once, every few minutes, held raid mode on for good.
     */
    private void countJoin(String address, int fingerprint, String name, boolean fresh, long now) {
        ConfigManager.ConfigData config = ConfigManager.settings();
        // A hold that ran out but whose end the minute tick has not announced yet would be extended, with no sweep
        if (raid.ended(now)) onRaidEnd();
        long window = config.raidJoinSeconds * 1000L;
        // A chorus raid that a burst now joins: votes close to all but trusted players from here on
        boolean open = raid.active(now) && !raid.votesLocked(now);
        if (raid.join(address, fingerprint, name, now, config.raidJoins, window, holdMs(config))) onRaidStart("join burst");
        if (fresh && raid.newJoin(address, now, config.raidNewJoins, window, holdMs(config))) onRaidStart("new uuid burst");
        if (open && raid.votesLocked(now)) Log.warn("BlockIp: join burst during raid mode (@): only trusted players vote now", raid.reason());
    }

    /** What a script sends the same every time and players differ in: locale, client, mods, colour, device. */
    private static int fingerprint(ConnectPacket packet) {
        String mods = packet.mods == null ? "" : String.join(",", packet.mods);
        return (packet.locale + '|' + packet.versionType + '|' + packet.version + '|' + packet.color + '|' + packet.mobile + '|' + mods).hashCode();
    }

    private static void refuse(ConnectPacketEvent event, ConfigManager.ConfigData config, String reason, String kind) {
        String ip = event.connection.address;
        event.connection.kick(Texts.local(reason, event.packet.locale));
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
                        && !ConfigManager.isUuidWhitelisted(p.uuid()) && !ConfigManager.isIpWhitelisted(p.ip())
                        && !challenge.hasPassed(p.uuid(), p.ip(), now),
                p -> {
                    p.kick(Texts.local(config.vpnKickText, p.locale));
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

    /**
     * The deadline set at join covers only clients that join. One challenged at connect that closes
     * before joining, or takes the map and never confirms, is a failure: flagged, and a VPN address blocked.
     * Checked by sweeping the challenges waiting, not by a timer per connection, which a uuid sent from
     * many addresses would pile up, each holding its closed connection's buffers.
     */
    private void dropNeverJoined(long now) {
        challenge.dropNeverJoined(now, Tuning.Challenge.JOIN_GRACE_SECONDS * 1000L, c -> ((NetConnection) c).isConnected(), this::failNeverJoined);
    }

    /**
     * The one waiting longest when too many wait, dropped to make room. No fault of its own: during a flood
     * of clients that never confirm, that is the real newcomer whose map is slowest to load. Kicked only -
     * no flag, no block, no failure counted.
     */
    private void dropForRoom(String uuid, Object c) {
        NetConnection connection = (NetConnection) c;
        // 0: not even vanilla's 30 s recent-kick hold on the address
        if (connection.isConnected()) connection.kick(Texts.local("Too many players are joining at once. Try again in a minute.", localeOf(connection)), 0);
    }

    /** The locale a client sent, from a connection that may not have its player yet. */
    private static String localeOf(NetConnection connection) {
        return connection.player == null ? null : connection.player.locale;
    }

    /** Dropped before joining: closed, or out of time. */
    private void failNeverJoined(String uuid, Object c) {
        NetConnection connection = (NetConnection) c;
        ConfigManager.ConfigData config = ConfigManager.settings();
        challengesFailed++;
        trust.flag(uuid);
        if (connection.isConnected()) connection.kick(Texts.local(config.challengeFailText, localeOf(connection)), 0);
        // Raid mode challenges residential and mobile addresses too, where one newcomer closing the game while
        // the map loads would shut out everyone behind the same carrier NAT. Only a VPN address is blocked
        if (VpnList.contains(connection.address)) FloodGuard.block(connection.address, System.currentTimeMillis() + config.blockSeconds * 1000L);
    }

    private void onJoin(Player player) {
        String uuid = player.uuid();
        challenge.joined(uuid);
        Arrival arrival = arrivals.remove(uuid);
        sessions.put(uuid, new Session(uuid, player.ip(), player.name, Time.millis(), arrival == null ? 0 : arrival.fingerprint,
                VpnList.contains(player.ip()), arrival != null && arrival.burst));
        if (challenge.waitsForMove(uuid)) askToMove(player);
        else if (challenge.isPending(uuid)) showChallenge(player, true);
        // Part of a burst, still loading the map when raid mode began: checked now, like the rest of it
        else if (arrival != null && arrival.burst && raid.active(Time.millis()) && !passedThisRaid.contains(uuid)
                && lowTrust(uuid, ConfigManager.settings())) {
            challengeInGame(player);
        }
        // Last, so a burst this completes sweeps this player too, unless asked already
        if (arrival != null && arrival.burst) countJoin(player.ip(), arrival.fingerprint, player.name, arrival.fresh, Time.millis());
    }

    /**
     * Raid mode challenges a low-trust player already in game: walk, then press. Not one farmed past the vote
     * minimum: it walked to get there, so walking proves nothing about it - raid mode keeps it out of votes
     * instead. False when this uuid still waits to join on another connection, which is challenged already.
     */
    private boolean challengeInGame(Player player) {
        String uuid = player.uuid();
        if (!challenge.expect(uuid, Time.millis(), true)) return false;
        challenged++;
        guardCommands();
        askToMove(player);
        return true;
    }

    private void onLeave(Player player) {
        String uuid = player.uuid();
        // A real player whose game crashed is flagged too: that costs them a challenge on later joins, until they pass one.
        // Not when the server shuts down, which disconnects everyone after net.dispose() has already cleared server()
        if (challenge.leave(uuid) && Vars.net.server()) failChallenge(player);
        sessions.remove(uuid);
        trust.seen(uuid, Time.millis());
    }

    /** At join (and again if the menu was closed): the menu, and on first showing the deadline. */
    private void showChallenge(Player player, boolean first) {
        String uuid = player.uuid();
        String[] options = challenge.options(uuid);
        if (options == null) return;
        ConfigManager.ConfigData config = ConfigManager.settings();
        String[][] rows = {{options[0], options[1]}, {options[2], options[3]}};
        Call.menu(player.con, menuId, Texts.local(config.challengeTitle, player.locale),
                Texts.local(config.challengeText, player.locale).replace("{word}", challenge.word(uuid)), rows);
        if (first) deadline(uuid, Math.max(config.challengeSeconds, Tuning.Challenge.MIN_SECONDS));
    }

    /**
     * Fails the challenge if it is still the one waiting when time is up. Holds the uuid, not the Player: a
     * timer holding one kept its closed connection, buffers and all, until it fired, and bots that join and
     * leave at once during raid mode piled those up. One who left failed it then, in onLeave.
     */
    private void deadline(String uuid, int seconds) {
        Object token = challenge.token(uuid);
        Timer.schedule(() -> {
            if (!challenge.expire(uuid, token)) return;
            Player player = Groups.player.find(p -> p.uuid().equals(uuid));
            if (player != null) failChallenge(player);
        }, seconds);
    }

    /**
     * A raid-mode challenge: walk first, then the buttons. A menu answer carries the word, so a script
     * written for this server answers the buttons; walking as well costs it a movement script. A person
     * reads the text, walks a step and presses, on desktop or mobile alike - the menu only appears once
     * they have moved, so it never sits in the way of moving. One deadline covers walking and pressing.
     */
    private void askToMove(Player player) {
        String uuid = player.uuid();
        ConfigManager.ConfigData config = ConfigManager.settings();
        Session session = sessions.get(uuid);
        if (session != null) session.startChallenge();
        String move = Texts.local(config.raidMoveText, player.locale);
        player.sendMessage(move);
        Call.announce(player.con, move);
        deadline(uuid, Math.max(config.raidChallengeSeconds, Tuning.Challenge.MIN_RAID_SECONDS));
    }

    /**
     * Every 2 s: where each player's unit is, for activity and for the raid challenge. A few dozen map
     * lookups and a square root each.
     */
    private void sample() {
        long now = Time.millis();
        observeVotes();
        dropNeverJoined(now);
        Groups.player.each(player -> {
            Session session = sessions.get(player.uuid());
            if (session == null || player.con == null) return;
            if (player.dead()) {
                session.sampleDead(player.con.lastReceivedClientTime, now);
                return;
            }
            Unit unit = player.unit();
            session.sample(unit.id, unit.x, unit.y, unit.speed(), player.con.lastReceivedClientTime, now);
            if (session.challengeMoved >= Tuning.Movement.CHALLENGE_DISTANCE && challenge.waitsForMove(player.uuid())) {
                showChallenge(player, false);
            }
        });
    }

    /**
     * Commands go through gates: one for a player being challenged (no command at all until they pass),
     * one more on the vote commands. Called at server load, before each command message and each minute,
     * so commands a plugin registers again later are guarded too; wrapped once each, however often.
     */
    private void guardCommands() {
        CommandHandler handler = Vars.netServer.clientCommands;
        CommandGuard.guard(handler, name -> true, challengeGate);
        CommandGuard.guard(handler, name -> ConfigManager.settings().voteCommands.contains(name), voteGate);
    }

    private boolean challengeAllows(String command, String[] args, Object caller) {
        if (!(caller instanceof Player player) || !challenge.isPending(player.uuid())) return true;
        ConfigManager.ConfigData config = ConfigManager.settings();
        String word = challenge.word(player.uuid());
        player.sendMessage(word != null ? Texts.local(config.challengeText, player.locale).replace("{word}", word)
                : Texts.local(config.raidMoveText, player.locale));
        return false;
    }

    /**
     * The vote gate. The same vote command within seconds from several players not yet trusted who already
     * look like raiders (see {@link Suspicion#chorusVoice}) is the raid itself: twelve bots, one /votekick,
     * one second. Starting a kick takes more active minutes than casting a vote; a kick of a trusted player
     * also needs a trusted player behind it (see {@link #holdKickVote}, {@link #needsTrustedStarter}), so bots
     * farmed past these minutes, voting seconds apart, still cannot kick anyone or roll the map back.
     */
    private boolean voteAllows(String command, String[] args, Object caller) {
        if (!(caller instanceof Player player)) return true;
        ConfigManager.ConfigData config = ConfigManager.settings();
        // A command taken off the list keeps its gate until it is registered again: it passes from now on
        if (!config.voteCommands.contains(command)) return true;
        long now = Time.millis();
        observeVotes();
        if (player.admin) {
            holdKickVote(command, args, player, now);
            return true;
        }
        String uuid = player.uuid();
        boolean startsKick = startsKick(command, args);
        int need = startsKick ? config.voteStartMinutes : config.voteCastMinutes;
        // By command alone: varying the target or the y/n would otherwise split one attack into several
        if (config.coordinatedCommands > 0 && !trusted(uuid, config) && chorusVoice(uuid, now, config, refusedAsRaider(uuid, now, config))) {
            long window = config.coordinatedSeconds * 1000L;
            boolean again = voteChorus.heard(command, uuid, now, window);
            Set<String> senders = voteChorus.add(command, uuid, now, window);
            // The first heard is the one the others echoed, however often they repeat it
            if (senders.size() >= config.coordinatedCommands && !uuid.equals(senders.iterator().next())) {
                markCoordinated(senders, config.coordinatedCommands, uuid, again, false);
                startRaid("coordinated votes", now, config);
            }
        }
        // Only vanilla's /vote casts into a running vote; Essentials' are cast by chat words, in filterChat
        String why = voteRefusal(player, "vote".equals(command) && vanillaVote != null ? vanillaVoteStart : 0, raid.votesLocked(now), need);
        if (why == null && !trusted(uuid, config) && needsTrustedStarter(command, args, player, now, config)) why = "needs a trusted player to start";
        if (why == null) {
            if ("rtv".equals(command) && !rtvOpen(rtvStart, now)) rtvStart = now;
            holdKickVote(command, args, player, now);
            // A vote this starts is seen right after it runs, so its start time is exact
            Core.app.post(this::observeVotes);
            return true;
        }
        votesRefused++;
        player.sendMessage(Texts.local(config.voteRefusedText, player.locale));
        return false;
    }

    /**
     * Whether this vote may start only from a trusted player: one whose count cannot be held - Essentials', which
     * counts yes votes itself (a kick, a rollback, a game over, a map), and vanilla's kick without
     * {@link KickVote#available}. Five bots farmed past the vote minutes are Essentials' majority on a server of
     * eight, so a trusted starter is what keeps them from rolling the map back. While trust is new nobody is
     * trusted, so then only a kick naming a trusted player is held to it. A player alone may still change the map
     * or skip waves ({@link #harmlessAlone}), but not roll the map back or end the game. Every /rtv is a vote, so
     * only the one that opens it needs a trusted player; once open, anyone the gate lets vote may add theirs.
     */
    private boolean needsTrustedStarter(String command, String[] args, Player caller, long now, ConfigManager.ConfigData config) {
        if (!startsUnheldVote(command, KickVote.available()) || Groups.player.size() <= 1 && harmlessAlone(command, args)) return false;
        if ("rtv".equals(command) && rtvOpen(rtvStart, now)) return false;
        return !newTrust(now, config) || startsKick(command, args) && kicksTrusted(command, args, caller);
    }

    /**
     * A vote a player alone may start without being trusted: one that moves to another map or skips waves.
     * Not a rollback, a game over, a draw or Essentials' random outcome, which can undo the work of players who
     * just left, and not a kick. Essentials matches its subcommand exactly, so this does too.
     */
    static boolean harmlessAlone(String command, String[] args) {
        return "votemap".equals(command) || "rtv".equals(command)
                || "evote".equals(command) && args.length > 0 && ("map".equals(args[0]) || "skip".equals(args[0]));
    }

    /**
     * A vote start whose count BlockPlugin cannot hold back: Essentials' every one, its /rtv included (it ends the
     * map once a share of the players have typed it), and vanilla's kick unless {@code held}.
     */
    static boolean startsUnheldVote(String command, boolean held) {
        return "evote".equals(command) || "evotekick".equals(command) || "votemap".equals(command) || "rtv".equals(command)
                || "votekick".equals(command) && !held;
    }

    /**
     * Whether the rtv the gate let open at {@code start} still runs at {@code now}.
     * shortcut: a fixed window, not Essentials' configured rtv timeout; read that timeout if an owner changes it.
     */
    static boolean rtvOpen(long start, long now) {
        return start != 0 && now - start < Tuning.Votes.RTV_OPEN_MS;
    }

    /** See {@link Suspicion#refusedAsRaider}. */
    private boolean refusedAsRaider(String uuid, long now, ConfigManager.ConfigData config) {
        Session session = sessions.get(uuid);
        return Suspicion.refusedAsRaider(session != null && session.snapshot, score(uuid, false, now, new StringBuilder()), config);
    }

    /** Starts a kick vote: vanilla's /votekick, Essentials' /evotekick and /evote kick. */
    static boolean startsKick(String command, String[] args) {
        return "votekick".equals(command) || "evotekick".equals(command)
                || "evote".equals(command) && args.length > 0 && "kick".equalsIgnoreCase(args[0]);
    }

    /**
     * Whether this kick may name a trusted player, for the votes whose count cannot be held: Essentials', which
     * counts yes votes itself, and vanilla's without {@link KickVote#available}. Those take a trusted player
     * to start. Vanilla's target is found as vanilla finds it. Essentials' lookup also takes partial names,
     * database ids and entity ids: a name matches every player whose name contains it, an id any player.
     * shortcut: an id is refused while anyone trusted is online; resolve it as Essentials does if players complain.
     */
    private boolean kicksTrusted(String command, String[] args, Player caller) {
        ConfigManager.ConfigData config = ConfigManager.settings();
        if ("votekick".equals(command)) {
            if (KickVote.available() || args.length < 2) return false;
            Player found;
            if (args[0].length() > 1 && args[0].startsWith("#") && Strings.canParseInt(args[0].substring(1))) {
                int id = Strings.parseInt(args[0].substring(1));
                found = Groups.player.find(p -> p.id() == id);
            } else {
                found = Groups.player.find(p -> p.name.equalsIgnoreCase(args[0]));
            }
            return found != null && !found.admin && trusted(found.uuid(), config);
        }
        int at = "evotekick".equals(command) ? 0 : 1;
        if (args.length <= at) return false;
        String query = Strings.stripColors(args[at]).trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) return false;
        boolean id = query.startsWith("#") || query.startsWith("@") || Strings.canParseInt(query);
        return Groups.player.find(p -> p != caller && !p.admin && trusted(p.uuid(), config)
                && (id || p.uuid().equals(args[at]) || Strings.stripColors(p.name).toLowerCase(Locale.ROOT).contains(query))) != null;
    }

    /**
     * A vanilla /vote into a kick. It passes only once the initiator or a yes voter is trusted (or an admin),
     * and for a trusted target once the yes votes reach trustedTargetShare of the players active now, three at
     * least: a cohort farmed past the vote minutes may outvote the regulars online, but has no trusted player
     * (see {@link KickVote#mayPass}).
     * Until then the counter is held back before vanilla's vote() counts (see {@link KickVote#counter}) and
     * given back once it may pass; a vote that runs out held fails, as vanilla's timer finds too few. Mirrors
     * the checks vanilla makes before vote(), so the counter is set only for a vote it will count.
     */
    private void holdKickVote(String command, String[] args, Player player, long now) {
        Object session = Vars.netServer.currentlyKicking;
        if (!"vote".equals(command) || session == null || !KickVote.available() || args.length == 0) return;
        try {
            if (session != heldVote) {
                heldVote = session;
                heldVotes = 0;
            }
            Player target = KickVote.target(session);
            ConfigManager.ConfigData config = ConfigManager.settings();
            if (target == null) return;
            int sign = switch (args[0].toLowerCase(Locale.ROOT)) {
                case "y", "yes" -> 1;
                case "n", "no" -> -1;
                default -> 0;
            };
            ObjectIntMap<String> voted = KickVote.voted(session);
            String uuid = player.uuid();
            Administration.PlayerInfo info = Vars.netServer.admins.getInfoOptional(uuid);
            String ip = info != null ? info.lastIP : player.ip();
            if (sign == 0 || target == player || target.team() != player.team() || voted.get(uuid, 2) == sign || voted.get(ip, 2) == sign) return;
            int yes = sign == 1 ? 1 : 0;
            boolean trustedYes = sign == 1 && (player.admin || trusted(uuid, config));
            // Its keys are uuids and addresses: only the uuids of players online count, each once
            for (ObjectIntMap.Entry<String> e : voted.entries()) {
                if (e.value != 1 || e.key.equals(uuid) || !sessions.containsKey(e.key)) continue;
                yes++;
                Administration.PlayerInfo voter = Vars.netServer.admins.getInfoOptional(e.key);
                trustedYes |= voter != null && voter.admin || trusted(e.key, config);
            }
            int active = 0;
            for (Session s : sessions.values()) {
                if (!s.uuid.equals(target.uuid()) && !s.afk(now)) active++;
            }
            int real = KickVote.votes(session) + heldVotes;
            int counter = KickVote.counter(real, voted.get(uuid, 0) | voted.get(ip, 0), sign, KickVote.mayPass(yes, trustedYes,
                    trusted(target.uuid(), config), newTrust(now, config), active, config.trustedTargetShare));
            KickVote.setVotes(session, counter);
            heldVotes = real - counter;
        } catch (Throwable e) {
            Log.err("BlockIp: could not hold a kick vote", e);
        }
    }

    /** {@code trustedOnly}: raid mode. {@code needMinutes}: the active minutes this vote takes. */
    private String voteRefusal(Player player, long voteStarted, boolean trustedOnly, int needMinutes) {
        ConfigManager.ConfigData config = ConfigManager.settings();
        String uuid = player.uuid();
        long now = Time.millis();
        // A whitelisted uuid is trusted on the operator's word, for votes as an admin is
        boolean exempt = player.admin || ConfigManager.isUuidWhitelisted(uuid);
        Session session = sessions.get(uuid);
        return Suspicion.voteRefusal(exempt, challenge.isPending(uuid), session != null && session.snapshot, trustedOnly,
                trusted(uuid, config), trust.minutes(uuid), needMinutes, score(uuid, exempt, now, new StringBuilder()),
                player.con == null ? 0 : player.con.connectTime, voteStarted, config);
    }

    /** See {@link Suspicion#chorusVoice}. */
    private boolean chorusVoice(String uuid, long now, ConfigManager.ConfigData config, boolean refused) {
        Session session = sessions.get(uuid);
        return Suspicion.chorusVoice(session, session != null && Suspicion.inCohort(session, sessions.values(), config),
                trust.isFlagged(uuid), trust.minutes(uuid), newTrust(now, config), refused);
    }

    /**
     * When the vote in progress started, so a player who came in after it cannot vote in it: bots join
     * once a vote is running. Vanilla's is a public field; Essentials' is told by the chat filter it adds
     * while a vote runs. Missing either just means no vote is seen - the other rules still apply.
     */
    private void observeVotes() {
        long now = Time.millis();
        Object kicking = Vars.netServer.currentlyKicking;
        if (kicking != vanillaVote) {
            vanillaVote = kicking;
            vanillaVoteStart = now;
        }
        Object essentials = null;
        // By index: this runs inside filterMessage's own iteration of the same Seq, whose iterators are shared
        Seq<Administration.ChatFilter> filters = Vars.netServer.admins.chatFilters;
        for (int i = 0; i < filters.size; i++) {
            if (filters.get(i).getClass().getName().startsWith(ESSENTIALS_VOTE)) essentials = filters.get(i);
        }
        if (essentials != essentialsVote) {
            essentialsVote = essentials;
            essentialsVoteStart = now;
        }
    }

    /**
     * Chat. A player being challenged says nothing. While an Essentials vote runs, a vote word from a player
     * the vote gate would refuse is dropped here, before Essentials' own filter (added later, when the vote
     * started) can count it. Then floods: the same line from several low-trust players within seconds is
     * dropped and marks them; from more, it is raid mode. Neither the first who said it nor players past
     * the vote minimum are ever dropped for a flood - regulars' "gg" at game over is the common case, and
     * the vote gate is what keeps a farmed cohort in check - and a short line ("gg", "da", "+") needs twice the senders.
     */
    private String filterChat(Player player, String text) {
        String uuid = player.uuid();
        if (challenge.isPending(uuid)) return null;
        if (player.admin) return text;
        ConfigManager.ConfigData config = ConfigManager.settings();
        long now = Time.millis();
        // Dropped silently, like a flood. Commands do not come through here; the vote gate asks for a snapshot itself
        if (Suspicion.headless(sessions.get(uuid), now) && !ConfigManager.isUuidWhitelisted(uuid)) {
            chatHeadless++;
            return null;
        }
        if (isVoteWord(text)) {
            observeVotes();
            if (essentialsVote != null && voteRefusal(player, essentialsVoteStart, raid.votesLocked(now), config.voteCastMinutes) != null) {
                votesRefused++;
                player.sendMessage(Texts.local(config.voteRefusedText, player.locale));
                return null;
            }
        }
        // Every low-trust sender, not only one that already looks like a raid's: a vote the gate refuses does no
        // harm, a line is posted however few minutes its sender has, and fifteen bots that walked one minute each
        // and joined a few at a time posted theirs unchecked. While trust is new, only no active minute counts
        // Nor one who plays (see Suspicion#playing): five newcomers saying "good game" at map end are not a flood,
        // and walking is what the incident's bots could not do
        // playing() before chorusVoice(): both pure, and the first is a field read where the second scans everyone online
        if (config.chatFloodSenders <= 0 || !lowTrust(uuid, config) || Suspicion.playing(sessions.get(uuid))
                || !chorusVoice(uuid, now, config, true)) return text;
        String key = Chorus.chatKey(text);
        if (key.isEmpty()) return text;
        int times = key.length() <= Tuning.Chat.SHORT_LINE_MAX_LENGTH ? Tuning.Chat.SHORT_LINE_SENDER_FACTOR : 1;
        long window = config.chatFloodSeconds * 1000L;
        boolean again = chatChorus.heard(key, uuid, now, window);
        Set<String> senders = chatChorus.add(key, uuid, now, window);
        if (senders.size() < config.chatFloodSenders * times || uuid.equals(senders.iterator().next())) return text;
        markCoordinated(senders, config.chatFloodSenders * times, uuid, again, true);
        if (config.chatFloodRaidSenders > 0 && senders.size() >= config.chatFloodRaidSenders * times) startRaid("chat flood", now, config);
        chatDropped++;
        return null;
    }

    /**
     * Trimmed as Essentials' Kotlin trim() does, which strips every Unicode space (U+00A0, U+2000 to U+200A, U+3000) where
     * Java's trim() stops at U+0020: a vote word it counts must be one this sees, or it passes the gate.
     */
    static boolean isVoteWord(String text) {
        int start = 0, end = text.length();
        while (start < end && kotlinSpace(text.charAt(start))) start++;
        while (end > start && kotlinSpace(text.charAt(end - 1))) end--;
        return VOTE_WORDS.contains(text.substring(start, end).toLowerCase(Locale.ROOT));
    }

    /** Kotlin's Char.isWhitespace on the JVM. */
    private static boolean kotlinSpace(char ch) {
        return Character.isWhitespace(ch) || Character.isSpaceChar(ch);
    }

    /**
     * A chorus starts raid mode; it never extends it (see {@link Raid#start}). A hold that ran out but whose
     * end the minute tick has not announced yet is ended first, so this is a real start, which sweeps the
     * players already in game.
     */
    private void startRaid(String why, long now, ConfigManager.ConfigData config) {
        if (raid.ended(now)) onRaidEnd();
        if (raid.start(why, now, holdMs(config))) onRaidStart(why);
    }

    /**
     * {@code sender}, not the first heard, brought {@code senders} to {@code threshold} or past it. At the
     * threshold everyone who said it so far counts as coordinated but the first heard: bots echoing a
     * newcomer's line or vote must not make a raider of the one they echoed, however often the newcomer
     * says it again. Past it, each new one. One saying it {@code again} was counted already. For {@code chat},
     * never one who plays (see {@link Suspicion#playing}): bots seeding "hello" make a newcomer saying it too
     * one of the chorus, and the dropped line is enough there.
     */
    private void markCoordinated(Set<String> senders, int threshold, String sender, boolean again, boolean chat) {
        if (again) return;
        if (senders.size() > threshold) {
            if (!chat || !Suspicion.playing(sessions.get(sender))) coordinated(sender);
            return;
        }
        boolean first = true;
        for (String uuid : senders) {
            if (!first && (!chat || !Suspicion.playing(sessions.get(uuid)))) coordinated(uuid);
            first = false;
        }
    }

    /** During raid mode a low-trust one marked now is challenged where they stand, as they would have been at its start. */
    private void coordinated(String uuid) {
        Session session = sessions.get(uuid);
        if (session == null) return;
        session.coordinated++;
        if (!raid.active(Time.millis()) || challenge.isPending(uuid) || passedThisRaid.contains(uuid)
                || !lowTrust(uuid, ConfigManager.settings())) return;
        Player player = Groups.player.find(p -> p.uuid().equals(uuid));
        if (player != null) challengeInGame(player);
    }

    /**
     * Raid mode is on. Everyone who came in with the burst or took part in the coordinated vote or chat,
     * is low trust and has not passed this raid, is challenged where they stand: until they walk and
     * press, they can neither act, chat nor run a command. For a burst start every burst join within its window
     * counts, however few of them are online still.
     */
    private void onRaidStart(String reason) {
        ConfigManager.ConfigData config = ConfigManager.settings();
        passedThisRaid.clear();
        long now = Time.millis();
        boolean burst = "join burst".equals(reason) || "new uuid burst".equals(reason);
        int[] asked = {0};
        Groups.player.each(player -> {
            String uuid = player.uuid();
            Session session = sessions.get(uuid);
            if (session == null || player.admin || challenge.isPending(uuid) || !lowTrust(uuid, config)) return;
            if (session.coordinated == 0 && !(burst && Suspicion.cameWithBurst(session, now, config))
                    && !Suspicion.inCohort(session, sessions.values(), config)) return;
            if (challengeInGame(player)) asked[0]++;
        });
        Log.warn("BlockIp: raid mode on (@) @: low-trust joins are challenged and @; challenged @ in game",
                reason, "manual".equals(reason) ? "until blockipraid off" : "for " + config.raidHoldMinutes + " min",
                raid.votesLocked(now) ? "only trusted players vote" : "votes follow the normal rules", asked[0]);
        // For ServerAdminer, which alerts Discord, with no compile dependency either way
        Events.fire("BlockIp:raid:start:" + reason);
    }

    private void onRaidEnd() {
        passedThisRaid.clear();
        Log.info("BlockIp: raid mode off");
        Events.fire("BlockIp:raid:end");
    }

    private static long holdMs(ConfigManager.ConfigData config) {
        return config.raidHoldMinutes * 60_000L;
    }

    /**
     * Under the vote minimum, or flagged: a player raid mode challenges, and whose chat counts toward a flood.
     * Joins and vote commands count from anyone not trusted. Admins are checked by the caller.
     */
    private boolean lowTrust(String uuid, ConfigManager.ConfigData config) {
        return !ConfigManager.isUuidWhitelisted(uuid) && (trust.isFlagged(uuid) || trust.minutes(uuid) < config.voteMinActiveMinutes);
    }

    /**
     * Trust kept for under {@code trustedDays} days: a first install, or a trust file lost, when every regular is
     * under the vote minimum and looks like a newcomer. Choruses then count only uuids with no active minute at
     * all, which every regular earns in their first minute and the bots of a raid do not - regulars agreeing on
     * a vote or thanking each other at once would otherwise start raid mode. Votes still need the minutes.
     */
    private boolean newTrust(long now, ConfigManager.ConfigData config) {
        return now - trustSince < config.trustedDays * 86_400_000L;
    }

    private boolean trusted(String uuid, ConfigManager.ConfigData config) {
        return ConfigManager.isUuidWhitelisted(uuid) || trust.isTrusted(uuid, config.trustedMinutes, config.trustedDays);
    }

    private int score(String uuid, boolean admin, long now, StringBuilder why) {
        return Suspicion.score(sessions.get(uuid), trust.get(uuid), trust.isFlagged(uuid), admin, sessions.values(), now,
                ConfigManager.settings(), why);
    }

    private void onChallengeAnswer(Player player, int option) {
        if (player == null) return;
        ConfigManager.ConfigData config = ConfigManager.settings();
        switch (challenge.answer(player.uuid(), player.ip(), option, Time.millis(), config.challengePassHours * 3_600_000L)) {
            case pass -> {
                challengesPassed++;
                // A flag is for a uuid that failed; one that passes is checked like anyone from now on
                trust.unflag(player.uuid());
                Session session = sessions.get(player.uuid());
                if (session != null) session.passedChallenge = true;
                if (raid.active(Time.millis()) && passedThisRaid.size() < Tuning.Plugin.MAX_PASSED_THIS_RAID) passedThisRaid.add(player.uuid());
            }
            case fail -> failChallenge(player);
            case reshow -> showChallenge(player, false);
            case none -> {
            }
        }
    }

    /**
     * A kick, a block at accept and a flag on the uuid - not an engine ban: a raider has thousands of
     * addresses, and each ban is two more lines in the Discord feed. The flag brings a challenge on every
     * later join of that uuid and keeps it out of votes; a pass, or blockipunflag, lifts it.
     */
    private void failChallenge(Player player) {
        ConfigManager.ConfigData config = ConfigManager.settings();
        challengesFailed++;
        trust.flag(player.uuid());
        // As in failNeverJoined, only a VPN address is blocked (whether still connected or not: one that left
        // before its deadline fails here too); a carrier NAT is not shut out for one newcomer, the flag suffices
        boolean vpn = VpnList.contains(player.ip());
        if (vpn) FloodGuard.block(player.ip(), System.currentTimeMillis() + config.blockSeconds * 1000L);
        // 0 for the rest: not even vanilla's 30 s recent-kick hold on the shared address
        if (player.con != null && player.con.isConnected()) player.con.kick(Texts.local(config.challengeFailText, player.locale), vpn ? Tuning.Challenge.VPN_FAIL_KICK_MS : 0L);
    }

    /**
     * A plugin that sets its filter later, instead of chaining, drops ours; take the slot back with it
     * chained. (One that wraps ours would get ours twice - BotEradicator did; it is what this replaces.)
     */
    private void keepFilter() {
        if (filter != null && Vars.net.getConnectFilter() != filter) installFilter();
    }

    private void minuteTick() {
        keepFilter();
        guardCommands();
        long now = Time.millis();

        if (vpnAttackLogged && !vpnGuard.underAttack(now)) {
            vpnAttackLogged = false;
            Log.info("BlockIp: no VPN burst for @ min, VPN connections follow the normal rules again", ConfigManager.settings().vpnBurstHoldMinutes);
        }
        if (raid.ended(now)) onRaidEnd();

        for (Session session : sessions.values()) {
            if (session.takeMinute(now)) trust.active(session.uuid, now);
        }
        // Not each minute: the store is copied on this thread and written whole, to change a few dozen rows
        if (++minutesSinceSave >= Tuning.Timers.SAVE_MINUTES) {
            minutesSinceSave = 0;
            saveTrust();
        }

        // A challenge for someone who never finished joining is dropped after a few minutes
        challenge.sweep(now, Tuning.Timers.STALE_CHALLENGE_MS);

        int refused = FloodGuard.refused.getAndSet(0);
        if (refused + kickedCountry + kickedVpn + kickedDuplicate + challenged > 0) {
            Log.info("BlockIp: last minute refused @ connections at accept, kicked @ by country, @ as VPN, @ as duplicates; challenged @ players, @ passed, @ failed",
                    refused, kickedCountry, kickedVpn, kickedDuplicate, challenged, challengesPassed, challengesFailed);
        }
        if (votesRefused + chatDropped + chatHeadless > 0) {
            Log.info("BlockIp: last minute refused @ votes, dropped @ flooded chat lines and @ from clients with no snapshot",
                    votesRefused, chatDropped, chatHeadless);
        }
        kickedCountry = kickedVpn = kickedDuplicate = challenged = challengesPassed = challengesFailed = votesRefused = chatDropped = chatHeadless = 0;
    }

    private static Path trustPath() {
        return ConfigManager.TRUST_FILE.file().toPath();
    }

    /** Copied here, written on a daemon thread: the main thread never waits on the disk. */
    private void saveTrust() {
        if (saveFailed.getAndSet(false)) trust.markDirty();
        if (saving.get()) return;
        var snapshot = trust.snapshot();
        if (snapshot == null) return;
        saving.set(true);
        Threads.daemon("BlockIp-trust", () -> {
            try {
                TrustStore.write(snapshot, trustPath());
            } catch (Throwable e) {
                saveFailed.set(true);
                Log.err("BlockIp: could not save the trust file", e);
            } finally {
                saving.set(false);
            }
        });
    }

    /**
     * At exit, on the calling thread, always: the minute's snapshot may still be on its way to disk on the
     * daemon thread, which exit stops. A save still running there finishes first.
     */
    private void saveTrustNow() {
        trust.markDirty();
        var snapshot = trust.snapshot();
        if (snapshot == null) return;
        try {
            TrustStore.write(snapshot, trustPath());
        } catch (Throwable e) {
            Log.err("BlockIp: could not save the trust file", e);
        }
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
                guardCommands();
                Log.info("Configuration reloaded.");
            } catch (Exception e) {
                Log.err("Error reloading config", e);
            }
        });

        handler.register("blockipstats", "Show what BlockIp is holding", args -> {
            long now = Time.millis();
            Log.info("BlockIp: VPN mode @@ (@ ranges), GeoIP database @, @ addresses blocked at accept, connect filter @",
                    ConfigManager.settings().mode(), vpnGuard.underAttack(now) ? ", VPN burst in progress" : "", VpnList.size(),
                    ConfigManager.getDbReader() != null ? "loaded" : "missing", FloodGuard.blockedCount(),
                    filter != null && Vars.net.getConnectFilter() == filter ? "installed" : "not installed");
            Log.info("BlockIp: raid mode @, @ trust records (@ flagged), @ challenges waiting, @ players tracked",
                    raid.active(now) ? "on (" + raid.reason() + ", " + (raid.votesLocked(now) ? "trusted-only votes" : "votes open")
                            + ", " + (raid.until() == Long.MAX_VALUE ? "until blockipraid off"
                            : (raid.until() - now) / 60_000 + 1 + " min left") + ")" : "off",
                    trust.size(), trust.flaggedCount(), challenge.pendingCount(), sessions.size());
        });

        handler.register("blockipinfo", "<name|uuid>", "Show a player's trust and suspicion score", args -> {
            Player player = Groups.player.find(p -> p.uuid().equals(args[0]) || Strings.stripColors(p.name).equalsIgnoreCase(args[0]));
            // Some clients put an icon glyph before the name, so a name typed in the console is also looked for inside it
            String part = args[0].toLowerCase(Locale.ROOT);
            if (player == null) player = Groups.player.find(p -> Strings.stripColors(p.name).toLowerCase(Locale.ROOT).contains(part));
            String uuid = player != null ? player.uuid() : args[0];
            TrustStore.Record record = trust.get(uuid);
            if (player == null && record == null && !trust.isFlagged(uuid)) {
                Log.err("No online player or trust record for @.", args[0]);
                return;
            }
            ConfigManager.ConfigData config = ConfigManager.settings();
            long now = Time.millis();
            StringBuilder why = new StringBuilder();
            int score = score(uuid, player != null && player.admin, now, why);
            Log.info("BlockIp: @ (@): @ active min on @ days, first seen @, last seen @@, @",
                    player != null ? Strings.stripColors(player.name) : "offline", uuid,
                    record == null ? 0 : record.minutes, record == null ? 0 : record.days,
                    record == null ? "-" : Time.timeSinceMillis(record.firstSeen) / 60_000 + " min ago",
                    record == null ? "-" : Time.timeSinceMillis(record.lastSeen) / 60_000 + " min ago",
                    trust.isFlagged(uuid) ? ", flagged" : "",
                    trusted(uuid, config) ? "trusted" : lowTrust(uuid, config) ? "low trust" : "known");
            Log.info("BlockIp: suspicion @ (@)@", score, why, player == null ? "" : ", vote: "
                    + (voteRefusal(player, 0, raid.votesLocked(now), config.voteCastMinutes) == null ? "allowed"
                    : "refused, " + voteRefusal(player, 0, raid.votesLocked(now), config.voteCastMinutes)));
        });

        handler.register("blockipunflag", "<uuid>", "Lift the flag a failed challenge put on a uuid", args -> {
            if (trust.unflag(args[0])) Log.info("Flag lifted.");
            else Log.err("That uuid is not flagged.");
        });

        handler.register("blockipraid", "<on|off>", "Turn raid mode on until turned off, or off now", args -> {
            long now = Time.millis();
            if (args[0].equalsIgnoreCase("on")) {
                if (raid.ended(now)) onRaidEnd();
                if (raid.startManually(now)) onRaidStart("manual");
                else Log.info("Raid mode was on already; it now stays on until blockipraid off.");
            } else if (args[0].equalsIgnoreCase("off")) {
                if (raid.stop()) onRaidEnd();
                else Log.info("Raid mode is not on.");
            } else {
                Log.err("Use blockipraid on or blockipraid off.");
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
