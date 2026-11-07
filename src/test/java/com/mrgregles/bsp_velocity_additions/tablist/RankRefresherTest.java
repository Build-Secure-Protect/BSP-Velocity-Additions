package com.mrgregles.bsp_velocity_additions.tablist;

import com.mrgregles.bsp_velocity_additions.config.Messages;
import com.mrgregles.bsp_velocity_additions.config.PluginConfig;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.util.GameProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.platform.PlayerAdapter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Bug B6: a rank changed on a backend while the player is online shows without a relog. */
class RankRefresherTest {

    private final ProxyServer proxy = mock(ProxyServer.class);
    private final Player steve = player("Steve", "BSP-Spawn");
    private final Player alex = player("Alex", "BSP-Frostwarf");
    private final Player joining = player("Ghost", null);

    /**
     * LuckPerms as the proxy sees it: storage (what BSP-RankUp's modifyUser writes on a backend) and the proxy's
     * cached copy, loaded at login. Only a reload copies storage into the cache.
     */
    static final class FakeLuckPerms implements PrefixProvider {
        final Map<UUID, String> storage = new HashMap<>();
        final Map<UUID, String> cache = new HashMap<>();

        void login(Player p, String prefix) {
            storage.put(p.getUniqueId(), prefix);
            cache.put(p.getUniqueId(), prefix);
        }

        @Override
        public Component prefixOf(Player player) {
            return Component.text(cache.getOrDefault(player.getUniqueId(), ""));
        }

        @Override
        public void reload(Player player) {
            cache.put(player.getUniqueId(), storage.getOrDefault(player.getUniqueId(), ""));
        }
    }

    private static Player player(String name, String server) {
        Player p = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(p.getUniqueId()).thenReturn(id);
        when(p.getUsername()).thenReturn(name);
        when(p.getGameProfile()).thenReturn(new GameProfile(id, name, List.of()));
        TabList list = mock(TabList.class);
        when(list.getEntry(any())).thenReturn(Optional.empty());
        when(list.buildEntry(any(), any(), anyInt(), anyInt(), any(), anyBoolean(), anyInt())).thenReturn(mock(TabListEntry.class));
        when(p.getTabList()).thenReturn(list);
        if (server == null) {
            when(p.getCurrentServer()).thenReturn(Optional.empty());
        } else {
            ServerConnection c = mock(ServerConnection.class);
            when(c.getServerInfo()).thenReturn(new ServerInfo(server, new InetSocketAddress("127.0.0.1", 25565)));
            when(p.getCurrentServer()).thenReturn(Optional.of(c));
        }
        return p;
    }

    /** The name Steve's tab list last showed for Alex. */
    private String alexInStevesList() {
        ArgumentCaptor<Component> name = ArgumentCaptor.forClass(Component.class);
        verify(steve.getTabList(), org.mockito.Mockito.atLeastOnce())
                .buildEntry(any(), name.capture(), anyInt(), anyInt(), any(), anyBoolean(), anyInt());
        return PlainTextComponentSerializer.plainText().serialize(name.getValue());
    }

    @Test
    void aRankChangedOnABackendShowsInTheTabListAfterTheNextReload() {
        when(proxy.getAllPlayers()).thenReturn(List.of(steve, alex));
        FakeLuckPerms luckPerms = new FakeLuckPerms();
        luckPerms.login(alex, "[Miner] ");
        PluginConfig.TabList config = new PluginConfig.TabList(true, 3, "", 30);
        TabListFeature tab = new TabListFeature(proxy, Messages.defaults(), new NameFormatter(config, Messages.defaults(), luckPerms));
        RankRefresher ranks = new RankRefresher(proxy, luckPerms);
        tab.refreshAll();
        assertEquals("[Miner] Alex", alexInStevesList());

        luckPerms.storage.put(alex.getUniqueId(), "[Digger] "); // BSP-RankUp ranks Alex up on BSP-Frostwarf
        when(steve.getTabList().getEntry(alex.getUniqueId())).thenReturn(Optional.empty()); // re-add path each refresh
        tab.refreshAll();
        assertEquals("[Miner] Alex", alexInStevesList(), "before a reload the proxy still has the login copy");

        ranks.reloadAll();
        tab.refreshAll();

        assertEquals("[Digger] Alex", alexInStevesList());
    }

    @Test
    void onlyPlayersOnAServerAreReloaded() {
        when(proxy.getAllPlayers()).thenReturn(List.of(steve, joining));
        PrefixProvider prefixes = mock(PrefixProvider.class);

        new RankRefresher(proxy, prefixes).reloadAll();

        verify(prefixes).reload(steve);
        verify(prefixes, never()).reload(joining);
    }

    /** The LuckPerms side: the prefix comes from the cached metadata, and a reload is UserManager.loadUser. */
    @Test
    @SuppressWarnings("unchecked")
    void theLuckPermsBridgeReadsTheCachedPrefixAndReloadsWithLoadUser() {
        LuckPerms api = mock(LuckPerms.class);
        PlayerAdapter<Player> adapter = mock(PlayerAdapter.class);
        CachedMetaData meta = mock(CachedMetaData.class);
        UserManager users = mock(UserManager.class);
        when(api.getPlayerAdapter(Player.class)).thenReturn(adapter);
        when(adapter.getMetaData(alex)).thenReturn(meta);
        when(meta.getPrefix()).thenReturn("&6[Digger]");
        when(api.getUserManager()).thenReturn(users);
        when(users.loadUser(alex.getUniqueId())).thenReturn(new CompletableFuture<>());
        PrefixProvider bridge = LuckPermsPrefixes.Bridge.forApi(api);

        assertEquals("[Digger] ", PlainTextComponentSerializer.plainText().serialize(bridge.prefixOf(alex)));
        bridge.reload(alex);

        verify(users).loadUser(alex.getUniqueId());
    }

    @Test
    void aFailingReloadIsQuietAndTheNextRoundTriesAgain() {
        LuckPerms api = mock(LuckPerms.class);
        when(api.getUserManager()).thenThrow(new IllegalStateException("LuckPerms is shutting down"));

        LuckPermsPrefixes.Bridge.forApi(api).reload(alex); // no exception reaches the scheduler
    }
}
