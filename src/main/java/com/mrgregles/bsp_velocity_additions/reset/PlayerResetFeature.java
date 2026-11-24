package com.mrgregles.bsp_velocity_additions.reset;

import com.mrgregles.bsp_velocity_additions.config.PluginConfig;
import com.mrgregles.bsp_velocity_additions.discord.RespRedis;
import com.velocitypowered.api.proxy.Player;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * BSP-Core's {@code /bsp player reset} on the proxy (decision PR21): listens on BSP-Core's Redis channel and, for each
 * {@code player_reset} notice, forgets the player's last server so their next join goes to the fallback server like a
 * new player's. The Discord link ({@code discord-links.json}) and the store records ({@code bsp_shop}) are left alone.
 * Every other message on the channel, and anything malformed, is ignored. Doing one reset twice is harmless.
 * <p>
 * Redis pub/sub keeps nothing: a reset sent while the proxy or Redis is down never reaches the proxy.
 */
public final class PlayerResetFeature {

    /** Read first, so this listener can use a Redis user other than the Discord bot's. */
    public static final String PASSWORD_VARIABLE = "BSP_PLAYER_RESET_REDIS_PASSWORD";
    public static final String FALLBACK_PASSWORD_VARIABLE = "BSP_REDIS_PASSWORD";

    /** What the reset does on the proxy: {@code LastServerFeature::forget}. */
    @FunctionalInterface
    public interface Forgetter {
        /** @return the server that was forgotten; empty when nothing older than {@code resetMs} was on file */
        Optional<String> forget(UUID player, long resetMs, Optional<Player> session);
    }

    private final Forgetter lastServers;
    private final Function<UUID, Optional<Player>> online;
    private final Logger logger;
    private RespRedis redis;

    PlayerResetFeature(Forgetter lastServers, Function<UUID, Optional<Player>> online, Logger logger) {
        this.lastServers = lastServers;
        this.online = online;
        this.logger = logger;
    }

    /**
     * Starts listening. Empty when the feature is off, or when {@code lastServers} is null (the last-server feature
     * is off, so there is nothing to forget). The password is read here and passed on, never stored or logged.
     */
    public static Optional<PlayerResetFeature> start(PluginConfig.PlayerReset settings, Forgetter lastServers,
                                                     Function<UUID, Optional<Player>> online,
                                                     Function<String, String> environment,
                                                     Logger logger) {
        if (!settings.enabled()) {
            logger.info("playerReset.enabled is false; a player reset by BSP-Core keeps their last server");
            return Optional.empty();
        }
        if (lastServers == null) {
            logger.info("Player reset: lastServer.enabled is false, so there is no last server to forget; not listening");
            return Optional.empty();
        }
        String password = environment.apply(PASSWORD_VARIABLE);
        if (password == null || password.isBlank()) {
            password = environment.apply(FALLBACK_PASSWORD_VARIABLE);
        }
        if (password == null || password.isBlank()) {
            logger.warn("Player reset: neither {} nor {} is set; connecting to Redis without a password",
                    PASSWORD_VARIABLE, FALLBACK_PASSWORD_VARIABLE);
            password = "";
        }
        PlayerResetFeature feature = new PlayerResetFeature(lastServers, online, logger);
        feature.redis = new RespRedis(settings.redisHost(), settings.redisPort(), settings.redisUser(), password,
                logger, "Player reset", accessHint(settings));
        feature.redis.subscribe(settings.redisChannel(), feature::onRedisMessage, () -> { });
        logger.info("Player reset: on, connecting to Redis {}:{} for BSP-Core's resets on channel {}",
                settings.redisHost(), settings.redisPort(), settings.redisChannel());
        return Optional.of(feature);
    }

    /** One message from the channel, on the Redis thread. True when it was a reset and was applied. */
    boolean onRedisMessage(String text) {
        Optional<PlayerResetMessage> parsed = PlayerResetMessage.parse(text);
        if (parsed.isEmpty()) {
            if (PlayerResetMessage.isResetKind(text)) {
                logger.warn("Player reset: ignored a player_reset notice that could not be read: {}", clean(text));
            }
            return false;
        }
        PlayerResetMessage reset = parsed.get();
        Optional<Player> session = online.apply(reset.player());
        boolean isOnline = session.isPresent();
        Optional<String> forgotten = lastServers.forget(reset.player(), reset.resetMs(), session);
        String who = reset.name().isBlank() ? reset.player().toString() : clean(reset.name());
        String by = clean(reset.by());
        String origin = clean(reset.origin());
        if (forgotten.isPresent()) {
            logger.info("Player reset: forgot {}'s last server {} (reset by {} on {}){}", who, forgotten.get(),
                    by, origin, isOnline ? "; not remembered again until they leave the network" : "");
        } else {
            logger.info("Player reset: {} has no last server from before the reset on file; nothing to forget (reset by {} on {})",
                    who, by, origin);
        }
        return true;
    }

    /** Names the settings to fix when Redis refuses this listener's user. */
    static String accessHint(PluginConfig.PlayerReset settings) {
        return "set [playerReset] redisUser" + (settings.redisUser().isEmpty() ? "" : " (now " + settings.redisUser() + ")")
                + " and " + PASSWORD_VARIABLE + " to a Redis user that may subscribe to " + settings.redisChannel()
                + "; with redisUser empty the [discord] user is used, and BSP-Core recommends limiting that one to its own channel";
    }

    /** Text from Redis goes into the log: no line breaks or other control characters, at most 64 characters. */
    static String clean(String text) {
        String flat = text.replaceAll("\\p{Cntrl}", "?");
        return flat.length() > 64 ? flat.substring(0, 64) + "..." : flat;
    }

    public void stop() {
        if (redis != null) {
            redis.close();
        }
    }
}
