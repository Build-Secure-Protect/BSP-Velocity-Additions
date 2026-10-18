package com.mrgregles.bsp_velocity_additions;

import com.mrgregles.bsp_velocity_additions.lastserver.JsonLastServerStore;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Proves the wiring: the plugin starts against a mocked proxy, writes its files and registers the feature. */
class BspVelocityAdditionsTest {

    @Test
    void startsWritesConfigAndRegistersTheLastServerFeature(@TempDir Path dir) throws Exception {
        ProxyServer proxy = mock(ProxyServer.class, RETURNS_DEEP_STUBS);
        when(proxy.getAllServers()).thenReturn(Set.of());
        when(proxy.getConfiguration().getAttemptConnectionOrder()).thenReturn(List.of("BSP-Spawn"));
        Logger logger = mock(Logger.class);
        Path data = dir.resolve("plugins").resolve("bsp_velocity_additions");
        Files.createDirectories(dir.resolve("plugins").resolve("velocityrememberserver"));
        Files.writeString(dir.resolve("plugins").resolve("velocityrememberserver").resolve("locations.yml"),
                "aff80d73-fa58-464d-b419-11187ffd8cd3: BSP-Wild\n");

        BspVelocityAdditions plugin = new BspVelocityAdditions(proxy, logger, data);
        plugin.onProxyInitialize(new ProxyInitializeEvent());
        plugin.onProxyShutdown(new ProxyShutdownEvent());

        assertEquals("bsp_velocity_additions", BspVelocityAdditions.ID);
        assertTrue(Files.exists(data.resolve("config.toml")), "default config written");
        assertTrue(Files.exists(data.resolve(JsonLastServerStore.FILE_NAME)), "store flushed on shutdown after the import");
        assertTrue(Files.readString(data.resolve(JsonLastServerStore.FILE_NAME)).contains("BSP-Wild"), "VelocityRememberServer data imported");
        verify(proxy.getEventManager()).register(eq(plugin), any(com.mrgregles.bsp_velocity_additions.lastserver.LastServerFeature.class));
    }
}
