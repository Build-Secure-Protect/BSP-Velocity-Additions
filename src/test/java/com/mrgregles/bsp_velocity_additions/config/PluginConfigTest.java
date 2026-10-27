package com.mrgregles.bsp_velocity_additions.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class PluginConfigTest {

    @Test
    void writesTheTemplateAndReadsTheDefaults(@TempDir Path dir) {
        PluginConfig config = PluginConfig.load(dir.resolve("data"), mock(Logger.class));

        assertTrue(Files.exists(dir.resolve("data").resolve(PluginConfig.FILE_NAME)));
        assertEquals(PluginConfig.LastServer.DEFAULTS, config.lastServer());
        assertEquals(PluginConfig.JoinLeave.DEFAULTS, config.joinLeave());
        assertFalse(config.lastServer().hasFallbackServer());
    }

    @Test
    void readsTheJoinLeaveSection(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(PluginConfig.FILE_NAME), """
                [joinLeave]
                enabled = false
                silentPermission = ""
                """);

        PluginConfig.JoinLeave jl = PluginConfig.load(dir, mock(Logger.class)).joinLeave();

        assertFalse(jl.enabled());
        assertFalse(jl.hasSilentPermission());
    }

    @Test
    void readsCustomValuesAndLowersIgnoredNames(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(PluginConfig.FILE_NAME), """
                [lastServer]
                enabled = false
                ignoredServers = ["BSP-Lobby", "Games"]
                fallbackServer = " BSP-Spawn "
                pingSeconds = 0
                importVelocityRememberServer = false
                """);

        PluginConfig.LastServer ls = PluginConfig.load(dir, mock(Logger.class)).lastServer();

        assertFalse(ls.enabled());
        assertEquals(Set.of("bsp-lobby", "games"), ls.ignoredServers());
        assertTrue(ls.isIgnored("bsp-LOBBY"));
        assertEquals("BSP-Spawn", ls.fallbackServer());
        assertEquals(1, ls.pingSeconds(), "pingSeconds is clamped to at least 1");
        assertFalse(ls.importVelocityRememberServer());
    }

    @Test
    void aBrokenFileFallsBackToTheDefaults(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(PluginConfig.FILE_NAME), "[lastServer\nenabled = maybe");

        assertEquals(PluginConfig.defaults(), PluginConfig.load(dir, mock(Logger.class)));
    }

    @Test
    void aFileWithoutTheSectionUsesTheDefaults(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(PluginConfig.FILE_NAME), "# nothing here\n");

        assertEquals(PluginConfig.defaults(), PluginConfig.load(dir, mock(Logger.class)));
    }

    @Test
    void theDiscordBotIsOffByDefaultAndReadsItsSection(@TempDir Path dir) throws Exception {
        assertEquals(PluginConfig.Discord.DEFAULTS, PluginConfig.load(dir.resolve("fresh"), mock(Logger.class)).discord());
        assertFalse(PluginConfig.Discord.DEFAULTS.enabled());

        Files.writeString(dir.resolve(PluginConfig.FILE_NAME), """
                [discord]
                enabled = true
                guildId = "1100000000000000001"
                alertChannelId = 1100000000000000002
                redisHost = "192.168.1.25"
                redisPort = 6380
                redisUser = "herald"
                redisChannel = ""
                wrongCodesPerTenMinutes = -3
                """);
        PluginConfig.Discord d = PluginConfig.load(dir, mock(Logger.class)).discord();

        assertTrue(d.enabled());
        assertEquals(1100000000000000001L, d.guildId());
        assertEquals(1100000000000000002L, d.alertChannelId());
        assertTrue(d.hasAlertChannel());
        assertEquals("192.168.1.25", d.redisHost());
        assertEquals(6380, d.redisPort());
        assertEquals("herald", d.redisUser());
        assertEquals("bsp_core:discord", d.redisChannel(), "an empty channel falls back to BSP-Core's default");
        assertEquals(0, d.wrongCodesPerTenMinutes());
    }

    @Test
    void aDiscordIdThatIsNotANumberCountsAsUnset(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(PluginConfig.FILE_NAME), """
                [discord]
                guildId = "my server"
                alertChannelId = ""
                """);
        PluginConfig.Discord d = PluginConfig.load(dir, mock(Logger.class)).discord();

        assertEquals(0L, d.guildId());
        assertFalse(d.hasAlertChannel());
    }

    @Test
    void theTemplateHasNoTokenOrPasswordSetting() throws Exception {
        String template = new String(PluginConfig.class.getResourceAsStream("/config.toml").readAllBytes());
        String discord = template.substring(template.indexOf("[discord]"));
        for (String line : discord.split("\n")) {
            if (!line.startsWith("#") && line.contains("=")) {
                String key = line.substring(0, line.indexOf('=')).trim().toLowerCase();
                assertFalse(key.contains("token") || key.contains("password"), line);
            }
        }
    }

    @Test
    void journeyMapIdsAreOnByDefaultAndReadTheirOverrides(@TempDir Path dir) throws Exception {
        assertEquals(PluginConfig.JourneyMap.DEFAULTS, PluginConfig.load(dir.resolve("fresh"), mock(Logger.class)).journeyMap());
        assertTrue(PluginConfig.JourneyMap.DEFAULTS.enabled());

        Files.writeString(dir.resolve(PluginConfig.FILE_NAME), """
                [journeyMap]
                enabled = true
                debug = true
                debugAllChannels = true
                switchResendDelayMs = 99999

                [journeyMap.worldIds]
                "BSP-Spawn" = "cc7a1dc1-1f9f-4922-83e4-cb353ca585cd"
                BSP-Frostwarf = "frost"
                """);
        PluginConfig.JourneyMap j = PluginConfig.load(dir, mock(Logger.class)).journeyMap();

        assertTrue(j.debug());
        assertTrue(j.debugAllChannels());
        assertEquals(60_000, j.switchResendDelayMs(), "capped at a minute");
        assertEquals(java.util.Map.of("bsp-spawn", "cc7a1dc1-1f9f-4922-83e4-cb353ca585cd", "bsp-frostwarf", "frost"), j.worldIds());
    }

    @Test
    void theBundledTemplateParsesWithItsEmptyOverrideTable() throws Exception {
        Path dir = Files.createTempDirectory("bspva");
        PluginConfig.JourneyMap j = PluginConfig.load(dir, mock(Logger.class)).journeyMap();

        assertEquals(PluginConfig.JourneyMap.DEFAULTS, j);
    }
}
