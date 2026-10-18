# Changelog

All notable changes to BSP-Velocity Additions. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

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
