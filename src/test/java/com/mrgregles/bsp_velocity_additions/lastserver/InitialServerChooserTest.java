package com.mrgregles.bsp_velocity_additions.lastserver;

import com.mrgregles.bsp_velocity_additions.config.PluginConfig;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InitialServerChooserTest {

    private final Map<String, RegisteredServer> servers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Set<String> down = new HashSet<>();
    private final List<String> tryList = List.of("BSP-Spawn", "BSP-SafeRoom");

    private final RegisteredServer spawn = register("BSP-Spawn");
    private final RegisteredServer wild = register("BSP-Wild");
    private final RegisteredServer safeRoom = register("BSP-SafeRoom");

    private InitialServerChooser chooser(PluginConfig.LastServer config) {
        return new InitialServerChooser(config,
                name -> Optional.ofNullable(servers.get(name)),
                name -> !down.contains(name.toLowerCase(Locale.ROOT)),
                () -> tryList);
    }

    private RegisteredServer register(String name) {
        RegisteredServer server = mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565)));
        servers.put(name, server);
        return server;
    }

    @Test
    void aReachableRememberedServerIsChosen() {
        InitialServerChooser.Choice c = chooser(PluginConfig.LastServer.DEFAULTS).choose(Optional.of("BSP-Wild"));

        assertSame(wild, c.server().orElseThrow());
        assertEquals(InitialServerChooser.Choice.Reason.REMEMBERED, c.reason());
    }

    @Test
    void anOfflineRememberedServerGoesToTheFirstReachableTryServer() {
        down.add("bsp-wild");

        InitialServerChooser.Choice c = chooser(PluginConfig.LastServer.DEFAULTS).choose(Optional.of("BSP-Wild"));

        assertSame(spawn, c.server().orElseThrow());
        assertEquals(InitialServerChooser.Choice.Reason.FALLBACK_INSTEAD_OF_OFFLINE, c.reason());
    }

    @Test
    void theConfiguredFallbackWinsOverTheTryList() {
        down.add("bsp-wild");
        PluginConfig.LastServer config = new PluginConfig.LastServer(true, Set.of(), "BSP-SafeRoom", 10, true);

        assertSame(safeRoom, chooser(config).choose(Optional.of("BSP-Wild")).server().orElseThrow());
    }

    @Test
    void anOfflineFallbackIsSkippedForTheTryList() {
        down.add("bsp-wild");
        down.add("bsp-saferoom");
        PluginConfig.LastServer config = new PluginConfig.LastServer(true, Set.of(), "BSP-SafeRoom", 10, true);

        assertSame(spawn, chooser(config).choose(Optional.of("BSP-Wild")).server().orElseThrow());
    }

    @Test
    void aRememberedServerThatNoLongerExistsGoesToSpawn() {
        InitialServerChooser.Choice c = chooser(PluginConfig.LastServer.DEFAULTS).choose(Optional.of("BSP-Old"));

        assertSame(spawn, c.server().orElseThrow());
        assertEquals(InitialServerChooser.Choice.Reason.FALLBACK_INSTEAD_OF_UNKNOWN, c.reason());
    }

    @Test
    void anIgnoredRememberedServerGoesToSpawn() {
        PluginConfig.LastServer config = new PluginConfig.LastServer(true, Set.of("bsp-wild"), "", 10, true);

        InitialServerChooser.Choice c = chooser(config).choose(Optional.of("BSP-Wild"));

        assertSame(spawn, c.server().orElseThrow());
        assertEquals(InitialServerChooser.Choice.Reason.FALLBACK_IGNORED, c.reason());
    }

    @Test
    void aFirstTimePlayerSkipsAnOfflineFirstTryServer() {
        down.add("bsp-spawn");

        InitialServerChooser.Choice c = chooser(PluginConfig.LastServer.DEFAULTS).choose(Optional.empty());

        assertSame(safeRoom, c.server().orElseThrow());
        assertEquals(InitialServerChooser.Choice.Reason.FALLBACK_FIRST_TIME, c.reason());
    }

    @Test
    void nothingIsChosenWhenEveryServerIsDown() {
        down.addAll(List.of("bsp-spawn", "bsp-saferoom", "bsp-wild"));

        assertTrue(chooser(PluginConfig.LastServer.DEFAULTS).choose(Optional.of("BSP-Wild")).server().isEmpty());
        assertTrue(chooser(PluginConfig.LastServer.DEFAULTS).choose(Optional.empty()).server().isEmpty());
    }

    @Test
    void theFailedServerIsExcludedFromTheFallback() {
        assertSame(safeRoom, chooser(PluginConfig.LastServer.DEFAULTS).fallback(Set.of("bsp-spawn")).orElseThrow());
    }
}
