package com.mrgregles.bsp_velocity_additions.tablist;

import com.mrgregles.bsp_velocity_additions.config.Messages;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feature 3: everyone on the network in everyone's tab list, with no server shown (D16). The backend fills a
 * player's tab list with the players on that backend; this class adds the rest and sets the header and footer.
 * Velocity empties a tab list on every server switch, so every switch and disconnect triggers a refresh, and a timer
 * repeats it for the footer and the names.
 *
 * <p>Names (BSP-RankUp request RU-R3, decision D35): BSP-RankUp draws each player's prefix, as its image, and suffix in
 * the tab list. A name a backend gave is left alone. A player on another server gets the name their own backend gives
 * them, read from their own entry in their own tab list (the proxy keeps backends' display names on its entries). Only
 * where a backend gives no name does {@link NameFormatter} fill in {@code tabList.entry}; a name this class set is
 * remembered, so it is told apart from one the backend sends later.
 */
public final class TabListFeature {

    public static final String KEY_HEADER = "tabList.header";
    public static final String KEY_FOOTER = "tabList.footer";

    private final ProxyServer proxy;
    private final Messages messages;
    private final NameFormatter names;
    /** Entries this plugin added to each viewer's list, so only those are removed again. */
    private final Map<UUID, Set<UUID>> added = new ConcurrentHashMap<>();
    /** The name this plugin last gave each entry of each viewer's list. */
    private final Map<UUID, Map<UUID, Component>> named = new ConcurrentHashMap<>();

    public TabListFeature(ProxyServer proxy, Messages messages, NameFormatter names) {
        this.proxy = proxy;
        this.messages = messages;
        this.names = names;
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        added.remove(event.getPlayer().getUniqueId()); // Velocity cleared that list on the switch
        named.remove(event.getPlayer().getUniqueId());
        refreshAll();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID gone = event.getPlayer().getUniqueId();
        added.remove(gone);
        named.remove(gone);
        for (Player viewer : proxy.getAllPlayers()) {
            Set<UUID> mine = added.get(viewer.getUniqueId());
            if (mine != null && mine.remove(gone)) {
                viewer.getTabList().removeEntry(gone);
            }
            Map<UUID, Component> given = named.get(viewer.getUniqueId());
            if (given != null) {
                given.remove(gone);
            }
        }
        refreshAll();
    }

    /** Called by the timer and after every change. */
    public void refreshAll() {
        List<Player> online = new ArrayList<>();
        for (Player p : proxy.getAllPlayers()) {
            if (p.getCurrentServer().isPresent()) {
                online.add(p);
            }
        }
        for (Player viewer : online) {
            refresh(viewer, online);
        }
    }

    void refresh(Player viewer, List<Player> online) {
        TabList list = viewer.getTabList();
        list.setHeaderAndFooter(
                messages.render(KEY_HEADER, Placeholder.unparsed("online", Integer.toString(online.size())),
                        Placeholder.unparsed("ping", Long.toString(viewer.getPing()))),
                messages.render(KEY_FOOTER, Placeholder.unparsed("online", Integer.toString(online.size())),
                        Placeholder.unparsed("ping", Long.toString(viewer.getPing()))));
        Set<UUID> mine = added.computeIfAbsent(viewer.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        Map<UUID, Component> given = named.computeIfAbsent(viewer.getUniqueId(), k -> new ConcurrentHashMap<>());
        Set<UUID> stillOnline = new HashSet<>();
        for (Player other : online) {
            UUID id = other.getUniqueId();
            stillOnline.add(id);
            Optional<TabListEntry> existing = list.getEntry(id);
            if (existing.isPresent() && mine.contains(id)) {
                TabListEntry entry = existing.get();
                Component name = homeName(other).orElseGet(() -> names.nameOf(other));
                if (!name.equals(entry.getDisplayNameComponent().orElse(null))) {
                    entry.setDisplayName(name);
                }
                entry.setLatency((int) other.getPing());
            } else if (existing.isPresent()) {
                // The backend's own entry: its name stays, unless the backend gave none (or only the one set here)
                TabListEntry entry = existing.get();
                Optional<Component> shown = entry.getDisplayNameComponent();
                if (shown.isEmpty() || shown.get().equals(given.get(id))) {
                    Component name = names.nameOf(other);
                    if (!name.equals(shown.orElse(null))) {
                        entry.setDisplayName(name);
                    }
                    given.put(id, name);
                } else {
                    given.remove(id);
                }
            } else if (!id.equals(viewer.getUniqueId())) {
                Component name = homeName(other).orElseGet(() -> names.nameOf(other));
                // The 7-argument form (listOrder, no showHat): the live proxy (git-91bdcebb-b467) has no 8-argument
                // buildEntry, although the newer API snapshot we compile against does (bug B3).
                list.addEntry(list.buildEntry(other.getGameProfile(), name, (int) other.getPing(), 0, null, true, 0));
                mine.add(id);
            }
        }
        for (UUID id : new ArrayList<>(mine)) {
            if (!stillOnline.contains(id)) {
                list.removeEntry(id);
                mine.remove(id);
            }
        }
        given.keySet().retainAll(stillOnline);
    }

    /**
     * The name a player's own backend gives them: their own entry in their own tab list (a backend always lists the
     * player themself). Empty when the backend gives none, or only the fallback this class put there.
     */
    private Optional<Component> homeName(Player player) {
        UUID id = player.getUniqueId();
        Optional<Component> own = player.getTabList().getEntry(id).flatMap(TabListEntry::getDisplayNameComponent);
        Map<UUID, Component> given = named.get(id);
        if (own.isPresent() && given != null && own.get().equals(given.get(id))) {
            return Optional.empty();
        }
        return own;
    }
}
