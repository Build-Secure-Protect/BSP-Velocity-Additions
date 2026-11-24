# BSP-Velocity Additions API

`API_VERSION`: 0 (no public API yet).

Other Velocity plugins will find the public surface under `com.mrgregles.bsp_velocity_additions.api` once it exists. Everything outside that package may change without notice.

Backends (the BSP Forge mods) do not call this plugin's Java API. They talk to it through plugin messages, Redis or MySQL.

## Channels the proxy listens on

| Channel | From | Contract | Used for |
|---|---|---|---|
| `bsp_core:proxy` | BSP-Core on a backend, through the speaking player's connection | BSP-Core `docs/API.md`, "Proxy relay: bsp_core:proxy" (version 1, kind `chat`) | shared chat |
| `bsp_server_utilities:proxy` | BSP-Server Utilities on a backend, through the kicked player's connection | below (version 1, kind `kick`; request SU-VA2) | network kicks |

## Redis channels the proxy listens on

| Channel (config key) | From | Message | Used for |
|---|---|---|---|
| `bsp_core:discord` (`discord.redisChannel`) | BSP-Core | `docs/DISCORD.md` | the Discord bot |
| `bsp_core` (`playerReset.redisChannel`, must equal BSP-Core's `storage.redis channel`) | BSP-Core's `/bsp player reset` | `player_reset:<originServerId>:<uuid>\|<resetMs>\|<by>\|<name>` (BSP-Core's `<kind>:<origin>:<payload>`) | forget the player's last server (PR21) |
| `bsp_core` (the same subscription) | BSP-Core's `/bsp season reset` (`NetworkStorage.seasonResetPublish`) | `season_reset:<originServerId>:<resetNumber>\|<season>` (older BSP-Core: no `\|<season>`) | forget every last server and disconnect every player (D45) |

On `bsp_core` every other kind is ignored. A `player_reset` is read anchored on the uuid, so `originServerId` may contain `:`; one whose uuid or `resetMs` (a positive number, epoch ms) does not parse is ignored with a warning. Only a `last-servers.json` entry last changed before `resetMs` is forgotten, so a late or repeated notice is harmless. The proxy publishes nothing there. Pub/sub keeps nothing: a reset sent while the proxy or Redis is down does not reach the proxy.

A `season_reset` carries BSP-Core's reset number (`resetEpoch`, a positive whole number that goes up by one with each season reset). The proxy applies a reset only when its number is higher than the last one it applied, kept in `season-reset.json` in the data folder, so the same notice twice, or an older one, does nothing, across restarts too. Applying it forgets every `last-servers.json` entry last changed before the proxy heard it, then disconnects every player on the network with `seasonReset.kick` from `messages.toml`. `<season>` is the season that is starting (`NetworkStorage.seasonResetPayload`, for example `3|12`) and fills `<season>` in `seasonReset.kick`; a BSP-Core from before it sends the reset number alone, and the proxy then uses `seasonReset.kickNoNumber`, which needs no number. The repeat check reads the reset number only. Fields after the season are ignored, and a second field that is not a number means no number; one whose reset number does not parse is ignored with a warning, as is a reset number lower than the last applied. Needs `[playerReset] enabled` (the same subscription) and `[seasonReset] enabled`; with `lastServer.enabled = false` players are still disconnected.

Bytes on these channels from a client are dropped, and nothing on them is forwarded. Unknown versions and kinds are ignored, so the sending mod may add kinds later.

### `bsp_server_utilities:proxy`, version 1

FriendlyByteBuf encodings: version (byte, 1), kind (Utf: VarInt byte length then UTF-8), then for kind `kick`: the player (UUID as two big-endian longs) and the reason (Utf, Component JSON as `Component.Serializer.toJson` writes it).

`kick`: the proxy disconnects that player from the network with the reason (no failover to another server) and logs `[server] kicked <name>: <plain reason>`. A player who is no longer online is ignored. Reason JSON it cannot read becomes vanilla's `multiplayer.disconnect.kicked` ("Kicked by an operator"). `[networkKick] enabled = false` in `config.toml` switches the kick off; the channel stays registered and its messages are dropped unread (never passed on to the client), and the sender should then kick the player itself (Server Utilities does so after 3 s).
