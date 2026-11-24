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

    /** B6: the rank reload is on every 30 s by default, 0 switches it off, and a negative value counts as off. */
    @Test
    void readsRankRefreshSeconds(@TempDir Path dir) throws Exception {
        assertEquals(30, PluginConfig.load(dir.resolve("defaults"), mock(Logger.class)).tabList().rankRefreshSeconds());
        Files.writeString(dir.resolve(PluginConfig.FILE_NAME), "[tabList]\nrankRefreshSeconds = 0\n");
        assertEquals(0, PluginConfig.load(dir, mock(Logger.class)).tabList().rankRefreshSeconds());
        Files.writeString(dir.resolve(PluginConfig.FILE_NAME), "[tabList]\nrankRefreshSeconds = -5\n");
        assertEquals(0, PluginConfig.load(dir, mock(Logger.class)).tabList().rankRefreshSeconds());
    }

    /** SU-VA2: network kicks are on by default and can be switched off. */
    @Test
    void readsTheNetworkKickSwitch(@TempDir Path dir) throws Exception {
        assertTrue(PluginConfig.load(dir.resolve("defaults"), mock(Logger.class)).networkKick().enabled());
        Files.writeString(dir.resolve(PluginConfig.FILE_NAME), "[networkKick]\nenabled = false\n");
        assertFalse(PluginConfig.load(dir, mock(Logger.class)).networkKick().enabled());
    }

    /** T25: chat history defaults (on, 50 lines, 30 minutes) and its limits. */
    @Test
    void readsTheChatHistorySection(@TempDir Path dir) throws Exception {
        assertEquals(PluginConfig.ChatHistory.DEFAULTS, PluginConfig.load(dir.resolve("defaults"), mock(Logger.class)).chatHistory());
        Files.writeString(dir.resolve(PluginConfig.FILE_NAME), "[chatHistory]\nenabled = false\nlines = 9000\nmaxAgeMinutes = 0\n");
        PluginConfig.ChatHistory h = PluginConfig.load(dir, mock(Logger.class)).chatHistory();
        assertFalse(h.enabled());
        assertEquals(500, h.lines());
        assertEquals(1, h.maxAgeMinutes());
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

    /** PR21: on by default on BSP-Core's storage channel, switchable off, Redis taken from [discord] unless given. */
    @Test
    void playerResetIsOnByDefaultAndFallsBackToTheDiscordRedis(@TempDir Path dir) throws Exception {
        PluginConfig fresh = PluginConfig.load(dir.resolve("fresh"), mock(Logger.class));
        assertEquals(PluginConfig.PlayerReset.DEFAULTS, fresh.playerReset(), "the bundled template reads as the defaults");
        assertTrue(PluginConfig.PlayerReset.DEFAULTS.enabled());
        assertEquals("bsp_core", PluginConfig.PlayerReset.DEFAULTS.redisChannel());

        Files.writeString(dir.resolve(PluginConfig.FILE_NAME), """
                [discord]
                redisHost = "192.168.1.25"
                redisPort = 6380
                redisUser = "herald"
                """);
        PluginConfig.PlayerReset noSection = PluginConfig.load(dir, mock(Logger.class)).playerReset();
        assertEquals(new PluginConfig.PlayerReset(true, "192.168.1.25", 6380, "herald", "bsp_core"), noSection,
                "a config.toml from before PR21");

        Files.writeString(dir.resolve(PluginConfig.FILE_NAME), """
                [discord]
                redisHost = "192.168.1.25"
                redisPort = 6380
                redisUser = "herald"

                [playerReset]
                enabled = true
                redisChannel = ""
                redisHost = ""
                redisPort = 0
                redisUser = ""
                """);
        assertEquals(new PluginConfig.PlayerReset(true, "192.168.1.25", 6380, "herald", "bsp_core"),
                PluginConfig.load(dir, mock(Logger.class)).playerReset(), "empty values take [discord]'s and the default channel");

        Files.writeString(dir.resolve(PluginConfig.FILE_NAME), """
                [playerReset]
                enabled = false
                redisChannel = "bsp_test"
                redisHost = "10.0.0.5"
                redisPort = 99999
                redisUser = "proxy"
                """);
        assertEquals(new PluginConfig.PlayerReset(false, "10.0.0.5", 65535, "proxy", "bsp_test"),
                PluginConfig.load(dir, mock(Logger.class)).playerReset());
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
