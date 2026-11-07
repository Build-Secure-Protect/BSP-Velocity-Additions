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
- [ ] Before this: set `suppressJoinLeaveMessages = true` under `[network]` in `bsp_teleport-server.toml` on **every** backend (BSP-Teleport 0.2.0, the first with VA-T1; `/bsptp reload` re-reads it). A backend without it still prints its own lines on every switch.
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

## 2026-10-21: backends moved from Mohist to standard Forge (task T14)

No code changed. Before the move, on every backend: keep `proxy-compatible-forge` and its `config/proxy-compatible-forge.toml` (forwarding mode MODERN, the proxy's secret), swap LuckPerms-Bukkit for LuckPerms-Forge on the same database, keep BSP-Core 1.3.0 with `relayChat = true` and BSP-Teleport 0.2.0 with `suppressJoinLeaveMessages = true`.

Untested on the proxy:

- [ ] Start the proxy with the Forge backends up: within ten seconds every backend shows `Server <name> is reachable`.
- [ ] A player joins: they arrive and see one `<name> joined the game` line, and the backend prints no join line of its own.
- [ ] Rejoin: switch servers, disconnect, reconnect; you arrive on the second server (the last-server steps above).
- [ ] Chat: a line from one backend appears once on another (the shared-chat steps above).
- [ ] With LuckPerms-Forge on the shared database: a rank prefix still shows in the tab list and `/players`.

## 2026-10-25: the BSP Discord bot (task T15, BSP-Core request VA-D1)

Unit tests (59 new, 122 in all) cover every rule with a fake Discord and a fake Redis: who gets which DM, the public line's only field, a 429 waited out and one that outlasts the steal, three tries at most and one log line, a closed DM (50007) setting the week-long undelivered flag, unknown and reused and expired codes, the wrong-code limit, Redis down, a new link replacing an old one, several accounts per Discord user, `/unlink` needing a name, `/linkstatus`, the unlink message from the game, the flags restored at start, markdown escaping, and no Discord id in any log line or Redis value. Also checked here: the plugin loads on a local Velocity with the bot off (one line), and with the bot on but no `BSP_DISCORD_TOKEN` (one warning, nothing started); JDA and its relocated libraries load from the shaded jar and build a message.

The jar is `build/libs/bsp_velocity_additions-0.2.0.jar`; the proxy log shows `Loaded plugin bsp_velocity_additions 0.2.0`. **Nothing has been tried against the real Discord.** Logging in, registering the commands, the DMs and the channel post are untested until the owner runs them. Setup: `docs/DISCORD.md`.

- [ ] Follow `docs/DISCORD.md` steps 1 to 6. Start the proxy: the log shows `Discord bot: starting`, `Herald: listening on Redis`, `Herald is logged in to Discord` and `/link, /unlink and /linkstatus are ready in the BSP Discord`.
- [ ] The log never shows the token, the Redis password or any Discord id (search the log for the first digits of your own Discord id).
- [ ] In Discord, type `/` in the BSP Discord: `/link`, `/unlink` and `/linkstatus` appear with their descriptions. In a DM with the bot they do not.
- [ ] In game, `/bsp discord link`; in Discord `/link` with the code: the green **Linked** card, seen only by you; a test DM arrives; `/bsp discord status` in game says linked within about 5 s.
- [ ] `/link` with the same code again: **Code not accepted**. With a made-up code: the same. After five wrong codes: **Too many wrong codes**; ten minutes later it works again.
- [ ] Link a second Minecraft account to the same Discord account: `/linkstatus` lists both names and nothing else, with "/unlink and a name to remove one" under them.
- [ ] From another Discord account, `/link` a fresh code for the first Minecraft account: the old Discord account gets "Steal warnings for <name> now go to another Discord account."; warnings now go to the new one.
- [ ] With Herald on a totem, start a steal (a second player): after the thief's Shroud, the owner's DM shows the red card with Server, Where, Thief and Time left, and the phone notification reads "Your totem on <server> is being stolen!". The alert channel shows only "A totem is being stolen on **<server>**".
- [ ] Walk the thief away until the steal fails: a green "✔ Steal failed" DM with the reason. Let a steal finish: a red "✖ Totem stolen" DM.
- [ ] A totem without Herald: the channel line only, no DM. A player who never linked: no DM, nothing extra in public.
- [ ] Turn off DMs from server members (Discord: the BSP server → Privacy Settings), start a steal: nothing public; at your next login BSP-Core tells you the warning could not reach you (BSP-Core's part, once built); the link stays.
- [ ] `/unlink` asks for a name (Discord will not send it without one). `/unlink` and one of your names: **Unlinked**, only that account; Herald's card in game shows "not linked" within about 5 s. A name that is not yours: **Not found**. Unlinking in game removes the link from `discord-links.json` too.
- [ ] Stop Redis for a minute while the proxy runs: one `Redis ... is not answering` line, `/link` answers **Try again soon** and the code still works afterwards; when Redis is back, `Redis answers again` and the links are marked again.
- [ ] Remove the bot's Send Messages permission in the alert channel and start a steal: one `could not send a public steal line ... MISSING_PERMISSION` line, not one per steal.
- [ ] Restart the proxy: links survive (`discord-links.json`), and `Herald: N Discord link(s) marked in Redis` shows the right N.

## 2026-10-27: JourneyMap world ids (task T19)

Unit tests (26 new, 148 in all) cover the packet layout read from JourneyMap 6.0.7's jar (`00 00 2A`, then the id as a Minecraft string; the request is `00 00 2A 00`), the stable name-based ids and the overrides, a request answered by the proxy and not forwarded, a backend's reply dropped, the resend after a switch and only for clients that asked, a request in the middle of a switch answered with the new server's id, stale resends after quick switches dropped, nothing for a player who left, and the debug lines. Checked here: the plugin loads on a local Velocity and logs one id per server. **Nothing has been tried with a real JourneyMap client.**

Background found before building: every backend has `useWorldId: "false"` in `journeymap/server/6.0/journeymap.server.global.config`, so no backend sends an id today and every server's map shares one client folder. With this feature on, that setting no longer matters (the proxy answers and drops backend replies); leave it as it is.

- [ ] Start the proxy: one `JourneyMap world ids: on` line and one `JourneyMap world id for <server>: <id>` line per server in velocity.toml, each id different.
- [ ] Set `debug = true` and `debugAllChannels = true` under `[journeyMap]` in `config.toml`, restart the proxy, join with the BSP client. The log shows `client <you> registers channels: [...]` (note any `journeymap:` channel), then `client -> proxy ... 00 00 2a 00 = (request, no id)` and `proxy -> client (reply) ... = <id>`. Copy these lines into the bug log if anything looks different.
- [ ] Fresh login to server A: in `.minecraft/journeymap/data/mp/` (or the CurseForge instance folder) a new folder appears whose name ends in A's id.
- [ ] `/server B` (or a portal or warp): about 1.5 s later the log shows `proxy -> client (resend after switch) ... on B`; the client switches to a folder ending in B's id and the map shows only B's terrain. Also note whether a `client -> proxy` request appears after the switch: if it does, the client asks again by itself.
- [ ] Switch back and forth five times quickly: the map never mixes tiles, and only one resend goes out after the last switch.
- [ ] Restart the proxy and the backends: the ids in the start-up lines are the same as before, and the client keeps using the same folders.
- [ ] Put `"BSP-Spawn" = "test-override-1"` under `[journeyMap.worldIds]`, restart the proxy: the start-up line says `(from config)` and the client uses a folder ending in `test-override-1`. Remove it again (or keep a real id you want).
- [ ] Join with a client that has no JourneyMap (vanilla, or the pack without it): no kick, no error, and no `proxy -> client` line for that player.
- [ ] Turn `debug` and `debugAllChannels` off again, restart the proxy.

## 2026-11-02: bugs B3 (tab list on the live proxy) and B4 (console chat line)

B3: the tab list called an 8-argument `TabList.buildEntry` that the live proxy (`git-91bdcebb-b467`) does not have; it now uses the 7-argument form both have. B4: the console printed `chat.type.text` for relayed chat; it now prints `<name> text`. Tests: `TabListFeatureTest` (the 7-argument call; the network's `velocity.jar` has it and not the 8) and `ChatRelayFeatureTest` (the console line for a translatable line); both failed against the old code. New: `tools/check_proxy_api.sh` lists every Velocity and Adventure method the built jar calls and fails if the proxy's `velocity.jar` lacks one; it flags the old 0.2.0 jar and passes the fixed one. Untested on the live proxy until the owner installs the rebuilt jar.

- [ ] Before installing: `tools/check_proxy_api.sh` prints `OK: the proxy has all of them.`
- [ ] Install the rebuilt `bsp_velocity_additions-0.2.0.jar`, restart the proxy, have two or more players online on different servers: no `NoSuchMethodError` and no `Exception in task ... BSP-Velocity Additions` lines.
- [ ] Each player's tab list shows everyone on the network, and the list stays complete after a server switch.
- [ ] A chat line on one server shows in the proxy console as `[BSP-Spawn] <Name> message`, not `chat.type.text`.

## 2026-11-03: `<server_tag>`, server images in chat (BSP-Server Utilities request SU-VA1)

The new placeholder is a translatable component: key `bsp_server_utilities.server_image.<server in lower case>`, fallback `%s`, argument the grey `[BSP-Spawn]` text, white, font `bsp_server_utilities:server_tags`, hover from `chat.serverTagHover`. The bundled `chat.relayStaff` is `<server_tag> <line>`. Tests (12 new, 160 in all): `ServerTagTest` (every part of the component; it survives `GlobalTranslator.render`; the network's own `velocity.jar` renders it and writes the 1.20 JSON with `fallback` and the grey argument; the hover from `messages.toml`; an old `messages.toml` keeps its `relayStaff` and gets the bundled hover), `ChatRelayFeatureTest` (staff get the tag; the owner can put it in `chat.relay`; the console keeps `[BSP-Wild] <Steve> ...`) and `PlayersCommandTest` (`players.staffServer` takes it). `tools/check_proxy_api.sh` passes against the network's `velocity.jar`. **Nothing has been tried with a real client or with BSP-Server Utilities' images (SU-27).**

- [ ] Before installing: `tools/check_proxy_api.sh` prints `OK: the proxy has all of them.`
- [ ] In the proxy's `plugins/bsp_velocity_additions/messages.toml`: if `[chat]` has its own `relayStaff` line, change it to `"<server_tag> <line>"`; if there is no such line, nothing to do (a missing line uses the bundled default). Add `serverTagHover = "<white><server>"` only to change the hover. Never two `[chat]` headings. Restart the proxy.
- [ ] Before Server Utilities' images are on (or for a server with no image): a staff player on another server sees `[BSP-Spawn] <Name> message` with `[BSP-Spawn]` grey, as before. Hovering `[BSP-Spawn]` shows `BSP-Spawn` in white.
- [ ] With Server Utilities' images on: the staff player sees the server's image instead of `[BSP-Spawn]`, in its own colours (not tinted grey), one line high, and the same hover.
- [ ] A player without `players.serversPermission` sees the line with no tag, as before.
- [ ] The proxy console still shows `[BSP-Spawn] <Name> message`; Discord messages still name the server as text.
- [ ] Optional: put `<server_tag>` in `players.staffServer` (for example `"<server_tag> <gray>(<count>):</gray> <names>"`), restart, `/players` as staff shows the images; the console's `/players` shows `[BSP-Spawn]`.

## 2026-11-07: bug B6, a new rank shows without a relog

The proxy reloads each online player's LuckPerms user from storage every `tabList.rankRefreshSeconds` (30), the same reload LuckPerms does when another server announces a change. Tests (5 new, 165 in all): `RankRefresherTest` (a rank changed in storage shows in another player's tab list after the next reload and not before; only players on a server are reloaded; the LuckPerms side reads the cached prefix and reloads with `UserManager.loadUser`; a failing reload is quiet) and `PluginConfigTest.readsRankRefreshSeconds`. Three of the `RankRefresherTest` tests failed with the reload taken out. `tools/check_proxy_api.sh` passes. **Untested on the live proxy.**

- [ ] Restart the proxy with the new jar: a `Ranks: reloaded from LuckPerms every 30 s` line after `LuckPerms found on the proxy`.
- [ ] Two players online on different servers. Rank one of them up with BSP-RankUp (or SET RANK in its admin screen): within 30 s both players' tab lists show the new prefix, with no relog. `/players` shows it too.
- [ ] The same with both players on the same server.
- [ ] Set `rankRefreshSeconds = 0` under `[tabList]`, restart: no `Ranks:` line, and a rank change shows only after a relog again (as before). Set it back to 30.
- [ ] Once BSP-RankUp 0.6.0 (VA-RU1, TELL OTHER SERVERS on in its OPTIONS) is on the backends and each backend's `config/luckperms/luckperms.conf` uses `messaging-service = "redis"` (or `"auto"` with `redis.enabled = true`) on the proxy's Redis: a rank-up shows in `/players` on the proxy within a second or two instead of up to 30 s. RankUp's OPTIONS info box should read `LuckPerms messaging: redis`.

## 2026-11-07: BSP-RankUp's names in the tab list (RankUp request RU-R3, task T23)

Option A of `tools/preview/rankup_tab_names_mockups.html`: a name a backend gives is left alone; a player on another server gets the name their own backend gives them (read from their own entry in their own tab list, which Velocity keeps up to date from the backend's packets, checked in the network's `velocity.jar`: `VelocityTabList.processUpsert` stores `UPDATE_DISPLAY_NAME`); `tabList.entry` only where a backend gives none, and a name the plugin set is remembered so it is never mistaken for the backend's. Tests: `TabListNamesTest` (7, against a tab list that keeps names like the proxy's; 4 failed against the old code), 172 in all. `tools/check_proxy_api.sh` passes. **Untested on the live proxy and with RankUp's images on a second server.**

- [ ] Two players on BSP-Spawn and one on another server, RankUp's prefix display on. Every row in every player's tab list shows RankUp's look: the rank image (or RankUp's text prefix for a rank without one) and any suffix, for players on the same server and on other servers.
- [ ] The images of a player on another server draw correctly (not as `[Miner]` text or a box), which shows RankUp sends every client the whole image set.
- [ ] Rank the player on the other server up: within about 5 s (RankUp's redraw) plus 3 s (the proxy's refresh), the new image shows in the tab lists on BSP-Spawn too.
- [ ] Switch servers a few times: the names stay right after each switch, and nobody is listed twice.
- [ ] Turn RankUp's prefix display off in its OPTIONS: within a few seconds every row shows the proxy's text form (`[Miner] Alex`) instead. Turn it on again: RankUp's look comes back.
- [ ] `/players` still lists names with text prefixes.
