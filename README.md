# BSP-Velocity Additions

A plugin for the [Velocity](https://papermc.io/software/velocity) proxy of the Build Secure Protect (BSP) Minecraft network. It is not a Forge mod and does not go into the modpack; it lives in the proxy's `plugins/` folder.

Features, in order of work:

1. **Last server** (built, see below): a player who leaves the network comes back to the server they were on. If that server is offline they go to spawn instead of being refused.
2. **Shared chat** across every server behind the proxy (built, proxy side): needs BSP-Core 1.3.0 with `proxy.relayChat = true` on every backend. Team chat and blocked chat never cross, because the backend decides what was public.
3. **Network-wide player list and tab list** (built): everyone in everyone's tab list with their LuckPerms prefix, a two-line header, a footer with the count and your ping; `/players` for a list. No server is shown to normal players. Replaces Velocitab.
4. **Join and leave messages for the whole network** (built): shown once when a player really joins or leaves, never when BSP-Teleport moves them between servers. Each backend's own vanilla lines are switched off with `suppressJoinLeaveMessages = true` in BSP-Teleport's server config, on every backend at once.

Companion to the BSP Forge mods on the backends: BSP-Core, BSP-Region Guard, BSP-Teleport, BSP-RankUp and BSP-InventorySync. Anything this plugin needs from them is optional on their side, so a server without a proxy loses nothing.

## Requirements

- Velocity 3.4.0 or newer, Java 17 or newer.
- Backend server names in `velocity.toml` must match Region Guard's `storage.serverId` and BSP-Teleport's `network.serverName`.

## Install

1. Build, or take `bsp_velocity_additions-<version>.jar` from a release.
2. Remove VelocityRememberServer and Velocitab from the proxy's `plugins/` folder: this plugin takes over both jobs and imports VelocityRememberServer's `locations.yml` once. LuckPerms-Velocity is optional; with it, rank prefixes show in the tab list and `/players`.
3. Put the jar in `plugins/` and start the proxy. `plugins/bsp_velocity_additions/config.toml` appears with comments for every setting.

## Configuration (`config.toml`)

| Key | Default | Meaning |
|---|---|---|
| `lastServer.enabled` | `true` | Send players back to their last server. |
| `lastServer.ignoredServers` | `[]` | Servers never remembered; a player who left from one starts at spawn. |
| `lastServer.fallbackServer` | `""` | Spawn. Empty means the first server of velocity.toml's `try` list that answers a ping. |
| `lastServer.pingSeconds` | `10` | How often every backend's port is checked to learn whether it is reachable. A server with players on it always counts as reachable. |
| `lastServer.importVelocityRememberServer` | `true` | Import `plugins/velocityrememberserver/locations.yml` once, while this plugin's store is empty. |
| `joinLeave.enabled` | `true` | Announce real network joins and leaves to everyone. |
| `joinLeave.silentPermission` | `bspva.joinleave.silent` | Players with this permission produce no lines. Empty disables the check. |
| `tabList.enabled` | `true` | Everyone on the network in everyone's tab list. |
| `tabList.refreshSeconds` | `3` | How often the footer and names are refreshed. |
| `tabList.staffPermission` | `bspva.tab.staff` | Players with it get `tabList.staffMark` before their name. |
| `players.enabled` | `true` | The `/players` and `/online` commands. |
| `players.serversPermission` | `bspva.players.servers` | Players with it see `/players` grouped by server, and the server tag on relayed chat. The console always does. |
| `chat.enabled` | `true` | Forward BSP-Core's relayed chat lines to the other servers. |
| `chat.useBackendFormatting` | `true` | Show the line as the speaker's server formatted it. Off: a plain `<Name> text`. |
| `chat.logToConsole` | `true` | Print relayed lines to the proxy console with their server. |

A section missing from an older `config.toml` runs with its defaults; copy it from the template inside the jar to see the keys.

## Messages (`messages.toml`)

Every line a player can see, in [MiniMessage](https://docs.advntr.dev/minimessage/format.html). Join and leave lines take `<player>` and `<server>`; the tab list header and footer take `<online>` and `<ping>`; each name (`tabList.entry`) takes `<player>`, `<prefix>` and `<staff>`; `/players` lines take `<online>`, `<names>`, `<servers>`, `<server>` and `<count>`; relayed chat (`chat.relay` for everyone, `chat.relayStaff` for staff) takes `<line>`, `<player>`, `<text>` and `<server>`. Server switches are never announced and the tab list never shows a server, by design: both would let players track each other. A line you delete falls back to the bundled default. Restart the proxy after editing.

The store is `plugins/bsp_velocity_additions/last-servers.json`, one entry per player with the server name, the last known player name and a timestamp.

## Build

```
./gradlew build
```

The plugin jar is `build/libs/bsp_velocity_additions-<version>.jar`. Unit tests: `./gradlew test`.

For a headless check, `tools/local_proxy.sh` copies the jar into a local Velocity under the gitignored `server/` folder (see `server/README.txt` once it exists), and `server/start.sh` runs that proxy on `127.0.0.1:25577` with its output in `server/console.log`.

## Documents

- `docs/API.md`: what other plugins may call.
- `docs/TESTING.md`: dated checklists of what is built but still to be tried on the live proxy.
- `CHANGELOG.md`.

MIT licence.
