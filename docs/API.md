# BSP-Velocity Additions API

`API_VERSION`: 0 (no public API yet).

Other Velocity plugins will find the public surface under `com.mrgregles.bsp_velocity_additions.api` once it exists. Everything outside that package may change without notice.

Backends (the BSP Forge mods) do not call this plugin's Java API. They talk to it through plugin messages, Redis or MySQL.

## Channels the proxy listens on

| Channel | From | Contract | Used for |
|---|---|---|---|
| `bsp_core:proxy` | BSP-Core on a backend, through the speaking player's connection | BSP-Core `docs/API.md`, "Proxy relay: bsp_core:proxy" (version 1, kind `chat`) | shared chat |

Bytes on that channel from a client are dropped. Unknown versions and kinds are ignored, so BSP-Core may add kinds later.
