package com.mrgregles.bsp_velocity_additions.lastserver;

import com.mrgregles.bsp_velocity_additions.config.PluginConfig;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LastServerFeatureTest {

    private final Map<String, RegisteredServer> servers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final RegisteredServer spawn = register("BSP-Spawn");
    private final RegisteredServer wild = register("BSP-Wild");
    private final UUID steveId = UUID.randomUUID();
    private final Player steve = mock(Player.class);

    private final Set<String> down = new HashSet<>();
    private InMemoryStore store;
    private ServerReachability reachability;
    private LastServerFeature feature;

    @BeforeEach
    void setUp() {
        when(steve.getUniqueId()).thenReturn(steveId);
        when(steve.getUsername()).thenReturn("Steve");
        when(steve.getCurrentServer()).thenReturn(Optional.empty());
        store = new InMemoryStore();
        com.velocitypowered.api.proxy.ProxyServer proxy = mock(com.velocitypowered.api.proxy.ProxyServer.class);
        when(proxy.getServer(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        reachability = new ServerReachability(mock(Logger.class), Runnable::run, proxy);
        PluginConfig.LastServer config = new PluginConfig.LastServer(true, Set.of("bsp-lobby"), "", 10, true);
        InitialServerChooser chooser = new InitialServerChooser(config,
                name -> Optional.ofNullable(servers.get(name)),
                reachability::isReachable,
                () -> List.of("BSP-Spawn"));
        feature = new LastServerFeature(config, store, reachability, chooser, mock(Logger.class));
    }

    private RegisteredServer register(String name) {
        RegisteredServer server = mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565)));
        servers.put(name, server);
        return server;
    }

    @Test
    void aReturningPlayerIsSentToTheirLastServer() {
        store.put(steveId, "Steve", "BSP-Wild");
        PlayerChooseInitialServerEvent event = new PlayerChooseInitialServerEvent(steve, spawn);

        feature.onChooseInitialServer(event);

        assertSame(wild, event.getInitialServer().orElseThrow());
    }

    @Test
    void anOfflineLastServerMeansSpawn() {
        store.put(steveId, "Steve", "BSP-Wild");
        reachability.mark("BSP-Wild", false);
        PlayerChooseInitialServerEvent event = new PlayerChooseInitialServerEvent(steve, spawn);

        feature.onChooseInitialServer(event);

        assertSame(spawn, event.getInitialServer().orElseThrow());
    }

    @Test
    void velocitysChoiceStandsWhenNothingAnswers() {
        store.put(steveId, "Steve", "BSP-Wild");
        reachability.mark("BSP-Wild", false);
        reachability.mark("BSP-Spawn", false);
        PlayerChooseInitialServerEvent event = new PlayerChooseInitialServerEvent(steve, spawn);

        feature.onChooseInitialServer(event);

        assertSame(spawn, event.getInitialServer().orElseThrow(), "untouched: Velocity's own choice");
    }

    @Test
    void connectingRecordsTheServerAndMarksItUp() {
        reachability.mark("BSP-Wild", false);

        feature.onServerConnected(new ServerConnectedEvent(steve, wild, null));

        assertEquals(Optional.of("BSP-Wild"), store.get(steveId));
        assertTrue(reachability.isReachable("bsp-wild"));
    }

    @Test
    void anIgnoredServerIsNotRecorded() {
        RegisteredServer lobby = register("BSP-Lobby");
        store.put(steveId, "Steve", "BSP-Wild");

        feature.onServerConnected(new ServerConnectedEvent(steve, lobby, wild));

        assertEquals(Optional.of("BSP-Wild"), store.get(steveId));
    }

    @Test
    void aFailedFirstConnectionIsRedirectedToSpawn() {
        KickedFromServerEvent event = new KickedFromServerEvent(steve, wild, Component.text("Connection refused"), true,
                KickedFromServerEvent.DisconnectPlayer.create(Component.text("Unable to connect")));

        feature.onKickedFromServer(event);

        KickedFromServerEvent.RedirectPlayer redirect = assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, event.getResult());
        assertSame(spawn, redirect.getServer());
        assertFalse(reachability.isReachable("BSP-Wild"));
    }

    @Test
    void aPlayerVelocityWouldDisconnectLaterIsAlsoSentToSpawn() {
        ServerConnection onWild = mock(ServerConnection.class);
        when(steve.getCurrentServer()).thenReturn(Optional.of(onWild));
        KickedFromServerEvent event = new KickedFromServerEvent(steve, wild, Component.text("Server closed"), false,
                KickedFromServerEvent.DisconnectPlayer.create(Component.text("Server closed")));

        feature.onKickedFromServer(event);

        assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, event.getResult());
    }

    @Test
    void velocitysOwnRedirectIsLeftAlone() {
        ServerConnection onWild = mock(ServerConnection.class);
        when(steve.getCurrentServer()).thenReturn(Optional.of(onWild));
        KickedFromServerEvent.ServerKickResult velocitys = KickedFromServerEvent.RedirectPlayer.create(spawn);
        KickedFromServerEvent event = new KickedFromServerEvent(steve, wild, null, false, velocitys);

        feature.onKickedFromServer(event);

        assertSame(velocitys, event.getResult());
    }

    @Test
    void noFallbackLeavesTheDisconnect() {
        reachability.mark("BSP-Spawn", false);
        KickedFromServerEvent.ServerKickResult disconnect = KickedFromServerEvent.DisconnectPlayer.create(Component.text("x"));
        KickedFromServerEvent event = new KickedFromServerEvent(steve, wild, null, true, disconnect);

        feature.onKickedFromServer(event);

        assertSame(disconnect, event.getResult());
    }

    /** A store without a file, for these tests. */
    private static final class InMemoryStore implements LastServerStore {
        private final Map<UUID, String> map = new java.util.HashMap<>();

        @Override
        public Optional<String> get(UUID player) {
            return Optional.ofNullable(map.get(player));
        }

        @Override
        public void put(UUID player, String name, String server) {
            map.put(player, server);
        }

        @Override
        public int size() {
            return map.size();
        }

        @Override
        public void flush() {
        }
    }
}
