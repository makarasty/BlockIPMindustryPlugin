# BlockIp Plugin for Mindustry

A lightweight plugin for Mindustry servers that keeps bots and unwanted networks out: connection flood limits, a per-address player cap, hosting/VPN network blocking and GeoIP country blocking, with IP and UUID whitelists.

It replaces BotEradicator; remove `BotEradicator.jar` when installing this. Two checks, both cheap:

*   **At accept**, on the network thread, before the server allocates anything for the connection: an address that opens connections too fast, or was refused recently, is closed straight away. A flood never reaches the main thread.
*   **At the connect packet**, on the main thread, once per join attempt: the player cap, the VPN list and the country. A binary search over ~31,000 merged ranges (about 240 KB) and a memory-mapped GeoIP lookup; no worker threads, no locks.

## 🚀 Key Features

*   **Flood Limits:** more than `maxConnectionsPerIp` connections in `connectionWindowMs` blocks the address for `blockSeconds`, refused at accept.
*   **Player Cap per Address:** at most `maxPlayersPerIp` players from one address at a time.
*   **Hosting / VPN Blocking, only when it is needed:** the [X4BNet](https://github.com/X4BNet/lists_vpn) datacenter and VPN lists, downloaded once a day into `config/mods/blockip/vpn-ipv4.txt` and read from there on restart (a failed download keeps the last good copy). In the default `auto` mode a VPN player can join a quiet server; VPN joins are refused only while `vpnMinPlayers` are online, or for `vpnBurstHoldMinutes` after `vpnBurstJoins` VPN joins arrive within `vpnBurstSeconds` - and the VPN players who joined during that burst are kicked when it trips. `always` refuses every VPN address, `off` never looks. IPv4 only: the published lists have no IPv6.
*   **A Button Instead of a Refusal** (`vpnChallenge`, on by default): a VPN player who would be refused for a busy server or `always` is let in and shown a menu - press the button with the given word within `challengeSeconds`. A person passes in a second and is not asked again for `challengePassHours`; a wrong button or no answer is a kick and a block. Until then the player can neither build nor chat. Attack mode still refuses outright: letting a flood in would send every bot the map.
*   **GeoIP Blocking:** Automatically kick players connecting from specific countries using the MaxMind GeoLite2 database.
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
> This plugin relies on the **MaxMind** (`maxmind-db`) library. Your `build.gradle` must be configured to **shadow (shade)** it into the final JAR file, otherwise the plugin will crash with `NoClassDefFoundError` at runtime.

## 📥 Installation

1.  **Download/Build the Plugin:**
    Place the `BlockIp.jar` into your server's `config/mods` folder.

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
"blockSeconds": 60
}
```

A file written by an older version gets the new keys added, with these defaults, on the next load.

### Configuration Fields

| Field | Type | Description                                                                          |
| :--- | :--- |:-------------------------------------------------------------------------------------|
| `blockedCountries` | Array | A list of 2-letter ISO-3166 country codes to block (e.g., `["CN", "RU"]`).           |
| `ipWhiteList` | Array | IPs that skip every check, flood limits included (e.g., a shared NAT address). |
| `uuidWhiteList` | Array | Mindustry Client UUIDs that skip the join checks (country, VPN, player cap). |
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

## 💻 Commands

All commands are intended for the **Server Console**.

| Command | Arguments | Description |
| :--- | :--- | :--- |
| `blockipreload` | *(none)* | Reloads `config.json` and `ip.mmdb`, and downloads the VPN list again when it is on. |
| `blockipstats` | *(none)* | Shows the VPN mode and whether a burst is on, the list size, GeoIP status, blocked addresses and whether the connect filter is installed. |
| `addcountry` | `<country_code>` | Adds a 2-letter code to the blocklist (e.g., `addcountry US`). |
| `removecountry` | `<country_code>` | Removes a country code from the blocklist. |
| `addwhitelist` | `<ip>` | Adds an IP address to the whitelist. |
| `removewhitelist` | `<ip>` | Removes an IP address from the whitelist. |
| `adduuid` | `<uuid>` | Adds a player UUID to the whitelist. |
| `removeuuid` | `<uuid>` | Removes a player UUID from the whitelist. |

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