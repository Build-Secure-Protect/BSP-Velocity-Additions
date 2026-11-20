# Changelog

All notable changes to BSP-Velocity Additions. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- **The Fourthwall store (BSP-Server Shop request SH-R1, decisions D38 to D41):** off by default (`store.toml`, `enabled = false`).
  - **Receiver:** checks the signature of every webhook and stores it in the shared MariaDB `bsp_shop` database, answering at once. It listens on `0.0.0.0:8765` inside the proxy's container, which a Pterodactyl allocation publishes on mc-main's loopback for `cloudflared`.
  - **Processing:** orders, cancellations, email changes and memberships are handled on a worker thread. Only PENDING reward rows are written into `shop_rewards`, for BSP-Server Shop to deliver; replays and resyncs never write a second row.
  - **Commands:** `/store` (link, claim, status) and `/storeadmin` (lookup, assign, unlink, grant, resync, reload; permission `bsp.shop.admin`). Emails are always masked.
  - **Resync:** reads Fourthwall's API every 24 h.
  - **Config:** what each SKU and tier gives is in `store-rewards.toml`, checked when it loads.
  - **Secrets:** only from `BSP_FOURTHWALL_WEBHOOK_SECRET`, `BSP_FOURTHWALL_API_USERNAME`, `BSP_FOURTHWALL_API_PASSWORD` and `BSP_SHOP_DB_PASSWORD`.
  - **Libraries:** HikariCP 6.3.0 and the MariaDB driver 3.4.1 are shaded and relocated; the jar is about 14 MB.
  - **Docs:** `docs/STORE.md`.
  - **Matching products:** a product is found by its variant's SKU or, for Fourthwall's digital products (which have no SKU), by its slug (D42).
  - **`/storeadmin revoke [order]`:** takes an order back by hand after a refund or chargeback (T27, D43).
  - **Fixed before release:**
    - B7 to B15, from an audit;
    - B16, Fourthwall's compressed API answers;
    - B17, the connection pool's own stack traces in the console.
  - **Tested live on 2026-11-20:** the webhook through Cloudflare, capture, orders by name, by link, by claim and by staff assign, the resync, and the database-down retry.
  - **Not tested live:** memberships (not sold yet), cancellations (Fourthwall cannot cancel digital orders), and `/storeadmin revoke`.

## [0.2.1] - 2026-11-18

### Changed
- **The tab list keeps BSP-RankUp's names** (BSP-RankUp request RU-R3): a name a game server gives a player (RankUp's prefix image and suffix) is no longer replaced by the proxy's text prefix, and a player on another server shows with the name their own server gives them, so rank images and suffixes show for everyone on the network. Where a server gives no name (RankUp's prefix display off, or no RankUp), `tabList.entry` fills in as before. `/players` is unchanged. Untested on the live proxy.

### Fixed
- A rank changed by BSP-RankUp while the player is online showed in the tab list and `/players` only after a relog (bug B6). LuckPerms' API saves such a change without telling the other servers, so the proxy's LuckPerms kept the rank from login. The proxy now reloads each online player's rank from LuckPerms' storage every 30 s (`rankRefreshSeconds` under `[tabList]` in `config.toml`, 0 switches it off; a `config.toml` without the line uses 30). BSP-RankUp is asked to announce its changes too (request VA-RU1), which makes it near instant. Untested on the live proxy.
- The network tab list failed on the live proxy with `NoSuchMethodError: TabList.buildEntry(...)` every 3 seconds once two players were online (bug B3): the plugin called an 8-argument form that is in the newer Velocity API it compiles against but not in the proxy (git-91bdcebb-b467). It now uses the 7-argument form both have.
- The proxy console showed `chat.type.text` for every relayed chat line (bug B4); it now shows `<Name> message`. Players were never affected.

### Added
- **Chat history on joining the network** (task T25): a player joining the network gets the recent public chat from every server (the last 50 lines, none older than 30 minutes), between `─── Earlier chat ───` and `─── Now ───`, before the "joined the game" line. Old lines look exactly like live relayed chat (staff see the server tag). Nothing on a server switch, nothing when there is no recent chat. Kept in memory only; team and blocked chat never kept. `[chatHistory]` in `config.toml` (on), `chatHistory.header` and `footer` in `messages.toml`; files without them use the defaults. Replayed lines are server messages, so Minecraft's report button does not work on them. Untested on the live proxy.
- **Network kicks (BSP-Server Utilities request SU-VA2):** a game server can ask the proxy, on `bsp_server_utilities:proxy` over the player's own connection, to disconnect a player from the whole network with its reason, so Velocity never moves a kicked player to another server instead. Only game servers are listened to, never a client; unknown versions and kinds are ignored; nothing on the channel is ever passed on to a client, even with the kick switched off. One console line per kick: `[BSP-Spawn] kicked Steve: <reason>`. `[networkKick] enabled` in `config.toml` (on). Contract in `docs/API.md`. Untested on the live proxy and with Server Utilities.
- **`<server_tag>`, server images in chat (BSP-Server Utilities request SU-VA1):** a new placeholder beside `<server>` in `chat.relay`, `chat.relayStaff` and `players.staffServer`. It is the server's image from BSP-Server Utilities (key `bsp_server_utilities.server_image.<name in lower case>`, font `bsp_server_utilities:server_tags`, white so the image keeps its colours); a server with no image shows the grey `[BSP-Spawn]` text as before. Hovering it shows the new line `chat.serverTagHover` (default `<white><server>`). The bundled `chat.relayStaff` is now `<server_tag> <line>`. `<server>`, `chat.relay`, `players.staffServer`, the console line and Discord are unchanged. A `messages.toml` without a `relayStaff` line (any file written before shared chat existed) gets the new default straight away; one that has its own `relayStaff` line keeps it until you change it to `relayStaff = "<server_tag> <line>"`. `serverTagHover` comes from the bundled file until you add it. Untested with a real client.
- `tabList.rankRefreshSeconds` in `config.toml` (see Fixed, B6).
- `tools/check_proxy_api.sh`: checks, before a release, that the built jar calls no Velocity or Adventure method the network's proxy lacks.
- **JourneyMap world ids from the proxy:** every server gets its own JourneyMap map folder on the client. The proxy answers JourneyMap's request on `worldinfo:world_id` with the id of the server the player is on, never forwards it, drops any reply a server sends, and sends the id again 1.5 s after every server switch (only to clients that have asked, so clients without JourneyMap get nothing). Ids come from each server's velocity.toml name and never change; `[journeyMap.worldIds]` in `config.toml` can pin one. `debug` logs every message on the channel with its bytes; `debugAllChannels` logs the channels each client registers. Packet layout checked against JourneyMap 6.0.7. Untested with a real JourneyMap client.

## [0.2.0] - 2026-10-25

### Added
- **The BSP Discord bot (Herald, BSP-Core request VA-D1):** one bot login on the proxy, off by default (`[discord]` in `config.toml`). It reads BSP-Core's steal alerts from Redis (`bsp_core:discord`) and sends the totem's owner a direct message when their totem is being stolen and when the steal ends, and posts "A totem is being stolen on <server>" in one channel, naming nothing else. `/link CODE`, `/unlink name` (a name is always needed, so nobody unlinks everything by accident) and `/linkstatus` in the BSP Discord, answered so only the player sees it. The token comes only from `BSP_DISCORD_TOKEN` and the Redis password only from `BSP_REDIS_PASSWORD`; neither is ever in a file or the log. Only the Discord user id is kept beside each Minecraft account (`discord-links.json`), never logged or sent to the game servers. Discord's rate limits are honoured, at most three tries per message, failures logged once. Setup guide: `docs/DISCORD.md`. Untested against the real Discord.
- JDA 6.7.0 is shaded into the jar with its libraries relocated under `com.mrgregles.bsp_velocity_additions.shaded`; its voice parts (opus, Tink) are left out. The jar is now about 13 MB.

### Changed
- README: backends may be standard Forge 1.20.1 or Mohist; the plugin needs no change for either (the network moves to Forge on 2026-10-21).

## [0.1.0] - 2026-10-18

### Fixed
- Backends with a large mod list were reported as "not answering" (bug B1): Velocity cannot decode a modded server's status reply, so the status ping failed although the server was up. Reachability now comes from a plain TCP connection check, and a server with any player connected always counts as reachable. The failure reason is logged once per outage.
- Only one jar is built; the unshaded `-slim` jar is gone.

### Added
- **Shared chat:** every public chat line that BSP-Core sends on `bsp_core:proxy` (its `proxy.relayChat` switch) is forwarded to the players on every other server, as the speaker's server formatted it; staff with `bspva.players.servers` see a `[Server]` tag, everyone else sees the line unchanged. Lines also go to the proxy console. Bytes from a client on the channel are dropped.
- **Network tab list:** everyone on the network appears in everyone's tab list with their skin and ping; header, footer and the name format are MiniMessage lines in `messages.toml` (`<online>`, `<ping>`, `<prefix>` from LuckPerms-Velocity when present, `<staff>` for `bspva.tab.staff`). No server is shown. Replaces Velocitab.
- **`/players` (alias `/online`):** names only for everyone; grouped by server with counts for the console and `bspva.players.servers`.
- **Network join and leave lines:** one line to everyone when a player lands on their first backend, one when they disconnect from the proxy, never anything when they switch servers (by design, so players cannot track each other). Players with `bspva.joinleave.silent` print nothing. Wording in `messages.toml` (MiniMessage), merged over the bundled defaults.
- **Last server:** a player who leaves the network is sent back to the server they were on. If that server is offline, no longer exists or is in `lastServer.ignoredServers`, they go to spawn instead: `lastServer.fallbackServer` when set, otherwise the first server of velocity.toml's `try` list that answers a ping. A first connection that still fails is redirected to spawn instead of disconnecting the player. Servers are pinged every `lastServer.pingSeconds` (10) seconds.
- One-time import of VelocityRememberServer's `plugins/velocityrememberserver/locations.yml` when the plugin's own store is empty.
- `plugins/bsp_velocity_additions/config.toml`, created from a commented template on first start, and `last-servers.json`, the plugin's store.
- Project skeleton: Velocity 3.4.0-SNAPSHOT plugin `bsp_velocity_additions`, Gradle 8.8 build with a shaded jar, JUnit 5 and Mockito test rig, generated `BuildConstants`.
