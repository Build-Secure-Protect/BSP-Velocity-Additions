# BSP-Velocity Additions API

`API_VERSION`: 0 (no public API yet).

Other Velocity plugins will find the public surface under `com.mrgregles.bsp_velocity_additions.api` once it exists. Everything outside that package may change without notice.

Backends (the BSP Forge mods) do not call this plugin's Java API. They talk to it through plugin messages, Redis or MySQL.

## Channels the proxy listens on

| Channel | From | Contract | Used for |
|---|---|---|---|
| `bsp_core:proxy` | BSP-Core on a backend, through the speaking player's connection | BSP-Core `docs/API.md`, "Proxy relay: bsp_core:proxy" (version 1, kind `chat`) | shared chat |
| `bsp_server_utilities:proxy` | BSP-Server Utilities on a backend, through the kicked player's connection | below (version 1, kind `kick`; request SU-VA2) | network kicks |

Bytes on these channels from a client are dropped, and nothing on them is forwarded. Unknown versions and kinds are ignored, so the sending mod may add kinds later.

### `bsp_server_utilities:proxy`, version 1

FriendlyByteBuf encodings: version (byte, 1), kind (Utf: VarInt byte length then UTF-8), then for kind `kick`: the player (UUID as two big-endian longs) and the reason (Utf, Component JSON as `Component.Serializer.toJson` writes it).

`kick`: the proxy disconnects that player from the network with the reason (no failover to another server) and logs `[server] kicked <name>: <plain reason>`. A player who is no longer online is ignored. Reason JSON it cannot read becomes vanilla's `multiplayer.disconnect.kicked` ("Kicked by an operator"). `[networkKick] enabled = false` in `config.toml` switches the kick off; the channel stays registered and its messages are dropped unread (never passed on to the client), and the sender should then kick the player itself (Server Utilities does so after 3 s).
