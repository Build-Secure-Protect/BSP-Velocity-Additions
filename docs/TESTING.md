# Testing BSP-Velocity Additions

Unit tests run with `./gradlew test` and mock the Velocity API. Claude cannot run the live proxy, so every task ends with a dated checklist here of what the owner should try on the proxy.

## 2026-10-18: project skeleton (task T2)

Built and unit-tested: the plugin class loads and logs at start-up.

Untested on the proxy:

- [ ] Drop `build/libs/bsp_velocity_additions-0.1.0.jar` into the proxy's `plugins/` folder and start the proxy.
- [ ] The log shows `Loaded plugin bsp_velocity_additions 0.1.0 by Mrgregles` and the start-up line with the backend server count.
- [ ] The folder `plugins/bsp_velocity_additions/` is created on first start (it holds the config and data later).

## 2026-10-18: last server (task T4), config (T5) and the local proxy (T9)

Built and unit-tested (29 tests, `./gradlew test`): the config loader, the JSON store, the one-time import of VelocityRememberServer's `locations.yml`, the reachability table, the initial-server choice and the kick redirect.

Checked headless on a local Velocity 3.4.0 (`server/`, loopback, two backends that were both offline): the plugin loads, writes `plugins/bsp_velocity_additions/config.toml`, imports the entries, logs "Server X is not answering" for both backends within a second, and writes `last-servers.json` on shutdown. No player could connect in that check, so everything below is still to be tried on the real proxy.

Untested on the proxy:

- [ ] Stop the proxy, remove `VelocityRememberServer-0.1.2.jar` from `plugins/` (decision D1), drop `build/libs/bsp_velocity_additions-0.1.0.jar` in, start the proxy.
- [ ] The log shows `Imported N last-server entries from .../velocityrememberserver/locations.yml` (N is the number of lines in that file) and `Last server: on, N player(s) remembered`.
- [ ] Within ten seconds the log shows one `Server <name> is reachable` or `is not answering` line per backend.
- [ ] Join with a player whose entry points at a server that is online: the log says `<name> returns to <server>` and you arrive there.
- [ ] Switch to another server with BSP-Teleport, disconnect, reconnect: you arrive on the second server (the switch was recorded in `last-servers.json`).
- [ ] Stop the backend you were last on, reconnect: the log says `<name>'s last server <server> is offline; sending them to <spawn>` and you arrive on the first server of `try` (this is the VelocityRememberServer bug, D13).
- [ ] Stop that backend while the proxy's last ping still says it is up (within ten seconds), reconnect: you still arrive at spawn, and the log says `<name> could not join <server>; sending them to <spawn>`.
- [ ] A brand-new player (no entry) joins the first reachable `try` server.
- [ ] Set `fallbackServer = "BSP-Spawn"` (or another real name) in `config.toml`, restart the proxy, repeat the offline test: the fallback server wins over the `try` list.
- [ ] Add a server name to `ignoredServers`, leave from that server, reconnect: you arrive at spawn, not on the ignored server.
- [ ] Known limit: a backend whose port takes longer than three seconds to accept a connection is treated as offline until the next check, unless a player is on it. If that is ever seen, raise the matter and the timeout can become a setting.

### 2026-10-18, bug B1 fixed: "not answering" with the backends up

Cause: Velocity cannot decode the status reply of a backend with 251 mods, so the status ping failed. Fix: a TCP connection check instead of a status ping, and a server with players on it always counts as reachable. Proved by three new unit tests that fail against the old code.

- [ ] Replace the jar in `plugins/` (there is only one jar now, no `-slim`), start the proxy with both backends up: within ten seconds the log shows `Server BSP-Spawn is reachable` and `Server BSP-SafeRoom is reachable`, and no `failed` lines.
- [ ] Stop one backend: within ten seconds `Connection check to <server> (<address>) failed: ConnectException: ...` once, then `Server <server> is not answering`. Start it again: `is reachable` within ten seconds.
- [ ] Join a backend, then leave it empty and stop it with a player still on the other one: the stopped one goes `not answering`; the one with the player never does.

## 2026-10-18: network join and leave lines (task T6)

Built and unit-tested (40 tests in all): one join line when a player lands on their first backend, one leave line when they disconnect from the proxy, an optional "moved" line, the silent permission, and `messages.toml` with MiniMessage lines merged over the bundled defaults. Loaded headless on the local proxy: `messages.toml` is written and the feature reports itself on. No player connected, so everything below is still to be tried on the real proxy.

The owner picked option A, vanilla wording (`tools/preview/join_leave_mockups.html`), and ruled out any server-switch announcement.

Untested on the proxy:

- [ ] Start the proxy with the jar; the log shows `Join and leave lines: on, silent permission bspva.joinleave.silent` and `plugins/bsp_velocity_additions/messages.toml` exists.
- [ ] Join with one player while another is online on a different server: both see one yellow `<name> joined the game` line, once, and the proxy console logs it too.
- [ ] Before this: set `suppressJoinLeaveMessages = true` under `[network]` in `bsp_teleport-server.toml` on **every** backend (BSP-Teleport's VA-T1, its 2026-10-18 build; `/bsptp reload` re-reads it). A backend without it still prints its own lines on every switch.
- [ ] Switch servers with BSP-Teleport (or `/server`): nobody sees a line from the proxy, and neither backend prints its own "left the game" or "joined the game".
- [ ] Disconnect: everyone sees one `<name> left the game` line.
- [ ] Connect and get refused before reaching any backend (for example every backend down): no join and no leave line.
- [ ] Give a staff player the permission `bspva.joinleave.silent` (LuckPerms-Velocity once installed, decision D4): their join and leave print nothing.
- [ ] Edit a line in `messages.toml` (for example a MiniMessage colour), restart the proxy, check the new wording. A line removed from the file falls back to the bundled default.
- [ ] A `config.toml` written by an older version has no `[joinLeave]` section; the feature then runs with its defaults. Copy the section from the template in the jar (or delete the file so it is rewritten) to see the keys.

## 2026-10-18: network tab list and /players (task T8)

Built and unit-tested (48 tests in all): every player on a backend is added to every other player's tab list with their skin and ping; names use `tabList.entry` from `messages.toml` (LuckPerms prefix when LuckPerms-Velocity is present, a staff mark for `bspva.tab.staff`); header and footer with `<online>` and `<ping>`; a refresh on every switch and disconnect and every `tabList.refreshSeconds`; `/players` and `/online` with names only, or grouped by server for the console and `bspva.players.servers`. Nothing shows a server to a normal player (D16). Loaded headless on the local proxy.

The owner picked option B (`tools/preview/tab_list_mockups.html`): a two-line header with the server address, a footer with the count and the viewer's ping, and the LuckPerms prefix in front of each name.

Untested on the proxy:

- [ ] Stop the proxy, remove `Velocitab-1.7.3.jar` from `plugins/` (decision D3), put the new jar in, start. The log shows `LuckPerms is not on the proxy; names show without rank prefixes` (or the "found" line once LuckPerms-Velocity is installed), `Network tab list: on, refreshing every 3 s` and `/players: on, server breakdown for bspva.players.servers`.
- [ ] Two players on different servers: each sees the other in the tab list, with the right skin and a ping bar, the two-line header `Build | Secure | Protect` / `minecraft.buildsecureprotect.com`, and the footer `2 online · ping <your ms> ms` (the ping figure refreshes every three seconds).
- [ ] Switch servers: within three seconds the tab list is complete again (Velocity empties it on a switch) and nothing shows which server anyone is on.
- [ ] A third player leaves: they vanish from everyone's tab list and the footer drops to 2.
- [ ] `/players` as a normal player: one line, `Online (2): Alex, Steve`, names sorted, no servers. `/online` does the same.
- [ ] `/players` from the console, and from a player with `bspva.players.servers`: a header line and one line per server with its count and names.
- [ ] Give a player `bspva.tab.staff` and set `staffMark = "<gold>✦ "` in `messages.toml`: the mark appears before their name in tab and in `/players` after a restart.
- [ ] With LuckPerms-Velocity installed (D4) and a group prefix such as `&6[Owner]`: the prefix shows in tab and `/players`.
- [ ] Watch for a flicker: a backend that rewrites display names (team colours, a scoreboard mod) will overwrite ours until the next refresh. If it shows, lower `refreshSeconds` or report it so the refresh can react to the backend's packet instead.
- [ ] A Forge client through Ambassador: check that remote players' heads render and that the list survives the Forge handshake on a switch.

## 2026-10-18: shared chat (task T7, BSP-Core request VA-C2)

Built and unit-tested (63 tests in all): the decoder for BSP-Core's `bsp_core:proxy` payload (version 1, kind `chat`), the relay that forwards each line to players on every other server, the staff variant with the server tag, console logging, and the guards (bytes from a client are dropped, unknown versions and kinds ignored, other channels untouched, a `storage.serverId` that differs from the Velocity name is warned about once). Loaded headless on the local proxy; no backend sent a line there, so the whole flow is still to be seen in game.

Needs BSP-Core 1.3.0 (commit `6d65907`, the first with VA-C2) on every backend with `proxy.relayChat = true` in `serverconfig/bsp_core-server.toml`. BSP-Core's own checklist is "Proxy chat relay, VA-C2" in its `docs/TESTING.md`.

The owner picked option A from `tools/preview/chat_relay_mockups.html`: no tag for players, `[Server]` for staff with `bspva.players.servers`.

Untested on the proxy:

- [ ] Start the proxy: the log shows `Shared chat: on, listening on bsp_core:proxy`.
- [ ] Two players on different backends, both with `relayChat = true`: a plain line from one appears once for the other, with the speaker's prefix and colours as their own server showed it, and once in the proxy console as `[BSP-Wild] <Name> text`.
- [ ] A player on the speaker's own server sees the line once (the backend's own), not twice.
- [ ] Team chat (TEAM CHAT on, or `/t`) and a line sent where Region Guard denies `send-chat`: nothing crosses.
- [ ] `/me`, `/say`: nothing crosses.
- [ ] A player with `bspva.players.servers` on another server sees `[BSP-Wild]` in front of the line; others do not (option A).
- [ ] Set `relayChat = false` on one backend and restart it: its lines no longer cross, lines from the other still arrive.
- [ ] A backend whose `storage.serverId` differs from its Velocity name: one warning in the proxy log, lines still cross with the Velocity name as the tag.
- [ ] Set `chat.useBackendFormatting = false`, restart the proxy: relayed lines become plain `<Name> text`.
