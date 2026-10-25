# BlockIp Plugin for Mindustry

A lightweight plugin for Mindustry servers that keeps bots and unwanted networks out: connection flood limits, a per-address player cap, hosting/VPN network blocking and GeoIP country blocking, with IP and UUID whitelists - and, against raids from residential proxies no list knows, per-server trust from real activity, a vote gate and a raid mode.

It replaces BotEradicator; remove `BotEradicator.jar` when installing this, and keep Essentials' `rules.vpn: false` (in `config/mods/Essentials/config/config_protect.yaml`) - BlockIP owns VPN decisions, and Essentials would kick the VPN players BlockIP lets in or challenges. Two checks, both cheap:

*   **At accept**, on the network thread, before the server allocates anything for the connection: an address that opens connections too fast, or was refused recently, is closed straight away. A flood never reaches the main thread.
*   **At the connect packet**, on the main thread, once per join attempt: the player cap, the VPN list and the country. A binary search over ~31,000 merged ranges (about 240 KB) and a memory-mapped GeoIP lookup; no worker threads, no locks.

## 🚀 Key Features

*   **Flood Limits:** more than `maxConnectionsPerIp` connections in `connectionWindowMs` blocks the address for `blockSeconds`, refused at accept.
*   **Player Cap per Address:** at most `maxPlayersPerIp` players from one address at a time, clients still loading the map included.
*   **Hosting / VPN Blocking, only when it is needed:** the [X4BNet](https://github.com/X4BNet/lists_vpn) datacenter and VPN lists, downloaded once a day into `config/mods/blockip/vpn-ipv4.txt` and read from there on restart (a failed download keeps the last good copy). In the default `auto` mode a VPN player can join a quiet server; VPN joins are refused only while `vpnMinPlayers` are online, or for `vpnBurstHoldMinutes` after `vpnBurstJoins` VPN joins arrive within `vpnBurstSeconds` - and the VPN players who joined during that burst are kicked when it trips. `always` refuses every VPN address, `off` never looks. IPv4 only: the published lists have no IPv6.
*   **A Button Instead of a Refusal** (`vpnChallenge`, on by default): a VPN player who would be refused for a busy server or `always` is let in and shown a menu - press the button with the given word within `challengeSeconds`. A person passes in a second and is not asked again from the same address for `challengePassHours`; a wrong button, no answer or leaving before answering is a kick and a block, and so is a client that takes the map and never finishes joining, or closes before it does (a player whose game crashed mid-challenge is flagged too, until they pass one). A uuid gets one such challenge at a time: the same uuid connecting again from elsewhere before the first has joined is turned away. With 200 clients challenged and still loading, each new one drops the one waiting longest - a kick only, no flag and no block, since during such a flood that is most often a real newcomer on a slow map load - so clients that never join cannot lock newcomers out; players challenged in game take no place. This stops generic join bots, which do not answer menus; it is not proof against a bot written for this server, since a Mindustry menu has to send the word along with the buttons - which is why a pass counts for nothing during attack mode. Until then the player can neither build, chat nor run commands. Attack mode still refuses outright: letting a flood in would send every bot the map. A uuid that fails is flagged (see below) and challenged on every later join until it passes.
*   **Trust from Real Activity:** each server keeps, per uuid, the minutes its player was really *active* - the unit got five tiles from where the minute found it by the player's own snapshots (the farthest it got, not the path: stepping back and forth goes nowhere), or the player built, broke, rotated, configured or moved items on a block while their client sent snapshots that minute and the unit moved at least a tile or they acted on at least 20 different tiles (a builder standing still, building at range) - and on how many days, in `config/mods/blockip/trust.txt`. Idling earns nothing, which is what beats bots with stable uuids that idle to pass a playtime gate; nor do the free packets a script can send empty (commanding units, dropping plans, taking control). Respawns, teleports, impossible speeds and units shoved about while their client sends no snapshots count nothing. *Low trust* is under `voteMinActiveMinutes` or flagged; *trusted* is `trustedMinutes` over `trustedDays` days, each new day counted only a full day after the first active minute (one minute before midnight and the rest after it are one day, not two). A record is created only by an active minute, so a flood of new uuids costs no memory; records under 30 minutes are kept apart and, past 100,000 records, the least recently seen of them goes first, so farming fresh uuids pushes out only other fresh uuids, never a veteran. Records on probation not seen for 30 days are dropped at load (a flag is kept). The file is saved every 10 minutes it changed (written on a background thread, to a temp file renamed over the old one) and at shutdown; one that does not read back is kept aside as `trust.txt.bad-<time>` and the server starts with no trust. **On a first install the store is empty:** a regular casts votes after `voteCastMinutes` active minutes and starts a kick after `voteStartMinutes`, both within their first session; for `trustedDays` days nobody is trusted, so during a raid mode started by a scripted join burst only admins and whitelisted uuids vote. For those first `trustedDays` days coordinated votes and chat floods count only uuids with no active minute at all: every regular is under the vote minimum then, but has a record after their first active minute, and a raid's fresh bots do not. Join bursts count as always. Joins in the first 15 minutes after the server starts never count toward a burst, so the reconnect wave after a restart does not start raid mode.
*   **A Vote Gate:** vanilla's `/votekick` passes on three yes votes, and a raid brought seventeen bots. Every command in `voteCommands` (vanilla `votekick` and `vote`, Essentials' `evotekick`, `evote`, `rtv`, `votemap`, whichever is registered) is gated for players: an admin always may (`/vote c` too), and so may a whitelisted uuid; anyone else needs no pending challenge, a client that has sent snapshots this session (a headless bot never does), a suspicion score under `voteMaxSuspicion`, and during a raid mode started by a scripted join burst or by hand to be trusted. Starting a kick (`votekick`, `evotekick`, `evote kick`) takes `voteStartMinutes` active minutes; casting a vote (`vote`, Essentials' chat words) or any other vote command takes `voteCastMinutes` (none for a player alone on the server who only changes the map or skips waves: `votemap`, `rtv`, `evote map|skip`, at most once per 5 minutes server-wide, since each change is a world load and a wipe of the map's history), and a cast also needs the player to have connected before the vote began. A vanilla kick passes only once the initiator or a yes voter is trusted (or an admin); a kick of a **trusted** player also needs the yes votes to reach `trustedTargetShare` of the players active in the last 5 minutes, three at least: bots farmed past the vote minutes may outnumber the regulars, but are not trusted. The counter is held back (it shows at most 1) until that holds, then given back; one that runs out held fails. So with nobody trusted online, only an admin's vote kicks. Essentials counts yes votes itself, so every Essentials vote (`evote kick|map|gg|skip|back|random|draw`, `evotekick`, `votemap`) takes a trusted player to start it unless the starter is alone on the server and it only changes the map or skips waves (`votemap`, `evote map|skip`) - alone, a rollback, game over, draw or random still takes a trusted player; so does vanilla's kick, should a server build lack the vote session's fields. `/rtv` is not held to this: every `/rtv` is a vote cast, so it takes `voteCastMinutes` like any other (but for the free pass of a player alone, above), and during a raid that locks votes only trusted players may use it. For the first `trustedDays` days of trust, when nobody can be trusted yet, both rules apply only to a kick naming a trusted (whitelisted) player (Essentials' name matches every player whose name contains it, an id anyone online). Essentials counts chat words (`y`, `+`, ...) as votes; while one of its votes runs, those words from a player the gate would refuse are dropped before Essentials sees them. An Essentials build with one electorate asks the gate itself (the public static `BlockPlugin.voteRefusal(uuid, starts, untrusted)`, `null` = may vote) for every vote it runs, `/rtv` included: a player refused there neither votes nor counts toward what the vote needs, so a raid that locks votes to trusted players leaves the trusted ones able to pass a kick of the raiders, and newcomers short of minutes no longer hold up `/rtv`. It applies the same rule as the gate (raid lock, `voteStartMinutes` to start a kick, `voteCastMinutes` otherwise, none for the gate's solo free pass, challenge, snapshot, suspicion); a session Essentials holds back - a privileged uuid on a device it is not bound to - is lent no trust, whitelist or admin exemption. Without BlockIP Essentials uses its own rules alone; with an older Essentials the chat-word filter above still applies. The same vote command within `coordinatedSeconds` from `coordinatedCommands` players not yet trusted who already look like raiders - joined in a burst still online, took part in a chorus before, flagged, or refused by the gate for no client snapshot or a suspicion score too high - starts raid mode, except from its first sender. Players refused only for being short of active minutes, and regulars the gate lets vote, voting a griefer out or calling `/rtv` together, are not counted. Commands are guarded in place, once each, at server load, before every command message and every minute, so Essentials' re-registration on reload is caught before the command can run.
*   **Suspicion Score (0-100):** from signals each too weak to act on alone - little active time, joined in a burst (one where at least half share its client or a look-alike name), look-alike names and identical clients (locale, version, mods, colour, device) within that burst, no snapshot or no movement since joining, a short digit name, a VPN/hosting address, taking part in a coordinated vote or chat flood, a flag - lowered by active time, several days, a passed challenge; admins are 0. It never kicks anyone: it only keeps a player out of votes. `blockipinfo` shows it with its parts.
*   **Raid Mode, for Everyone:** `raidJoins` joins of players not yet trusted from different addresses within `raidJoinSeconds` (counted once they join, so bare connect packets, fresh uuids closed before loading the map and banned bots retrying count nothing; uuids past `voteMinActiveMinutes` active in the last 15 minutes, back after a restart, do not count; a burst counts only when at least half of it shares one client fingerprint or look-alike names, so regulars moving over from a crashed sibling server do not), `raidNewJoins` joins within `raidJoinSeconds` of uuids this server has no record of, from different addresses, however varied (reconnects after a restart never count), a coordinated vote, a chat flood or `blockipraid on` turn it on for `raidHoldMinutes`. A join burst while it is on extends it; a coordinated vote or chat flood only starts it, since bots that passed this raid's challenge are not asked again and could otherwise hold it on for good. While on, every low-trust join, and the low-trust players already in game who came with the burst or took part (or take part while it is on), must walk a few steps (three tiles from where the challenge found them) - the buttons appear only then, so a script cannot answer before moving - and press the right one within `raidChallengeSeconds`. A raid started or extended by a join burst that looks scripted, or by hand, also lets only trusted players vote; one started by a coordinated vote, a chat flood or a burst of new uuids alone leaves votes to the usual rules (`voteCastMinutes`, `voteStartMinutes`), so regulars not yet trusted can still vote a griefer out - a join burst during it closes them. `blockipstats` says which. A player challenged while controlling a turret may respawn to get a unit that walks. A failure is a kick, a block at accept and a flag, not an engine ban (only a VPN address is blocked, whether the challenge failed in game or a client never finished loading the map: a residential or mobile one may be a carrier NAT shared with other players, and the flag already brings that uuid a challenge on every later join): a raider has thousands of addresses, and each ban is more lines in the Discord feed.
*   **Chat Floods:** the same line (colours, case, spaces, punctuation, zero-width and icon glyphs ignored) from `chatFloodSenders` low-trust players within `chatFloodSeconds` is dropped and marks them - all but the first, so bots echoing a newcomer do not mark the newcomer, even when the newcomer says it again; from `chatFloodRaidSenders`, raid mode. Every low-trust player who does not play counts - one whose client sends no snapshots or whose unit has not walked yet: bots that walked a minute each and joined a few at a time are low trust and nothing else, while five newcomers who walked about saying "good game" at map end are no flood. For the first `trustedDays` days only players who already look like raiders or have no active minute count. Lines of up to three characters ("gg", "da", "+") need twice as many. Players past the vote minimum are never dropped. Apart from floods, a chat line (team chat included, commands not) from a client that has sent no snapshot more than 5 seconds after joining - a headless bot, no game behind it - is dropped silently; the vote gate already refuses such a client's votes.
*   **GeoIP Blocking:** Automatically kick players connecting from specific countries using the MaxMind GeoLite2 database - a VPN-listed address too, whatever the VPN rules let in.
*   **Refused Means Refused for a While:** an address kicked for its VPN or country is also blocked at accept for `blockSeconds`, so its retries cost nothing.
*   **Dual Whitelisting:**
    *   **IP Whitelist:** Allow specific IP addresses to bypass every check.
    *   **UUID Whitelist:** Allow specific player UUIDs to bypass all checks (ideal for players with dynamic IPs).
*   **Cheap Checks:**
    *   **Memory-Mapped IO:** the database is read through the OS page cache, not loaded onto the heap; the reader keeps a small cache of decoded records.
    *   **No DNS Lookups:** addresses are parsed as IP literals, so a lookup never blocks on the network.
    *   **Kicked Before Joining:** a blocked client is kicked while its connect packet is handled, before the server creates a player.

## 🔨 Building from Source

To compile the plugin yourself, you need a Java Development Kit (JDK) installed (JDK  17 is recommended).

1.  Open a terminal in the project directory.
2.  Run the build command:
    *   **Windows:**
        ```bash
        gradlew jar
        ```
    *   **Linux / macOS:**
        ```bash
        ./gradlew jar
        ```
3.  The compiled plugin will be located in:
    `build/libs/`

> **⚠️ Important for Developers:**
> This plugin relies on the **MaxMind** (`maxmind-db`) library. The `jar` task in `build.gradle` copies it into the final JAR; keep it that way, or the plugin crashes with `NoClassDefFoundError` at runtime.

## 📥 Installation

1.  **Download/Build the Plugin:**
    Place `BlockIPMindustryPlugin.jar` into your server's `config/mods` folder.

2.  **Download the GeoIP Database:**
    *   This plugin requires a MaxMind `.mmdb` database.
    *   Sign up for free at [MaxMind](https://dev.maxmind.com/geoip/geolite2-free-geolocation-data) and download the **GeoLite2-Country** database.

3.  **Initial Run:**
    Start the server once. The plugin will generate the necessary folders but will log a warning that the database is missing.

4.  **Setup Database:**
    *   Navigate to: `config/mods/blockip/`
    *   Rename your downloaded database file to `ip.mmdb`.
    *   Place it inside the folder so the path is: `config/mods/blockip/ip.mmdb`.

5.  **Reload:**
    Type `blockipreload` in the server console to load the database without restarting.

## ⚙️ Configuration

The configuration file is located at `config/mods/blockip/config.json`.

### Default Configuration
When generated, the file looks like this:

```json
{
"blockedCountries": [],
"ipWhiteList": [],
"uuidWhiteList": [],
"kickText": "Your country is blocked on this server.",
"vpnMode": "auto",
"vpnMinPlayers": 10,
"vpnBurstJoins": 8,
"vpnBurstSeconds": 60,
"vpnBurstHoldMinutes": 10,
"vpnChallenge": true,
"challengeSeconds": 30,
"challengePassHours": 24,
"challengeTitle": "Quick check",
"challengeText": "Press the button that says [accent]{word}[] to play.",
"challengeFailText": "Wrong button, or no answer in time.",
"vpnLists": [
	"https://raw.githubusercontent.com/X4BNet/lists_vpn/main/output/datacenter/ipv4.txt",
	"https://raw.githubusercontent.com/X4BNet/lists_vpn/main/output/vpn/ipv4.txt"
],
"vpnListRefreshHours": 24,
"vpnKickText": "VPN and hosting connections are not allowed on this server.",
"maxPlayersPerIp": 2,
"duplicateKickText": "Too many players are connected from your address.",
"maxConnectionsPerIp": 4,
"connectionWindowMs": 1000,
"blockSeconds": 60,
"voteCommands": ["votekick", "vote", "evotekick", "evote", "rtv", "votemap"],
"voteStartMinutes": 20,
"voteCastMinutes": 10,
"voteMinActiveMinutes": 30,
"voteMaxSuspicion": 50,
"trustedTargetShare": 0.4,
"voteRefusedText": "[scarlet]You cannot vote yet:[] play a while on this server first.",
"trustedMinutes": 120,
"trustedDays": 2,
"coordinatedCommands": 3,
"coordinatedSeconds": 3,
"raidJoins": 6,
"raidJoinSeconds": 30,
"raidNewJoins": 12,
"raidHoldMinutes": 10,
"raidChallengeSeconds": 90,
"raidMoveText": "[scarlet]Anti-bot check:[] walk a few steps with your unit, and a button will appear. Press the one it asks for.",
"chatFloodSenders": 3,
"chatFloodRaidSenders": 5,
"chatFloodSeconds": 10
}
```

A file written by an older version gets the new keys added, with these defaults, on the next load.

Every text a player reads (`*Text`, `challengeTitle`) is shown in Ukrainian or Russian to a client whose
language is one of those, and in English to everyone else. Only the default English texts are translated: a
text changed here is shown as written, in every language.

### Configuration Fields

| Field | Type | Description                                                                          |
| :--- | :--- |:-------------------------------------------------------------------------------------|
| `blockedCountries` | Array | A list of 2-letter ISO-3166 country codes to block (e.g., `["CN", "RU"]`).           |
| `ipWhiteList` | Array | IPs that skip every check, flood limits included (e.g., a shared NAT address). |
| `uuidWhiteList` | Array | Mindustry Client UUIDs that skip the join checks (country, VPN, player cap), raid mode and the vote gate. |
| `kickText` | String | The message shown to a player from a blocked country. |
| `vpnMode` | String | `off`, `auto` or `always`, see Hosting / VPN Blocking above. A value it does not know means `auto`. |
| `vpnMinPlayers` | Number | In `auto`, VPN joins are refused while at least this many players are online; `0` turns this rule off. |
| `vpnBurstJoins` | Number | In `auto`, VPN joins from this many different addresses within `vpnBurstSeconds` start refusing VPN joins; `0` turns bursts off. Attempts refused by attack mode itself do not count, so retrying cannot hold it open. |
| `vpnBurstSeconds` | Number | The window a burst is counted in. |
| `vpnChallenge` | Boolean | Challenge a VPN player with a button instead of refusing them (not during attack mode). |
| `challengeSeconds` | Number | Time to press the right button. |
| `challengePassHours` | Number | How long a player who passed is not asked again. |
| `challengeTitle`, `challengeText`, `challengeFailText` | String | The menu title, its text (`{word}` is the word to press) and the kick message on failure. |
| `vpnBurstHoldMinutes` | Number | How long VPN joins stay refused once a burst trips; a new burst after that trips it again. |
| `vpnLists` | Array | Sources for the VPN list: `http(s)://` or `file:` URLs of `a.b.c.d/nn` lines. |
| `vpnListRefreshHours` | Number | How old the cached list may get before it is downloaded again. |
| `vpnKickText` | String | The message shown to a player on a listed network. |
| `maxPlayersPerIp` | Number | Players one address may have on the server at once; `0` for no limit. |
| `duplicateKickText` | String | The message shown to a player over that limit. |
| `maxConnectionsPerIp` | Number | Connections one address may open per window; `0` turns flood limits off. |
| `connectionWindowMs` | Number | The window for `maxConnectionsPerIp`, in milliseconds. |
| `blockSeconds` | Number | How long an address that broke a rule is refused at accept. |
| `voteCommands` | Array | The client commands the vote gate covers; names not registered are ignored. |
| `voteStartMinutes` | Number | Active minutes a player needs to start a kick vote. |
| `voteCastMinutes` | Number | Active minutes a player needs to cast a vote or start any other. None for a player alone on the server changing the map (`votemap`, `rtv`, `evote map\|skip`), once per 5 minutes server-wide. |
| `voteMinActiveMinutes` | Number | Under this many active minutes a player is *low trust*: challenged by raid mode, counted for chat floods. |
| `trustedTargetShare` | Number | A kick of a trusted player needs this share of the active players voting yes (three at least); any kick needs one of them trusted. |
| `voteMaxSuspicion` | Number | A suspicion score this high or higher keeps a player out of votes. |
| `voteRefusedText` | String | What a refused voter is told. |
| `trustedMinutes`, `trustedDays` | Number | Active minutes, over at least this many days, that make a player *trusted*: never challenged by raid mode, able to vote during a raid a scripted join burst started, the only ones who start Essentials' votes, and needed as a yes voter for any vanilla kick. |
| `coordinatedCommands`, `coordinatedSeconds` | Number | The same vote command (whatever its arguments) from this many players not yet trusted who already look like raiders (not those short of minutes only), within this many seconds, starts raid mode; `0` turns it off. |
| `raidJoins`, `raidJoinSeconds` | Number | Joins of players not yet trusted from this many addresses within this many seconds start raid mode; `0` turns joins off as a trigger. Only completed joins count, so refused retries and bare connects cannot hold it open. |
| `raidNewJoins` | Number | Joins of uuids with no trust record from this many addresses within `raidJoinSeconds` start raid mode, with no look-alike test; `0` turns it off. Twice `raidJoins` by default. |
| `raidHoldMinutes` | Number | How long raid mode stays on after its last trigger. |
| `raidChallengeSeconds` | Number | Time for a raid-mode challenge: walk a few steps, then press. |
| `raidMoveText` | String | What a player challenged by raid mode is told (chat and an on-screen notice). |
| `chatFloodSenders`, `chatFloodRaidSenders`, `chatFloodSeconds` | Number | The same chat line from this many low-trust players within `chatFloodSeconds` is dropped, from `chatFloodRaidSenders` it starts raid mode; twice as many for lines of up to three characters. `0` turns either off. |

## 💻 Commands

All commands are intended for the **Server Console**.

| Command | Arguments | Description |
| :--- | :--- | :--- |
| `blockipreload` | *(none)* | Reloads `config.json` and `ip.mmdb`, and downloads the VPN list again when it is on. |
| `blockipstats` | *(none)* | Shows the VPN mode and whether a burst is on, the list size, GeoIP status, blocked addresses and whether the connect filter is installed; then raid mode (its reason and time left), trust records and flags, challenges waiting and players tracked. |
| `blockipinfo` | `<name\|uuid>` | An online player (by uuid, or name without colours) or a stored uuid: active minutes, days, first and last seen, flag, trust level, the suspicion score with its parts, and whether the vote gate lets them vote. |
| `blockipunflag` | `<uuid>` | Lifts the flag a failed challenge put on a uuid. |
| `blockipraid` | `<on\|off>` | `on`: raid mode until `blockipraid off`. `off`: ends it now. |
| `addcountry` | `<country_code>` | Adds a 2-letter code to the blocklist (e.g., `addcountry US`). |
| `removecountry` | `<country_code>` | Removes a country code from the blocklist. |
| `addwhitelist` | `<ip>` | Adds an IP address to the whitelist. |
| `removewhitelist` | `<ip>` | Removes an IP address from the whitelist. |
| `adduuid` | `<uuid>` | Adds a player UUID to the whitelist. |
| `removeuuid` | `<uuid>` | Removes a player UUID from the whitelist. |

## 📣 Raid Events for Other Plugins

When raid mode starts and ends, BlockIp fires a plain `String` through Arc's `Events`, so another plugin (ServerAdminer, which alerts Discord) can listen without either jar depending on the other:

*   `BlockIp:raid:start:<reason>` - `<reason>` is one of `join burst`, `new uuid burst`, `coordinated votes`, `chat flood`, `manual`. Never a name, address or uuid.
*   `BlockIp:raid:end` - exactly once after each start, when the hold runs out or on `blockipraid off`.

```java
Events.on(String.class, text -> { if (text.startsWith("BlockIp:raid:")) ... });
```

## ❓ Troubleshooting

*   **"MaxMind DB reader is not initialized" Error:**
    *   Ensure the file is named exactly `ip.mmdb`.
    *   Ensure it is located in `config/mods/blockip/`.
    *   Run `blockipreload` after placing the file.
*   **Changes to config.json are not working:**
    *   You must run `blockipreload` or restart the server for manual file edits to take effect.
*   **IPv6 Support:**
    *   The plugin supports GeoIP blocking for IPv6.
    *   *Note:* The `addwhitelist` command currently validates for IPv4 patterns. To whitelist an IPv6 address, add it manually to `config.json` and reload.

## ⚖️ License & Attribution

This product uses GeoLite2 data created by MaxMind, available from https://www.maxmind.com.
The hosting and VPN lists are published by X4BNet at https://github.com/X4BNet/lists_vpn.