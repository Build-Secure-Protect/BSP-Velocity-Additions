# The Fourthwall store

BSP-Velocity Additions receives Fourthwall's signed webhooks, works out which player and which rewards an order or membership is for, and writes **reward rows** into the shared MariaDB database `bsp_shop`. **BSP-Server Shop** on every game server picks the rows up and runs their commands as the server console, once. This is BSP-Server Shop's request SH-R1 (decisions D38 to D41 in the vault).

Nothing a buyer types ever becomes part of a command, except a username that Mojang has confirmed.

## How it fits together

```
Fourthwall ──HTTPS──> Cloudflare (store-hooks.buildsecureprotect.com)
                         │ tunnel
                         v
mc-main: cloudflared ──> 172.18.0.1:8765 ──(Pterodactyl allocation)──> proxy container 0.0.0.0:8765
                                                                        │ POST /fourthwall/webhook, signature checked
                                                                        v
                                                           shop_events ─> worker ─> shop_orders, shop_members,
                                                                                     shop_links, shop_rewards (PENDING)
                                                                                                   │
                                                          BSP-Server Shop on the game servers <────┘ delivers, once
```

- The receiver answers **200** as soon as the event is stored. It answers **401** for a missing or wrong `X-Fourthwall-Hmac-SHA256`, **404** for another path, **405** for another method, **413** for a body over 1 MB, and **500** when the database cannot store the event, so Fourthwall sends it again.
- Processing happens on one worker thread called "BSP Store", never on Velocity's own threads. The same worker runs the commands and the daily resync.
- Every row has a fixed `dedupe_key`, so a replayed webhook or a resync never writes a second row.
- The store only **inserts** PENDING rows. The only change it makes to an existing row is PENDING to CANCELLED. Delivered rows are BSP-Server Shop's.

**The allocation:** in Pterodactyl, the proxy has an extra allocation `127.0.0.1:8765`. Wings publishes an allocation written as `127.0.0.1` on its Docker network's gateway instead, which on mc-main is `172.18.0.1` (Wings' `docker.network.interface`). That address exists only on mc-main, so the tunnel's route is `http://172.18.0.1:8765` (checked live on 2026-11-20).

## Files (in `plugins/bsp_velocity_additions/`)

- **`store.toml`** holds the settings. It is written with comments the first time the proxy starts. `enabled = false` until you switch it on.
- **`store-rewards.toml`** says what each product and membership tier gives. A product is found by its variant's SKU or, when it has none (Fourthwall's digital products), by its slug: `/products/vip` becomes `[products."vip"]` (D42). A SKU the file knows wins over the slug. The bundled entries are examples (D41).
  - Every reward is checked when the file loads, and one with a mistake is left out with an error line in the console.
  - Mistakes it catches: a target server not in `velocity.toml` (names are compared exactly), an unknown placeholder, a line break or `§` in a command, a missing `displayName`.
  - LuckPerms groups named in `lp user … parent add|remove|set <group>` are checked against LuckPerms-Velocity.
- **`messages.toml`** holds the `[store]` and `[storeAdmin]` text.
- `/storeadmin reload` re-reads both store files. The sections marked "restart" in `store.toml` need a proxy restart.

## Secrets (never in a file)

The four values are read only from environment variables, set as egg variables on the proxy's Pterodactyl server, the same way as `docs/DISCORD.md` does for Discord. You add each variable under Admin → Nests → the proxy's egg → Variables, then type its value under Admin → Servers → the proxy → Startup.

| Variable | What it is |
|---|---|
| `BSP_FOURTHWALL_WEBHOOK_SECRET` | the secret key of the webhook in Fourthwall's For developers settings |
| `BSP_FOURTHWALL_API_USERNAME` | the Open API user's username (same page, Open API) |
| `BSP_FOURTHWALL_API_PASSWORD` | that user's password |
| `BSP_SHOP_DB_PASSWORD` | the `bsp_shop` database user's password (password manager: "bsp_shop DB") |

If any is missing, the proxy log names it (never a value) and the store stays off.

## Commands

**Players:**
- `/store` shows the help and the shop link.
- `/store link [email]` links the email you shop with to your account. It only works for an email that some order or membership was made with and that nobody else has linked. Linking assigns every order waiting on that email and grants its membership.
- `/store claim [order] [email]` claims one order. Both must match, and each order can be claimed once.
- `/store status` shows your orders and your latest reward rows.
- Five failed `link` or `claim` tries in ten minutes block further tries. A failure never says which part was wrong.

**Staff** (`bsp.shop.admin`, or the console):
- `/storeadmin lookup [player|order|email]`
- `/storeadmin assign [order] [player]` assigns a waiting order.
- `/storeadmin revoke [order]` takes an order back by hand after a refund or chargeback, as a Fourthwall cancellation would: its waiting rewards are cancelled, delivered ones that have `revoke` commands get a take-back row, and anything else is reported. Fourthwall cannot cancel a delivered digital order (D43), so this is the way for ranks. Running it twice does nothing more.
- `/storeadmin unlink [player]` removes the link. Membership rewards that came through the link are taken back; orders stay with the player.
- `/storeadmin grant [player] [reward]` writes one MANUAL row, from a product SKU or a tier id.
- `/storeadmin resync` and `/storeadmin reload`.

Emails are always shown masked, like `g***@domain.com`.

## What each event does

| Event | What happens |
|---|---|
| `ORDER_PLACED` | The order is stored. With a valid, existing Java username it is ASSIGNED and one row is written per unit (one row with `{quantity}` when `perQuantity = false`). Otherwise it is AWAITING_PLAYER, unless the email is linked. A product whose SKU and slug are both missing from `store-rewards.toml` gives nothing, and online staff are told. |
| `ORDER_UPDATED` | **Cancelled:** PENDING rows become CANCELLED. Delivered rows get a REVOKE row where the reward has `revoke` commands; for anything else staff are told it cannot be taken back. A row being delivered at that moment is waited for. **Email:** the stored email changes. Any other update is only stored. |
| `SUBSCRIPTION_PURCHASED` / `_CHANGED` | The member is stored. If their email is linked, the tier's rewards are applied (a tier change revokes the old tier and grants the new one). `CANCELLED` keeps the rewards. Anything in `revokeOn` (default `SUSPENDED`, `NONE`) takes them away. |
| `SUBSCRIPTION_EXPIRED` | Revokes. |
| Anything else | Stored and marked "ignored". |

- **Test events** (`testMode`) are processed only with `processTestEvents = true`, and their rows are test rows. BSP-Server Shop skips those unless `deliverTestRewards = true` on that server.
- **`captureOnly = true`** stores events and does nothing else. Setting it back and running `/storeadmin reload` processes what was stored.
- **Resync** runs two minutes after start, then every `intervalHours`. It reads orders from the last `orderLookbackDays` and every member from Fourthwall's API.
  - Missed orders are stored as `recon:<orderId>` and processed.
  - Known orders now cancelled are cancelled.
  - Members' rewards are brought in line.
  - An order already seen in a test-mode webhook is never treated as real.

## What real payloads showed (capture, 2026-11-20)

1. **The checkout name box fills `OrderV1.username`; the message box fills `message`.** Settled by a real order: the name typed went to `username`, the note to `message`.
2. **The name box cannot be made required.** Settled: Fourthwall offers no such option. A blank or wrong name leaves the order waiting for `/store claim` or `/store link`.
3. **Do membership renewals fire any event?** Open until memberships are sold. Assumed not.
4. **When a cancelled membership's paid period ends, is it EXPIRED, CHANGED or both?** Open until memberships are sold. Either works.
5. **`friendlyId` is eight capital letters and digits** (`Q3ZUA32R`). Settled.
6. **`apiVersion` is `V1`.** Settled; not read by the store.
7. **Do test orders appear in List Orders?** Fourthwall has no test-order mode, only a "Send test notification" with sample data. That is not a real order, so the question does not arise.

Also found:
- **Digital products** carry a SKU Fourthwall makes up itself (for example `P6YE-9BF0000`), which nobody can see or set. That is why products are matched on their slug when the SKU is unknown (D42).
- **A digital order is marked DELIVERED at once:** `ORDER_UPDATED` (update `STATUS`, order status `DELIVERED`) arrives in the same second as `ORDER_PLACED`, sometimes first. Whichever arrives first creates the order, and the other changes nothing.

## Known limits

- Anyone who knows a buyer's email can try `/store link` with it. The five-tries limit slows guessing, but an email is not a password.
- The store keeps nothing in memory that matters: everything is in `bsp_shop`, and events not processed before a restart are processed at the next start.
