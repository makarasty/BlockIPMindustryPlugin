# BlockIp Plugin for Mindustry

A lightweight plugin for Mindustry servers that provides advanced access control through GeoIP blocking and whitelisting.

The check runs once per join attempt, on the main thread, against a memory-mapped database: no worker threads, no locks, no per-IP cache to grow during a join flood.

## 🚀 Key Features

*   **GeoIP Blocking:** Automatically kick players connecting from specific countries using the MaxMind GeoLite2 database.
*   **Dual Whitelisting:**
    *   **IP Whitelist:** Allow specific IP addresses to bypass country checks.
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
> This plugin relies on **Jackson** (`jackson-databind`) and **MaxMind** (`maxmind-db`) libraries. Your `build.gradle` must be configured to **shadow (shade)** these dependencies into the final JAR file, otherwise the plugin will crash with `NoClassDefFoundError` at runtime.

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
  "blockedCountries" : [ ],
  "ipWhiteList" : [ ],
  "uuidWhiteList" : [ ],
  "kickText" : "Your country is blocked on this server."
}
```

### Configuration Fields

| Field | Type | Description                                                                          |
| :--- | :--- |:-------------------------------------------------------------------------------------|
| `blockedCountries` | Array | A list of 2-letter ISO-3166 country codes to block (e.g., `["CN", "RU"]`).           |
| `ipWhiteList` | Array | Specific IPs that can join even if their country is blocked (e.g., `["127.0.0.1"]`). |
| `uuidWhiteList` | Array | Mindustry Client UUIDs that can join from *any* IP/Country.                          |
| `kickText` | String | The message displayed to the player when they are disconnected.                      |

## 💻 Commands

All commands are intended for the **Server Console**.

| Command | Arguments | Description |
| :--- | :--- | :--- |
| `blockipreload` | *(none)* | Reloads `config.json` and `ip.mmdb`. |
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