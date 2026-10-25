package com.mrgregles.bsp_velocity_additions.discord;

import com.mrgregles.bsp_velocity_additions.config.Messages;
import com.mrgregles.bsp_velocity_additions.config.PluginConfig;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Starts and stops the BSP Discord bot (BSP-Core request VA-D1). Off unless {@code discord.enabled = true}, the
 * environment variable {@code BSP_DISCORD_TOKEN} is set and {@code discord.guildId} is filled in; each missing piece
 * gives one log line and the bot stays off. The token and {@code BSP_REDIS_PASSWORD} are read here and passed on,
 * never stored in a field of this class, never logged.
 */
public final class DiscordFeature {

    public static final String TOKEN_VARIABLE = "BSP_DISCORD_TOKEN";
    public static final String REDIS_PASSWORD_VARIABLE = "BSP_REDIS_PASSWORD";

    private final ScheduledExecutorService thread;
    private final LinkStore links;
    private final RespRedis redis;
    private final JdaDiscord discord;

    private DiscordFeature(ScheduledExecutorService thread, LinkStore links, RespRedis redis, JdaDiscord discord) {
        this.thread = thread;
        this.links = links;
        this.redis = redis;
        this.discord = discord;
    }

    /**
     * Checks the settings and the environment, then starts the bot. Empty when the bot stays off. The checks come
     * before any JDA class is touched, so a proxy without the bot never loads JDA.
     */
    public static Optional<DiscordFeature> start(PluginConfig.Discord settings, Messages messages, Path dataDirectory,
                                                 Function<String, String> environment, Logger logger) {
        if (!settings.enabled()) {
            logger.info("discord.enabled is false; the Discord bot is off");
            return Optional.empty();
        }
        String token = environment.apply(TOKEN_VARIABLE);
        if (token == null || token.isBlank()) {
            logger.warn("The Discord bot is on in config.toml but {} is not set on this machine; the bot stays off", TOKEN_VARIABLE);
            return Optional.empty();
        }
        if (settings.guildId() == 0L) {
            logger.warn("discord.guildId is empty or not a number; the Discord bot stays off");
            return Optional.empty();
        }
        String redisPassword = environment.apply(REDIS_PASSWORD_VARIABLE);
        if (redisPassword == null || redisPassword.isBlank()) {
            logger.warn("{} is not set; connecting to Redis without a password", REDIS_PASSWORD_VARIABLE);
            redisPassword = "";
        }
        if (!settings.hasAlertChannel()) {
            logger.warn("discord.alertChannelId is empty; the public steal line is off");
        }

        ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BSP Herald");
            t.setDaemon(true);
            return t;
        });
        Delayer delayer = (millis, task) -> thread.schedule(guard(task, logger), Math.max(0L, millis), TimeUnit.MILLISECONDS);
        LinkStore links = new LinkStore(dataDirectory.resolve(LinkStore.FILE_NAME), thread, logger);
        RespRedis redis = new RespRedis(settings.redisHost(), settings.redisPort(), settings.redisUser(), redisPassword, logger);
        JdaDiscord discord;
        try {
            discord = JdaDiscord.start(token, settings.guildId(), logger);
        } catch (RuntimeException e) {
            // JDA's messages never contain the token; the class name is enough to tell a bad token from no network.
            logger.error("The Discord bot could not log in ({}); check {}", e.getClass().getSimpleName(), TOKEN_VARIABLE);
            thread.shutdownNow();
            return Optional.empty();
        }
        HeraldBot bot = new HeraldBot(links, redis, discord, new HeraldMessages(messages), delayer, System::currentTimeMillis,
                logger, settings.redisChannel(), settings.alertChannelId(), settings.wrongCodesPerTenMinutes());
        discord.attach(bot);
        redis.subscribe(settings.redisChannel(), bot::onRedisMessage, bot::onRedisReady);
        logger.info("Discord bot: starting; {} link(s) on file, Redis {}:{}, channel {}", links.size(),
                settings.redisHost(), settings.redisPort(), settings.redisChannel());
        return Optional.of(new DiscordFeature(thread, links, redis, discord));
    }

    public void stop() {
        redis.close();
        discord.shutdown();
        thread.shutdown();
        try {
            thread.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        links.flush();
    }

    private static Runnable guard(Runnable task, Logger logger) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                logger.warn("Herald: a task failed ({})", e.getClass().getSimpleName());
            }
        };
    }
}
