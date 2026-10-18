package com.mrgregles.bsp_velocity_additions.lastserver;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Bug B1: a modded backend answers a status ping with a reply Velocity cannot decode, so the check must not depend on decoding. */
class ServerReachabilityTest {

    private static RegisteredServer server(String name, int port, Player... players) {
        RegisteredServer s = mock(RegisteredServer.class);
        when(s.getServerInfo()).thenReturn(new ServerInfo(name, new InetSocketAddress(InetAddress.getLoopbackAddress(), port)));
        when(s.getPlayersConnected()).thenReturn(List.of(players));
        return s;
    }

    private static ProxyServer proxyWith(RegisteredServer... servers) {
        ProxyServer proxy = mock(ProxyServer.class);
        when(proxy.getAllServers()).thenReturn(Set.of(servers));
        for (RegisteredServer s : servers) {
            when(proxy.getServer(s.getServerInfo().getName())).thenReturn(java.util.Optional.of(s));
        }
        return proxy;
    }

    @Test
    void anOpenPortIsReachableWhateverListensThere() throws Exception {
        try (ServerSocket listening = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            RegisteredServer up = server("up", listening.getLocalPort());
            ProxyServer proxy = proxyWith(up);
            ServerReachability r = new ServerReachability(mock(Logger.class), Runnable::run, proxy);

            r.refresh(proxy);

            assertTrue(r.isReachable("up"));
        }
    }

    @Test
    void aClosedPortIsNotAnswering() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = s.getLocalPort();
        }
        RegisteredServer down = server("down", closedPort);
        ProxyServer proxy = proxyWith(down);
        ServerReachability r = new ServerReachability(mock(Logger.class), Runnable::run, proxy);

        r.refresh(proxy);

        assertFalse(r.isReachable("down"));
    }

    @Test
    void aServerWithPlayersOnItIsReachableEvenWhenTheCheckFails() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = s.getLocalPort();
        }
        RegisteredServer busy = server("busy", closedPort, mock(Player.class));
        ProxyServer proxy = proxyWith(busy);
        ServerReachability r = new ServerReachability(mock(Logger.class), Runnable::run, proxy);

        r.mark("busy", false);
        r.refresh(proxy);

        assertTrue(r.isReachable("busy"), "a player is connected, so the server is up whatever the check says");
    }

    @Test
    void unknownServersCountAsReachableUntilChecked() {
        ServerReachability r = new ServerReachability(mock(Logger.class), Runnable::run, proxyWith());

        assertTrue(r.isReachable("never-checked"));
    }
}
