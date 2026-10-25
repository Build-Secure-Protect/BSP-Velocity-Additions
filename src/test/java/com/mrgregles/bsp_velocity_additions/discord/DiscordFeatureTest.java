package com.mrgregles.bsp_velocity_additions.discord;

import com.mrgregles.bsp_velocity_additions.config.Messages;
import com.mrgregles.bsp_velocity_additions.config.PluginConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The bot stays off, with one line saying why, unless it is switched on and the token is in the environment. */
class DiscordFeatureTest {

    static PluginConfig.Discord on(long guild) {
        return new PluginConfig.Discord(true, guild, 5L, "127.0.0.1", 6379, "", "bsp_core:discord", 5);
    }

    @Test
    void offByDefault(@TempDir Path dir) {
        Logger logger = mock(Logger.class);

        assertTrue(DiscordFeature.start(PluginConfig.Discord.DEFAULTS, Messages.defaults(), dir, Map.<String, String>of()::get, logger).isEmpty());
        verify(logger).info("discord.enabled is false; the Discord bot is off");
    }

    @Test
    void withoutTheTokenVariableTheBotStaysOffAndTouchesNothing(@TempDir Path dir) {
        Logger logger = mock(Logger.class);

        assertTrue(DiscordFeature.start(on(1L), Messages.defaults(), dir, Map.of("BSP_REDIS_PASSWORD", "pw")::get, logger).isEmpty());
        assertTrue(DiscordFeature.start(on(1L), Messages.defaults(), dir, Map.of("BSP_DISCORD_TOKEN", "  ")::get, logger).isEmpty());

        ArgumentCaptor<String> line = ArgumentCaptor.forClass(String.class);
        verify(logger, org.mockito.Mockito.times(2)).warn(line.capture(), any(Object.class));
        assertTrue(line.getValue().contains("is not set on this machine"));
        assertFalse(Files.exists(dir.resolve(LinkStore.FILE_NAME)));
    }

    @Test
    void withoutAGuildIdTheBotStaysOffAndTheTokenIsNeverLogged(@TempDir Path dir) {
        Logger logger = mock(Logger.class);

        assertTrue(DiscordFeature.start(on(0L), Messages.defaults(), dir, Map.of("BSP_DISCORD_TOKEN", "secret-token")::get, logger).isEmpty());

        verify(logger).warn("discord.guildId is empty or not a number; the Discord bot stays off");
        org.mockito.Mockito.mockingDetails(logger).getInvocations().forEach(i ->
                assertFalse(java.util.Arrays.deepToString(i.getArguments()).contains("secret-token")));
    }
}
