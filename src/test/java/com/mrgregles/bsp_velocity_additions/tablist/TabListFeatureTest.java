package com.mrgregles.bsp_velocity_additions.tablist;

import com.mrgregles.bsp_velocity_additions.config.Messages;
import com.mrgregles.bsp_velocity_additions.config.PluginConfig;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.util.GameProfile;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TabListFeatureTest {

    private final ProxyServer proxy = mock(ProxyServer.class);
    private final Player steve = player("Steve", "BSP-Spawn");
    private final Player alex = player("Alex", "BSP-Wild");
    private final Player lobbyless = player("Ghost", null);
    private TabListFeature feature;

    @BeforeEach
    void setUp() {
        when(proxy.getAllPlayers()).thenReturn(List.of(steve, alex, lobbyless));
        PluginConfig.TabList config = new PluginConfig.TabList(true, 3, "bspva.tab.staff");
        feature = new TabListFeature(proxy, Messages.defaults(), new NameFormatter(config, Messages.defaults(), PrefixProvider.NONE));
    }

    private static Player player(String name, String server) {
        Player p = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(p.getUniqueId()).thenReturn(id);
        when(p.getUsername()).thenReturn(name);
        when(p.getGameProfile()).thenReturn(new GameProfile(id, name, List.of()));
        when(p.getPing()).thenReturn(42L);
        TabList list = mock(TabList.class);
        when(list.getEntry(any())).thenReturn(Optional.empty());
        when(list.buildEntry(any(), any(), anyInt(), anyInt(), any(), anyBoolean(), anyInt(), anyBoolean()))
                .thenAnswer(inv -> {
                    TabListEntry e = mock(TabListEntry.class);
                    when(e.getProfile()).thenReturn(inv.getArgument(0));
                    when(e.getDisplayNameComponent()).thenReturn(Optional.ofNullable(inv.getArgument(1)));
                    return e;
                });
        when(p.getTabList()).thenReturn(list);
        if (server != null) {
            ServerConnection c = mock(ServerConnection.class);
            when(c.getServerInfo()).thenReturn(new ServerInfo(server, new InetSocketAddress("127.0.0.1", 25565)));
            when(p.getCurrentServer()).thenReturn(Optional.of(c));
        } else {
            when(p.getCurrentServer()).thenReturn(Optional.empty());
        }
        return p;
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void everyoneOnABackendSeesEveryoneElseAndTheHeader() {
        feature.refreshAll();

        ArgumentCaptor<TabListEntry> added = ArgumentCaptor.forClass(TabListEntry.class);
        verify(steve.getTabList()).addEntry(added.capture());
        assertEquals("Alex", added.getValue().getProfile().getName());
        assertEquals("Alex", plain(added.getValue().getDisplayNameComponent().orElseThrow()));
        verify(alex.getTabList()).addEntry(any());

        ArgumentCaptor<Component> header = ArgumentCaptor.forClass(Component.class);
        ArgumentCaptor<Component> footer = ArgumentCaptor.forClass(Component.class);
        verify(steve.getTabList()).setHeaderAndFooter(header.capture(), footer.capture());
        assertEquals("Build | Secure | Protect\nminecraft.buildsecureprotect.com", plain(header.getValue()));
        assertEquals("2 online · ping 42 ms", plain(footer.getValue()), "a player who has not reached a backend is not counted");
        verify(lobbyless.getTabList(), never()).addEntry(any());
    }

    @Test
    void anEntryTheBackendAlreadyHasOnlyGetsItsNameSet() {
        TabListEntry backendEntry = mock(TabListEntry.class);
        when(backendEntry.getDisplayNameComponent()).thenReturn(Optional.empty());
        when(steve.getTabList().getEntry(alex.getUniqueId())).thenReturn(Optional.of(backendEntry));

        feature.refreshAll();

        verify(backendEntry).setDisplayName(any());
        verify(steve.getTabList(), never()).addEntry(any());
    }

    @Test
    void aLeaverIsRemovedFromTheListsThePluginAddedThemTo() {
        feature.refreshAll();

        feature.onDisconnect(new DisconnectEvent(alex, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));

        verify(steve.getTabList()).removeEntry(alex.getUniqueId());
    }

    @Test
    void aServerSwitchReaddsEverythingBecauseVelocityClearedTheList() {
        feature.refreshAll();

        feature.onServerPostConnect(new ServerPostConnectEvent(steve, null));

        verify(steve.getTabList(), org.mockito.Mockito.times(2)).addEntry(any());
    }

    @Test
    void theStaffMarkAndPrefixShowInTheName() {
        when(alex.hasPermission("bspva.tab.staff")).thenReturn(true);
        PluginConfig.TabList config = new PluginConfig.TabList(true, 3, "bspva.tab.staff");
        Messages m = Messages.defaults();
        NameFormatter names = new NameFormatter(config, m, p -> Component.text("[Owner] "));

        assertEquals("[Owner] Alex", plain(names.nameOf(alex)), "the bundled staffMark is empty, so only the prefix shows");
        assertEquals("[Owner] Steve", plain(names.nameOf(steve)));
        verify(steve, org.mockito.Mockito.atLeastOnce()).hasPermission(eq("bspva.tab.staff"));
    }
}
