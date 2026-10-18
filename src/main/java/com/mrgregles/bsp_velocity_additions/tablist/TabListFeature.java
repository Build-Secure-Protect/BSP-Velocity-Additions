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
 * player's tab list with the players on that backend; this class adds the rest, keeps the names in the agreed
 * format, and sets the header and footer. Velocity empties a tab list on every server switch, so every switch
 * and disconnect triggers a refresh, and a timer repeats it for the footer and for names the backend overwrote.
 */
public final class TabListFeature {

    public static final String KEY_HEADER = "tabList.header";
    public static final String KEY_FOOTER = "tabList.footer";

    private final ProxyServer proxy;
    private final Messages messages;
    private final NameFormatter names;
    /** Entries this plugin added to each viewer's list, so only those are removed again. */
    private final Map<UUID, Set<UUID>> added = new ConcurrentHashMap<>();

    public TabListFeature(ProxyServer proxy, Messages messages, NameFormatter names) {
        this.proxy = proxy;
        this.messages = messages;
        this.names = names;
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        added.remove(event.getPlayer().getUniqueId()); // Velocity cleared that list on the switch
        refreshAll();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID gone = event.getPlayer().getUniqueId();
        added.remove(gone);
        for (Player viewer : proxy.getAllPlayers()) {
            Set<UUID> mine = added.get(viewer.getUniqueId());
            if (mine != null && mine.remove(gone)) {
                viewer.getTabList().removeEntry(gone);
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
        Set<UUID> stillOnline = new HashSet<>();
        for (Player other : online) {
            stillOnline.add(other.getUniqueId());
            Component name = names.nameOf(other);
            Optional<TabListEntry> existing = list.getEntry(other.getUniqueId());
            if (existing.isPresent()) {
                TabListEntry entry = existing.get();
                if (!name.equals(entry.getDisplayNameComponent().orElse(null))) {
                    entry.setDisplayName(name);
                }
                if (mine.contains(other.getUniqueId())) {
                    entry.setLatency((int) other.getPing());
                }
            } else if (!other.getUniqueId().equals(viewer.getUniqueId())) {
                list.addEntry(list.buildEntry(other.getGameProfile(), name, (int) other.getPing(), 0, null, true, 0, true));
                mine.add(other.getUniqueId());
            }
        }
        for (UUID id : new ArrayList<>(mine)) {
            if (!stillOnline.contains(id)) {
                list.removeEntry(id);
                mine.remove(id);
            }
        }
    }
}
