package com.mrgregles.bsp_velocity_additions.discord;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.exceptions.RateLimitedException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Function;

/**
 * The only class that knows JDA (decision D20): logs the bot in, registers {@code /link}, {@code /unlink} and
 * {@code /linkstatus} in the BSP Discord, hands each command to {@link HeraldBot} on the bot's thread, and sends what
 * the bot asks. It asks for no gateway intents at all; JDA itself keeps the basic, unprivileged guild events it needs
 * to know the server's channels. No member, message or presence data (decision 16). Every message is sent with no
 * mentions allowed.
 *
 * <p>Untested against the real Discord until the owner runs it: the unit tests cover {@link HeraldBot} with a fake
 * Discord, not this adapter.
 */
final class JdaDiscord extends ListenerAdapter implements DiscordPort {

    private final long guildId;
    private final Logger logger;
    private volatile HeraldBot bot;
    private volatile JDA jda;

    private JdaDiscord(long guildId, Logger logger) {
        this.guildId = guildId;
        this.logger = logger;
    }

    /** Starts the login in the background. The token is used here and nowhere else. */
    static JdaDiscord start(String token, long guildId, Logger logger) {
        JdaDiscord adapter = new JdaDiscord(guildId, logger);
        adapter.jda = JDABuilder.createLight(token, EnumSet.noneOf(GatewayIntent.class))
                .setMemberCachePolicy(MemberCachePolicy.NONE)
                .addEventListeners(adapter)
                .build();
        return adapter;
    }

    void attach(HeraldBot bot) {
        this.bot = bot;
    }

    void shutdown() {
        JDA j = jda;
        if (j == null) {
            return;
        }
        j.shutdown();
        try {
            if (!j.awaitShutdown(Duration.ofSeconds(5))) {
                j.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            j.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public void onGuildReady(GuildReadyEvent event) {
        Guild guild = event.getGuild();
        if (guild.getIdLong() != guildId) {
            return;
        }
        guild.updateCommands().addCommands(
                Commands.slash("link", "Get Herald's steal warnings for your Minecraft account by direct message")
                        .addOption(OptionType.STRING, "code", "The six-character code from /bsp discord link in game", true)
                        .setContexts(InteractionContextType.GUILD),
                Commands.slash("unlink", "Stop Herald's steal warnings for one of your Minecraft accounts")
                        .addOption(OptionType.STRING, "name", "The Minecraft name to unlink (see /linkstatus)", true)
                        .setContexts(InteractionContextType.GUILD),
                Commands.slash("linkstatus", "See which Minecraft accounts send their steal warnings to you")
                        .setContexts(InteractionContextType.GUILD)
        ).queue(ok -> logger.info("Herald: /link, /unlink and /linkstatus are ready in the BSP Discord"),
                failure -> logger.warn("Herald could not register its commands ({}); was the bot invited with applications.commands?",
                        failure.getClass().getSimpleName()));
    }

    @Override
    public void onReady(net.dv8tion.jda.api.events.session.ReadyEvent event) {
        if (event.getJDA().getGuildById(guildId) == null) {
            logger.warn("Herald is logged in but not in the server set as discord.guildId; invite it there");
        } else {
            logger.info("Herald is logged in to Discord");
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        HeraldBot b = bot;
        if (b == null || event.getGuild() == null || event.getGuild().getIdLong() != guildId) {
            return;
        }
        long user = event.getUser().getIdLong();
        Function<HeraldBot, OutgoingMessage> work = switch (event.getName()) {
            case "link" -> h -> h.link(user, option(event, "code"));
            case "unlink" -> h -> h.unlink(user, option(event, "name"));
            case "linkstatus" -> h -> h.status(user);
            default -> null;
        };
        if (work == null) {
            return;
        }
        event.deferReply(true).queue(hook -> b.submit(() -> {
            OutgoingMessage answer = work.apply(b);
            hook.sendMessage(data(answer)).setEphemeral(true).queue(null,
                    failure -> logger.warn("Herald could not answer /{} ({})", event.getName(), failure.getClass().getSimpleName()));
        }));
    }

    private static String option(SlashCommandInteractionEvent event, String name) {
        OptionMapping o = event.getOption(name);
        return o == null ? "" : o.getAsString();
    }

    // ------------------------------------------------------------------ sending (bot thread)

    @Override
    public Result sendDirect(long discordUser, OutgoingMessage message) {
        JDA j = jda;
        if (j == null) {
            return new Result.Failed("NOT_LOGGED_IN");
        }
        try {
            j.openPrivateChannelById(discordUser).flatMap(channel -> channel.sendMessage(data(message))).complete(false);
            return new Result.Sent();
        } catch (RateLimitedException e) {
            return new Result.RateLimited(e.getRetryAfter());
        } catch (ErrorResponseException e) {
            ErrorResponse r = e.getErrorResponse();
            int status = e.getResponse() == null ? 0 : e.getResponse().code;
            if (r == ErrorResponse.CANNOT_SEND_TO_USER || r == ErrorResponse.UNKNOWN_USER || status == 403) {
                return new Result.Undeliverable();
            }
            return new Result.Failed(r.name());
        } catch (RuntimeException e) {
            return new Result.Failed(e.getClass().getSimpleName());
        }
    }

    @Override
    public Result postInChannel(long channelId, OutgoingMessage message) {
        JDA j = jda;
        if (j == null) {
            return new Result.Failed("NOT_LOGGED_IN");
        }
        GuildMessageChannel channel = j.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) {
            return new Result.Failed("CHANNEL_NOT_FOUND");
        }
        try {
            channel.sendMessage(data(message)).complete(false);
            return new Result.Sent();
        } catch (RateLimitedException e) {
            return new Result.RateLimited(e.getRetryAfter());
        } catch (InsufficientPermissionException e) {
            return new Result.Failed("MISSING_PERMISSION_" + e.getPermission().name());
        } catch (ErrorResponseException e) {
            return new Result.Failed(e.getErrorResponse().name());
        } catch (RuntimeException e) {
            return new Result.Failed(e.getClass().getSimpleName());
        }
    }

    /** The message for Discord, with no mentions allowed whatever the text holds. */
    static MessageCreateData data(OutgoingMessage message) {
        MessageCreateBuilder b = new MessageCreateBuilder().setAllowedMentions(List.of());
        if (!message.text().isBlank()) {
            b.setContent(message.text());
        }
        if (message.hasCard()) {
            OutgoingMessage.Card c = message.card();
            EmbedBuilder e = new EmbedBuilder().setColor(c.colour());
            if (!c.title().isBlank()) {
                e.setTitle(c.title());
            }
            if (!c.description().isBlank()) {
                e.setDescription(c.description());
            }
            for (OutgoingMessage.Field f : c.fields()) {
                e.addField(f.name(), f.value(), f.inline());
            }
            if (!c.footer().isBlank()) {
                e.setFooter(c.footer());
            }
            b.setEmbeds(e.build());
        }
        return b.build();
    }
}
