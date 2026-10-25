package com.mrgregles.bsp_velocity_additions.discord;

/**
 * What the bot asks of Discord. Blocking; called only on the bot's own thread. {@link JdaDiscord} is the real one
 * and the only class that knows JDA; the tests use a fake. An implementation never waits out a rate limit itself:
 * it answers {@link Result.RateLimited} and the bot decides whether a retry still makes sense.
 */
public interface DiscordPort {

    /** How one send went. */
    sealed interface Result {
        /** Delivered. */
        record Sent() implements Result {
        }

        /** Discord answered 429; it may be tried again after {@code retryAfterMillis}. */
        record RateLimited(long retryAfterMillis) implements Result {
        }

        /** The user cannot get a direct message from the bot: DMs closed (50007), left the server, or 403. */
        record Undeliverable() implements Result {
        }

        /** Anything else (Discord down, a network error, missing permissions). {@code reason} is safe to log. */
        record Failed(String reason) implements Result {
        }
    }

    Result sendDirect(long discordUser, OutgoingMessage message);

    Result postInChannel(long channelId, OutgoingMessage message);
}
