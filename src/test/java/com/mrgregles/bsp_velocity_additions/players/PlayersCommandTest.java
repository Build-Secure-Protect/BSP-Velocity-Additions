package com.mrgregles.bsp_velocity_additions.players;

import com.mrgregles.bsp_velocity_additions.config.Messages;
import com.mrgregles.bsp_velocity_additions.config.PluginConfig;
import com.mrgregles.bsp_velocity_additions.tablist.NameFormatter;
import com.mrgregles.bsp_velocity_additions.tablist.PrefixProvider;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayersCommandTest {

    private final ProxyServer proxy = mock(ProxyServer.class);
    private final Player steve = player("Steve", "BSP-Spawn");
    private final Player alex = player("Alex", "BSP-Wild");
    private final Player bram = player("bram", "BSP-Wild");
    private PlayersCommand command;

    @BeforeEach
    void setUp() {
        when(proxy.getAllPlayers()).thenReturn(List.of(steve, alex, bram));
        PluginConfig.TabList tab = new PluginConfig.TabList(true, 3, "bspva.tab.staff");
        PluginConfig.Players cfg = new PluginConfig.Players(true, "bspva.players.servers");
        command = new PlayersCommand(proxy, cfg, Messages.defaults(), new NameFormatter(tab, Messages.defaults(), PrefixProvider.NONE));
    }

    private static Player player(String name, String server) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.getUsername()).thenReturn(name);
        ServerConnection c = mock(ServerConnection.class);
        when(c.getServerInfo()).thenReturn(new ServerInfo(server, new InetSocketAddress("127.0.0.1", 25565)));
        when(p.getCurrentServer()).thenReturn(Optional.of(c));
        return p;
    }

    private static SimpleCommand.Invocation invocationBy(com.velocitypowered.api.command.CommandSource source) {
        SimpleCommand.Invocation inv = mock(SimpleCommand.Invocation.class);
        when(inv.source()).thenReturn(source);
        when(inv.arguments()).thenReturn(new String[0]);
        return inv;
    }

    private static List<String> linesSentTo(com.velocitypowered.api.command.CommandSource source, int count) {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(count)).sendMessage(captor.capture());
        return captor.getAllValues().stream().map(c -> PlainTextComponentSerializer.plainText().serialize(c)).toList();
    }

    @Test
    void aNormalPlayerSeesNamesOnlySortedWithoutServers() {
        command.execute(invocationBy(steve));

        assertEquals(List.of("Online (3): Alex, bram, Steve"), linesSentTo(steve, 1));
    }

    @Test
    void staffSeeTheServerBreakdown() {
        when(steve.hasPermission("bspva.players.servers")).thenReturn(true);

        command.execute(invocationBy(steve));

        assertEquals(List.of("Online (3) on 2 server(s)", "BSP-Spawn (1): Steve", "BSP-Wild (2): Alex, bram"), linesSentTo(steve, 3));
    }

    @Test
    void theConsoleAlwaysSeesServers() {
        ConsoleCommandSource console = mock(ConsoleCommandSource.class);

        command.execute(invocationBy(console));

        assertEquals(3, linesSentTo(console, 3).size());
    }

    @Test
    void anEmptyNetworkSaysSo() {
        when(proxy.getAllPlayers()).thenReturn(List.of());

        command.execute(invocationBy(steve));

        assertEquals(List.of("Nobody is online."), linesSentTo(steve, 1));
    }
}
