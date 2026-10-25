package com.mrgregles.bsp_velocity_additions.discord;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * The BSP Discord bot's rules (BSP-Core request VA-D1), free of JDA and of sockets so every rule runs in a unit test.
 * <ul>
 *   <li>Alerts from BSP-Core arrive as JSON on {@code bsp_core:discord}: the owner's DMs go to the Discord user linked
 *   to the totem owner's Minecraft account, and only to them; nothing is sent for a player with no link (decision 15).
 *   The public line goes to {@code discord.alertChannelId} with the server's name and nothing else.</li>
 *   <li>{@code /link CODE} takes the code with {@code GETDEL} (single use), links the Discord user who ran the command
 *   (Discord says who; nothing typed is trusted) and sets the {@code linked} flag BSP-Core reads.</li>
 *   <li>Limits (decision 17): at most {@link #MAX_TRIES} tries per message, Discord's 429 wait honoured, a warning
 *   dropped once its steal is over, an outcome or public line dropped after {@link #SEND_WINDOW_MS}; failures logged
 *   once until a send works again.</li>
 *   <li>Privacy (decision 11): a Discord id is never logged, never written to Redis, never shown to anyone.</li>
 * </ul>
 * Everything runs on the bot's own thread ({@link Delayer}); the proxy's threads are never used.
 */
public final class HeraldBot {

    static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final Pattern CODE = Pattern.compile("[" + CODE_ALPHABET + "]{6}");
    static final int MAX_TRIES = 3;
    static final long SEND_WINDOW_MS = 60_000L;
    static final long RETRY_PAUSE_MS = 2_000L;
    static final long UNDELIVERED_SECONDS = 604_800L;
    static final long WRONG_CODE_WINDOW_MS = 600_000L;

    /** Where a message goes. A direct message names the Minecraft account it is about, for logs and the undelivered flag. */
    private sealed interface Target {
        record Direct(long discordUser, UUID player, String name, boolean flagUndelivered) implements Target {
        }

        record Channel(long channelId) implements Target {
        }
    }

    private final LinkStore links;
    private final KeyValueStore redis;
    private final DiscordPort discord;
    private final HeraldMessages messages;
    private final Delayer delayer;
    private final LongSupplier clock;
    private final Logger logger;
    private final String keyPrefix;
    private final long alertChannelId;
    private final int wrongCodeLimit;

    private final Map<Long, Deque<Long>> wrongCodes = new HashMap<>();
    private final Set<UUID> pendingFlagDeletes = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedProblems = ConcurrentHashMap.newKeySet();

    public HeraldBot(LinkStore links, KeyValueStore redis, DiscordPort discord, HeraldMessages messages, Delayer delayer,
                     LongSupplier clock, Logger logger, String redisChannel, long alertChannelId, int wrongCodeLimit) {
        this.links = links;
        this.redis = redis;
        this.discord = discord;
        this.messages = messages;
        this.delayer = delayer;
        this.clock = clock;
        this.logger = logger;
        this.keyPrefix = redisChannel;
        this.alertChannelId = alertChannelId;
        this.wrongCodeLimit = wrongCodeLimit;
    }

    // ------------------------------------------------------------------ from Redis

    /**
     * One message from the Redis channel, called on the subscriber's thread. The time of arrival is taken here: a
     * warning's deadline counts from it rather than from {@code sentAt}, so a game server whose clock is a little off
     * cannot make the bot drop live warnings. Pub/sub delivers within milliseconds, so the two agree in practice.
     */
    public void onRedisMessage(String json) {
        long receivedAt = clock.getAsLong();
        delayer.now(() -> handle(json, receivedAt));
    }

    /** Redis is (again) connected: put back every {@code linked} flag, in case Redis lost them, and finish any deletes. */
    public void onRedisReady() {
        delayer.now(this::restoreFlags);
    }

    void handle(String json, long receivedAt) {
        Optional<Alert> parsed = Alert.parse(json);
        if (parsed.isEmpty()) {
            return;
        }
        Alert alert = parsed.get();
        if (alert instanceof Alert.Warning w) {
            directToOwner(w.player(), messages.warning(w), receivedAt + w.secondsLeft() * 1000L);
        } else if (alert instanceof Alert.Saved s) {
            directToOwner(s.player(), messages.saved(s), receivedAt + SEND_WINDOW_MS);
        } else if (alert instanceof Alert.Stolen s) {
            directToOwner(s.player(), messages.stolen(s), receivedAt + SEND_WINDOW_MS);
        } else if (alert instanceof Alert.Channel c) {
            if (alertChannelId == 0L) {
                problem("no-channel", "discord.alertChannelId is empty; the public steal line is not posted");
                return;
            }
            attempt(new Target.Channel(alertChannelId), messages.channel(c), receivedAt + SEND_WINDOW_MS, 1);
        } else if (alert instanceof Alert.Unlink u) {
            pendingFlagDeletes.remove(u.player());
            links.remove(u.player()).ifPresent(l -> logger.info("Herald: {} unlinked their Discord account in game", l.name()));
        }
    }

    private void directToOwner(UUID player, OutgoingMessage message, long deadline) {
        links.get(player).ifPresent(l ->
                attempt(new Target.Direct(l.discordUser(), player, l.name(), true), message, deadline, 1));
    }

    private void restoreFlags() {
        try {
            for (UUID player : Set.copyOf(pendingFlagDeletes)) {
                redis.delete(linkedKey(player));
                pendingFlagDeletes.remove(player);
            }
            for (LinkStore.Link l : links.all()) {
                redis.set(linkedKey(l.player()), "1");
            }
            solved("redis");
            logger.info("Herald: {} Discord link(s) marked in Redis", links.size());
        } catch (IOException e) {
            problem("redis", "Herald could not reach Redis ({}); links are marked again when it answers", e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ slash commands (bot thread)

    /** {@code /link CODE}, run by the Discord user {@code discordUser}. The answer is shown to that user only. */
    public OutgoingMessage link(long discordUser, String typed) {
        long now = clock.getAsLong();
        if (tooManyWrongCodes(discordUser, now)) {
            return messages.tooManyTries();
        }
        String code = typed == null ? "" : typed.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            wrongCode(discordUser, now);
            return messages.linkInvalid();
        }
        Optional<String> stored;
        try {
            stored = redis.getDel(keyPrefix + ":code:" + code);
            solved("redis");
        } catch (IOException e) {
            problem("redis", "Herald could not reach Redis ({}); /link answers 'try again soon'", e.getClass().getSimpleName());
            return messages.unavailable();
        }
        Optional<LinkCode> parsed = stored.flatMap(v -> LinkCode.parse(v, now));
        if (parsed.isEmpty()) {
            wrongCode(discordUser, now);
            return messages.linkInvalid();
        }
        LinkCode c = parsed.get();
        wrongCodes.remove(discordUser);
        Optional<LinkStore.Link> replaced = links.link(c.player(), c.name(), discordUser, now);
        pendingFlagDeletes.remove(c.player());
        setFlag(c.player());
        logger.info("Herald: {} linked a Discord account", c.name());
        if (replaced.isPresent() && replaced.get().discordUser() != discordUser) {
            long previous = replaced.get().discordUser();
            delayer.now(() -> attempt(new Target.Direct(previous, c.player(), c.name(), false),
                    messages.replaced(c.name()), now + SEND_WINDOW_MS, 1));
        }
        delayer.now(() -> attempt(new Target.Direct(discordUser, c.player(), c.name(), true),
                messages.testDm(c.name()), now + SEND_WINDOW_MS, 1));
        return messages.linked(c.name());
    }

    /**
     * {@code /unlink name}: ends the link of that one Minecraft account, if it is this Discord user's (D25: a name is
     * always needed, so nobody unlinks everything by accident). Without a name the answer lists the user's accounts.
     */
    public OutgoingMessage unlink(long discordUser, String name) {
        List<LinkStore.Link> mine = links.linksOf(discordUser);
        if (mine.isEmpty()) {
            return messages.notLinked();
        }
        String wanted = name == null ? "" : name.trim();
        if (wanted.isEmpty()) {
            return messages.status(mine.stream().map(LinkStore.Link::name).toList());
        }
        List<LinkStore.Link> chosen = mine.stream().filter(l -> l.name().equalsIgnoreCase(wanted)).toList();
        if (chosen.isEmpty()) {
            return messages.unlinkNoMatch(wanted);
        }
        for (LinkStore.Link l : chosen) {
            links.remove(l.player());
            clearFlag(l.player());
            logger.info("Herald: {} unlinked their Discord account in Discord", l.name());
        }
        return messages.unlinked(chosen.stream().map(LinkStore.Link::name).toList());
    }

    /** {@code /linkstatus}: only the caller's own Minecraft names. */
    public OutgoingMessage status(long discordUser) {
        List<LinkStore.Link> mine = links.linksOf(discordUser);
        if (mine.isEmpty()) {
            return messages.notLinked();
        }
        return messages.status(mine.stream().map(LinkStore.Link::name).toList());
    }

    /** Runs a command on the bot's thread; for the Discord adapter. */
    public void submit(Runnable task) {
        delayer.now(task);
    }

    // ------------------------------------------------------------------ delivery

    private void attempt(Target target, OutgoingMessage message, long deadline, int tryNumber) {
        String kind = target instanceof Target.Direct ? "dm" : "channel";
        long now = clock.getAsLong();
        if (now >= deadline) {
            problem(kind + ":late", "Herald dropped a {} that was too late to matter", describe(kind));
            return;
        }
        DiscordPort.Result result;
        if (target instanceof Target.Direct d) {
            result = discord.sendDirect(d.discordUser(), message);
        } else {
            result = discord.postInChannel(((Target.Channel) target).channelId(), message);
        }
        if (result instanceof DiscordPort.Result.Sent) {
            solved(kind + ":failed");
            solved(kind + ":late");
            solved(kind + ":ratelimit");
            if (target instanceof Target.Direct d) {
                solved("undeliverable:" + d.player());
            }
        } else if (result instanceof DiscordPort.Result.RateLimited r) {
            long wait = Math.max(0L, r.retryAfterMillis());
            if (tryNumber < MAX_TRIES && now + wait < deadline) {
                delayer.after(wait, () -> attempt(target, message, deadline, tryNumber + 1));
            } else {
                problem(kind + ":ratelimit", "Herald dropped a {}: Discord's rate limit outlasted it", describe(kind));
            }
        } else if (result instanceof DiscordPort.Result.Undeliverable && target instanceof Target.Direct d) {
            undeliverable(d);
        } else {
            String reason = result instanceof DiscordPort.Result.Failed f ? f.reason() : "UNDELIVERABLE";
            long wait = RETRY_PAUSE_MS * tryNumber;
            if (tryNumber < MAX_TRIES && now + wait < deadline) {
                delayer.after(wait, () -> attempt(target, message, deadline, tryNumber + 1));
            } else {
                problem(kind + ":failed", "Herald could not send a {} after {} tries ({}); further failures are not logged until one works",
                        describe(kind), tryNumber, reason);
            }
        }
    }

    /** Decision 15: nothing public; BSP-Core reads the flag at the player's next login and tells them. The link stays. */
    private void undeliverable(Target.Direct d) {
        if (!d.flagUndelivered()) {
            return;
        }
        try {
            redis.setExpiring(keyPrefix + ":undelivered:" + d.player(), "1", UNDELIVERED_SECONDS);
        } catch (IOException e) {
            problem("redis", "Herald could not reach Redis ({})", e.getClass().getSimpleName());
        }
        problem("undeliverable:" + d.player(), "Herald could not DM {} (DMs closed or not in the BSP Discord); they are told at their next login", d.name());
    }

    // ------------------------------------------------------------------ helpers

    private void setFlag(UUID player) {
        try {
            redis.set(linkedKey(player), "1");
        } catch (IOException e) {
            problem("redis", "Herald could not reach Redis ({}); links are marked again when it answers", e.getClass().getSimpleName());
        }
    }

    private void clearFlag(UUID player) {
        try {
            redis.delete(linkedKey(player));
        } catch (IOException e) {
            pendingFlagDeletes.add(player);
            problem("redis", "Herald could not reach Redis ({}); the unlink reaches the game when it answers", e.getClass().getSimpleName());
        }
    }

    private String linkedKey(UUID player) {
        return keyPrefix + ":linked:" + player;
    }

    private boolean tooManyWrongCodes(long discordUser, long now) {
        if (wrongCodeLimit <= 0) {
            return false;
        }
        Deque<Long> times = wrongCodes.get(discordUser);
        if (times == null) {
            return false;
        }
        while (!times.isEmpty() && times.peekFirst() <= now - WRONG_CODE_WINDOW_MS) {
            times.pollFirst();
        }
        if (times.isEmpty()) {
            wrongCodes.remove(discordUser);
            return false;
        }
        return times.size() >= wrongCodeLimit;
    }

    private void wrongCode(long discordUser, long now) {
        if (wrongCodeLimit > 0) {
            wrongCodes.computeIfAbsent(discordUser, k -> new ArrayDeque<>()).addLast(now);
        }
    }

    private static String describe(String kind) {
        return kind.equals("dm") ? "direct message" : "public steal line";
    }

    /** Logs a problem once; quiet until {@link #solved} clears it. Arguments never include a Discord id. */
    private void problem(String key, String format, Object... args) {
        if (loggedProblems.add(key)) {
            logger.warn(format, args);
        }
    }

    private void solved(String key) {
        loggedProblems.remove(key);
    }

    /** The value BSP-Core stores under {@code <channel>:code:<CODE>}. */
    record LinkCode(UUID player, String name) {

        static Optional<LinkCode> parse(String json, long now) {
            try {
                JsonElement e = JsonParser.parseString(json);
                if (!e.isJsonObject()) {
                    return Optional.empty();
                }
                JsonObject o = e.getAsJsonObject();
                if (!o.has("v") || o.get("v").getAsInt() != 1) {
                    return Optional.empty();
                }
                if (o.has("expiresAt") && o.get("expiresAt").getAsLong() <= now) {
                    return Optional.empty();
                }
                UUID player = UUID.fromString(o.get("player").getAsString());
                String name = o.has("name") ? o.get("name").getAsString() : "";
                return Optional.of(new LinkCode(player, name.isBlank() ? player.toString() : name));
            } catch (RuntimeException broken) {
                return Optional.empty();
            }
        }
    }
}
