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
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RankUp request RU-R3 (decision D35): names a backend gives (BSP-RankUp's prefix images and suffixes) are kept, and
 * players on other servers get the name their own backend gives them.
 */
class TabListNamesTest {

    /** A tab list that keeps entries and their names the way the proxy's does. */
    static final class FakeTabList {
        final Map<UUID, TabListEntry> entries = new HashMap<>();
        final Map<UUID, Component> names = new HashMap<>();
        final TabList list = mock(TabList.class);
        int nameSetsByPlugin;

        FakeTabList() {
            when(list.getEntry(any())).thenAnswer(i -> Optional.ofNullable(entries.get((UUID) i.getArgument(0))));
            when(list.buildEntry(any(), any(), anyInt(), anyInt(), any(), anyBoolean(), anyInt()))
                    .thenAnswer(i -> entry(((GameProfile) i.getArgument(0)).getId(), i.getArgument(1)));
            doAnswer(i -> {
                TabListEntry e = i.getArgument(0);
                entries.put(e.getProfile().getId(), e);
                return null;
            }).when(list).addEntry(any());
        }

        TabListEntry entry(UUID id, Component name) {
            TabListEntry e = mock(TabListEntry.class);
            when(e.getProfile()).thenReturn(new GameProfile(id, "x", List.of()));
            if (name != null) {
                names.put(id, name);
            }
            when(e.getDisplayNameComponent()).thenAnswer(i -> Optional.ofNullable(names.get(id)));
            when(e.setDisplayName(any())).thenAnswer(i -> {
                nameSetsByPlugin++;
                names.put(id, i.getArgument(0));
                return e;
            });
            return e;
        }

        /** The backend lists a player, with its own name (null: none). */
        void backendLists(UUID id, Component name) {
            entries.put(id, entry(id, name));
        }

        /** The backend sends a new display name, as Velocity's processUpsert stores it. */
        void backendRenames(UUID id, Component name) {
            names.put(id, name);
        }

        String shown(UUID id) {
            Component c = names.get(id);
            return c == null ? null : PlainTextComponentSerializer.plainText().serialize(c);
        }
    }

    private final ProxyServer proxy = mock(ProxyServer.class);
    private final Map<UUID, FakeTabList> lists = new HashMap<>();
    private final Map<UUID, String> prefixes = new HashMap<>();
    private Player steve, mrgregles, alex;
    private TabListFeature feature;

    /** RankUp's look: the prefix image (a translatable in RankUp's font), the name, a suffix. */
    static Component rankUp(String image, String name) {
        return Component.translatable("bsp_rankup.prefix." + image, "[" + image + "]").font(Key.key("bsp_rankup", "prefixes"))
                .append(Component.text(" " + name)).append(Component.text(" ✦", NamedTextColor.YELLOW));
    }

    private Player player(String name, String server) {
        Player p = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(p.getUniqueId()).thenReturn(id);
        when(p.getUsername()).thenReturn(name);
        when(p.getGameProfile()).thenReturn(new GameProfile(id, name, List.of()));
        FakeTabList list = new FakeTabList();
        lists.put(id, list);
        when(p.getTabList()).thenReturn(list.list);
        ServerConnection c = mock(ServerConnection.class);
        when(c.getServerInfo()).thenReturn(new ServerInfo(server, new InetSocketAddress("127.0.0.1", 25565)));
        when(p.getCurrentServer()).thenReturn(Optional.of(c));
        return p;
    }

    private FakeTabList listOf(Player p) {
        return lists.get(p.getUniqueId());
    }

    @BeforeEach
    void setUp() {
        steve = player("Steve", "BSP-Spawn");
        mrgregles = player("Mrgregles", "BSP-Spawn");
        alex = player("Alex", "BSP-Frostwarf");
        when(proxy.getAllPlayers()).thenReturn(List.of(steve, mrgregles, alex));
        PrefixProvider text = p -> Component.text(prefixes.getOrDefault(p.getUniqueId(), ""));
        PluginConfig.TabList config = new PluginConfig.TabList(true, 3, "", 30);
        feature = new TabListFeature(proxy, Messages.defaults(), new NameFormatter(config, Messages.defaults(), text));
        prefixes.put(alex.getUniqueId(), "[Miner] ");
        prefixes.put(mrgregles.getUniqueId(), "[Owner] ");
    }

    /** Each backend lists its own players, with RankUp's names (null: RankUp's prefix display off). */
    private void backendsList(Component mrgreglesName, Component alexName, Component steveName) {
        for (Player viewer : List.of(steve, mrgregles)) { // both on BSP-Spawn
            listOf(viewer).backendLists(steve.getUniqueId(), steveName);
            listOf(viewer).backendLists(mrgregles.getUniqueId(), mrgreglesName);
        }
        listOf(alex).backendLists(alex.getUniqueId(), alexName); // alone on BSP-Frostwarf
    }

    @Test
    void aNameTheBackendGaveIsLeftAlone() {
        Component owner = rankUp("owner", "Mrgregles");
        backendsList(owner, rankUp("miner", "Alex"), rankUp("miner", "Steve"));

        feature.refreshAll();
        feature.refreshAll();

        assertSame(owner, listOf(steve).names.get(mrgregles.getUniqueId()), "RankUp's own component, image and all");
        assertEquals(0, listOf(steve).nameSetsByPlugin, "nothing on Steve's list is renamed; Alex's entry is added with a name");
    }

    @Test
    void aPlayerOnAnotherServerGetsTheNameTheirOwnServerGivesThem() {
        Component alexByRankUp = rankUp("miner", "Alex");
        backendsList(rankUp("owner", "Mrgregles"), alexByRankUp, rankUp("miner", "Steve"));

        feature.refreshAll();

        assertEquals(alexByRankUp, listOf(steve).names.get(alex.getUniqueId()));
        assertEquals(rankUp("owner", "Mrgregles"), listOf(alex).names.get(mrgregles.getUniqueId()));
    }

    @Test
    void aRankUpOnTheirOwnServerReachesEveryOtherServerOnTheNextRefresh() {
        backendsList(rankUp("owner", "Mrgregles"), rankUp("miner", "Alex"), rankUp("miner", "Steve"));
        feature.refreshAll();

        listOf(alex).backendRenames(alex.getUniqueId(), rankUp("digger", "Alex")); // RankUp redraws Alex on Frostwarf
        feature.refreshAll();

        assertEquals(rankUp("digger", "Alex"), listOf(steve).names.get(alex.getUniqueId()));
        assertEquals(rankUp("digger", "Alex"), listOf(mrgregles).names.get(alex.getUniqueId()));
    }

    @Test
    void whereTheBackendGivesNoNameTheTextFormatFillsIn() {
        backendsList(null, null, null); // RankUp's prefix display switched off

        feature.refreshAll();

        assertEquals("[Owner] Mrgregles", listOf(steve).shown(mrgregles.getUniqueId()));
        assertEquals("[Miner] Alex", listOf(steve).shown(alex.getUniqueId()));
        assertEquals("[Miner] Alex", listOf(alex).shown(alex.getUniqueId()), "the player's own row too");
    }

    @Test
    void theTextFallbackIsNotMistakenForTheBackendsNameAndFollowsTheRank() {
        backendsList(null, null, null);
        feature.refreshAll();

        prefixes.put(alex.getUniqueId(), "[Digger] "); // the proxy's LuckPerms reloaded (bug B6)
        prefixes.put(mrgregles.getUniqueId(), "[Admin] ");
        feature.refreshAll();

        assertEquals("[Digger] Alex", listOf(steve).shown(alex.getUniqueId()), "copied entry follows, not stuck on the old fallback");
        assertEquals("[Digger] Alex", listOf(alex).shown(alex.getUniqueId()));
        assertEquals("[Admin] Mrgregles", listOf(steve).shown(mrgregles.getUniqueId()));
    }

    @Test
    void whenTheBackendStartsGivingANameItWinsOverTheFallback() {
        backendsList(null, null, null);
        feature.refreshAll();
        assertEquals("[Owner] Mrgregles", listOf(steve).shown(mrgregles.getUniqueId()));

        listOf(steve).backendRenames(mrgregles.getUniqueId(), rankUp("owner", "Mrgregles")); // RankUp switched on
        listOf(alex).backendRenames(alex.getUniqueId(), rankUp("miner", "Alex"));
        feature.refreshAll();
        feature.refreshAll();

        assertEquals(rankUp("owner", "Mrgregles"), listOf(steve).names.get(mrgregles.getUniqueId()));
        assertEquals(rankUp("miner", "Alex"), listOf(steve).names.get(alex.getUniqueId()));
    }

    @Test
    void everyoneIsListedOnceAndNoViewerIsAddedToTheirOwnList() {
        backendsList(null, null, null);

        feature.refreshAll();
        feature.refreshAll();

        assertEquals(java.util.Set.of(alex.getUniqueId(), steve.getUniqueId(), mrgregles.getUniqueId()), listOf(alex).entries.keySet());
        assertEquals(java.util.Set.of(alex.getUniqueId(), steve.getUniqueId(), mrgregles.getUniqueId()), listOf(steve).entries.keySet());
    }
}
