# The BSP Discord bot (Herald)

BSP-Velocity Additions carries the BSP Discord bot. It runs once, on the Velocity proxy, and does four things:

- sends a totem's owner a direct message when their totem is being stolen, and a second one when the steal ends (only for totems with the Herald upgrade);
- posts **"A totem is being stolen on _server_"** in one channel of the BSP Discord for every steal, and nothing else;
- answers `/link`, `/unlink` and `/linkstatus` in the BSP Discord, so players connect their Minecraft account themselves;
- tells BSP-Core who is linked (yes or no only), through Redis.

The game servers never hold the bot's token or anyone's Discord id. Players never type a Discord name, password or token anywhere.

> **Status:** built and unit-tested against a fake Discord and a fake Redis. Nothing has been tried against the real Discord yet. The checklist is in `docs/TESTING.md`.

## What you need

- Owner access to the BSP Discord server.
- Access to the proxy's Pterodactyl panel (or the machine that runs Velocity) to set two environment variables.
- Redis 6.2 or newer (the bot uses `GETDEL`), the same Redis the backends use.
- BSP-Core with the Herald upgrade and `discord.enabled` on every backend (step 7).

## 1. Create the bot in the Discord Developer Portal

1. Open <https://discord.com/developers/applications> and click **New Application**. Name it, for example, **BSP Herald**. This name and icon are what players see in their DMs.
2. Open **Bot** in the left menu.
3. Under **Privileged Gateway Intents**, leave all three switches **off** (Presence, Server Members, Message Content). The bot reads no messages and needs none of them.
4. Turn **Public Bot** off, so only you can add it to a server.
5. Click **Reset Token**, then **Copy**. This is the bot's password. Do not paste it into Discord, a config file, a screenshot or a chat with anyone, including Claude. If it ever leaks, come back here and click **Reset Token** again; the old one stops working at once.

## 2. Put the token and the Redis password on the proxy machine

The plugin reads them only from two environment variables:

| Variable | Holds |
|---|---|
| `BSP_DISCORD_TOKEN` | the bot token from step 1 |
| `BSP_REDIS_PASSWORD` | the password of the Redis user in step 6 |

Neither is ever written to a file by the plugin or printed in the log.

**On Pterodactyl** (how the proxy runs today):

1. As a panel admin: **Admin → Nests → (the Velocity nest) → (the Velocity egg) → Variables → Create New Variable**.
   - Name: `Discord bot token`. Environment Variable: `BSP_DISCORD_TOKEN`. Default value: empty.
   - Untick **Users Can View** and **Users Can Edit**, so the value is visible to admins only.
   - Rules: `nullable|string`.
2. Do the same for `BSP_REDIS_PASSWORD`.
3. Open the proxy server → **Startup**, paste each value into its box, and restart the server.

**On a plain Linux service** (systemd): run `sudo systemctl edit velocity` and add

```ini
[Service]
EnvironmentFile=/etc/bsp/herald.env
```

then put `BSP_DISCORD_TOKEN=...` and `BSP_REDIS_PASSWORD=...` on two lines in `/etc/bsp/herald.env`, make it readable by root only (`sudo chmod 600 /etc/bsp/herald.env`), and restart the service.

## 3. Invite the bot to the BSP Discord

1. In the Developer Portal, open **OAuth2 → URL Generator**.
2. Scopes: tick **bot** and **applications.commands**.
3. Bot permissions: tick only **View Channels**, **Send Messages** and **Embed Links**. Do not tick Administrator or anything else.
4. Open the generated link, choose the BSP Discord, and click **Authorise**.
5. In the BSP Discord, if the alert channel is private, open the channel's settings → **Permissions** and give the bot's role View Channel, Send Messages and Embed Links there.

Players get DMs from the bot only while they are in the BSP Discord and allow direct messages from its members (Discord's default). Nobody needs to give the bot any other permission.

## 4. Copy the two ids

1. In Discord: **User Settings → Advanced → Developer Mode** on.
2. Right-click the BSP Discord's icon → **Copy Server ID**. That is `guildId`.
3. Right-click the alert channel (for example `#totem-alerts`) → **Copy Channel ID**. That is `alertChannelId`.

These ids are not secret; they only say where the bot works.

## 5. Fill in the plugin's config

In `plugins/bsp_velocity_additions/config.toml` on the proxy (a `config.toml` written by an older version has no `[discord]` section; copy it from the template in the jar or from the block below):

```toml
[discord]
enabled = true
guildId = "123456789012345678"        # step 4, keep the quotes
alertChannelId = "123456789012345679" # step 4; empty means no public line
redisHost = "192.168.1.25"
redisPort = 6379
redisUser = "herald"                  # step 6; empty means Redis's default user
redisChannel = "bsp_core:discord"     # must equal BSP-Core's discord.redisChannel
wrongCodesPerTenMinutes = 5
```

The wording of every message is in the `[discord]` section of `messages.toml`.

## 6. A Redis user for the bot (recommended)

Give the bot a Redis user that can only touch its own channel and keys. In `redis-cli`, as an admin:

```
ACL SETUSER herald on >CHOOSE-A-PASSWORD resetchannels &bsp_core:discord resetkeys ~bsp_core:discord:* -@all +subscribe +ping +getdel +set +del
ACL SAVE
```

Put that password in `BSP_REDIS_PASSWORD` (step 2) and `herald` in `redisUser`. If the backends' own Redis user is limited by an ACL too, it needs `&bsp_core:discord`, `~bsp_core:discord:*` and `+publish +set +del +exists +getdel` for BSP-Core's side.

## 7. Switch it on in the game

On every backend, in BSP-Core's server config: `discord.enabled = true`, `discord.serverName` set to the name players should see (for example `Frostwarf`), and network storage on (`storage.mode = "mysql"` with Redis enabled). BSP-Core's `docs/API.md`, section "Discord alerts", has the details.

## 8. Restart the proxy and check the log

A healthy start prints:

```
Discord bot: starting; 0 link(s) on file, Redis 192.168.1.25:6379, channel bsp_core:discord
Herald: listening on Redis 192.168.1.25:6379 channel bsp_core:discord
Herald: 0 Discord link(s) marked in Redis
Herald is logged in to Discord
Herald: /link, /unlink and /linkstatus are ready in the BSP Discord
```

| Line | Meaning and fix |
|---|---|
| `discord.enabled is false; the Discord bot is off` | step 5 |
| `... BSP_DISCORD_TOKEN is not set on this machine; the bot stays off` | step 2; restart after setting it |
| `discord.guildId is empty or not a number` | step 4; keep the quotes |
| `The Discord bot could not log in (InvalidTokenException)` | the token is wrong or was reset; step 1, then step 2 |
| `Herald is logged in but not in the server set as discord.guildId` | step 3, or the wrong id in step 4 |
| `Herald could not register its commands` | the invite lacked **applications.commands**; step 3 |
| `BSP_REDIS_PASSWORD is not set; connecting to Redis without a password` | fine only if your Redis has no password |
| `Herald: Redis at ... is not answering (WRONGPASS)` | the Redis user or password is wrong; step 6 |
| `Herald: Redis at ... is not answering (NOPERM)` | the Redis user lacks a permission; step 6 |
| `discord.alertChannelId is empty; the public steal line is off` | step 4, if you want the line |
| `Herald could not send a public steal line ... (MISSING_PERMISSION_...)` | the bot cannot post in the alert channel; step 3.5 |

## For players

1. In game, open the totem's Herald card and press **LINK**, or type `/bsp discord link`. A six-letter code appears, valid for 10 minutes.
2. In the BSP Discord, type `/link` and the code. Only you see the answer, and a test message arrives in your DMs.
3. `/linkstatus` shows which Minecraft accounts send their warnings to you; `/unlink` and a name stops the warnings for that account. The name is always needed, so nobody unlinks everything by accident. Unlinking in game works too.

## What is stored, and where

| What | Where | Who can see it |
|---|---|---|
| Each linked Minecraft account's Discord user id, Minecraft name and link time | `plugins/bsp_velocity_additions/discord-links.json` on the proxy (owner-only file permissions where supported) | whoever runs the proxy machine |
| "This player is linked" (no id) | Redis key `bsp_core:discord:linked:<uuid>` | BSP-Core, to show "linked" on Herald's card |
| "A warning could not reach this player" (no id), for 7 days | Redis key `bsp_core:discord:undelivered:<uuid>` | BSP-Core, to tell the player at their next login |

No Discord name, avatar or email is ever kept. A Discord user id is never written to a log, to Redis or to anything the game servers read, and never shown to anyone. Unlinking deletes it.
