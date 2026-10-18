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
}
