# Changelog

All notable changes to BSP-Velocity Additions. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
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
