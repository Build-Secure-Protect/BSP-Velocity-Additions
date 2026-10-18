package com.mrgregles.bsp_velocity_additions.joinleave;

import com.mrgregles.bsp_velocity_additions.config.Messages;
import com.mrgregles.bsp_velocity_additions.config.PluginConfig;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JoinLeaveFeatureTest {

    private final ProxyServer proxy = mock(ProxyServer.class);
    private final ConsoleCommandSource console = mock(ConsoleCommandSource.class);
    private final Player steve = player("Steve");
    private final Player alex = player("Alex");
    private final RegisteredServer spawn = server("BSP-Spawn");
    private final RegisteredServer wild = server("BSP-Wild");

    @BeforeEach
    void setUp() {
        when(proxy.getAllPlayers()).thenReturn(List.of(steve, alex));
        when(proxy.getConsoleCommandSource()).thenReturn(console);
    }

    private JoinLeaveFeature feature() {
        PluginConfig.JoinLeave config = new PluginConfig.JoinLeave(true, "bspva.joinleave.silent");
        return new JoinLeaveFeature(proxy, config, Messages.defaults(), mock(Logger.class));
    }

    private static Player player(String name) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.getUsername()).thenReturn(name);
        when(p.getCurrentServer()).thenReturn(Optional.empty());
        return p;
    }

    private static RegisteredServer server(String name) {
        RegisteredServer s = mock(RegisteredServer.class);
        when(s.getServerInfo()).thenReturn(new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565)));
        return s;
    }

    private static void placeOn(Player p, RegisteredServer s) {
        ServerInfo info = s.getServerInfo();
        ServerConnection c = mock(ServerConnection.class);
        when(c.getServerInfo()).thenReturn(info);
        when(c.getServer()).thenReturn(s);
        when(p.getCurrentServer()).thenReturn(Optional.of(c));
    }

    private static List<String> linesSentTo(Player p, int count) {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(p, times(count)).sendMessage(captor.capture());
        return captor.getAllValues().stream().map(c -> PlainTextComponentSerializer.plainText().serialize(c)).toList();
    }

    @Test
    void firstLandingAnnouncesAJoinToEveryoneAndTheConsole() {
        JoinLeaveFeature f = feature();
        placeOn(steve, spawn);

        f.onServerPostConnect(new ServerPostConnectEvent(steve, null));

        assertEquals(List.of("Steve joined the game"), linesSentTo(steve, 1));
        assertEquals(List.of("Steve joined the game"), linesSentTo(alex, 1));
        verify(console).sendMessage(any(Component.class));
        assertTrue(f.isOnNetwork(steve.getUniqueId()));
    }

    @Test
    void aServerSwitchNeverPrintsAnything() {
        JoinLeaveFeature f = feature();
        placeOn(steve, spawn);
        f.onServerPostConnect(new ServerPostConnectEvent(steve, null));
        placeOn(steve, wild);

        f.onServerPostConnect(new ServerPostConnectEvent(steve, spawn));
        f.onServerPostConnect(new ServerPostConnectEvent(steve, wild));

        assertEquals(List.of("Steve joined the game"), linesSentTo(alex, 1));
        assertTrue(f.isOnNetwork(steve.getUniqueId()));
    }

    @Test
    void leavingAfterAJoinAnnouncesALeaveOnce() {
        JoinLeaveFeature f = feature();
        placeOn(steve, spawn);
        f.onServerPostConnect(new ServerPostConnectEvent(steve, null));

        f.onDisconnect(new DisconnectEvent(steve, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
        f.onDisconnect(new DisconnectEvent(steve, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));

        assertEquals(List.of("Steve joined the game", "Steve left the game"), linesSentTo(alex, 2));
        assertFalse(f.isOnNetwork(steve.getUniqueId()));
    }

    @Test
    void aPlayerWhoNeverReachedABackendLeavesSilently() {
        JoinLeaveFeature f = feature();

        f.onDisconnect(new DisconnectEvent(steve, DisconnectEvent.LoginStatus.PRE_SERVER_JOIN));

        verify(alex, never()).sendMessage(any(Component.class));
    }

    @Test
    void theSilentPermissionHidesJoinAndLeave() {
        JoinLeaveFeature f = feature();
        when(steve.hasPermission("bspva.joinleave.silent")).thenReturn(true);
        placeOn(steve, spawn);

        f.onServerPostConnect(new ServerPostConnectEvent(steve, null));
        f.onDisconnect(new DisconnectEvent(steve, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));

        verify(alex, never()).sendMessage(any(Component.class));
    }
}
