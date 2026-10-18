package com.mrgregles.bsp_velocity_additions.players;

import com.mrgregles.bsp_velocity_additions.config.Messages;
import com.mrgregles.bsp_velocity_additions.config.PluginConfig;
import com.mrgregles.bsp_velocity_additions.tablist.NameFormatter;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code /players} (alias {@code /online}): names only for everyone; grouped by server, with counts, for the
 * console and for players with {@code players.serversPermission} (decisions D8, D16).
 */
public final class PlayersCommand implements SimpleCommand {

    public static final String KEY_LIST = "players.list";
    public static final String KEY_NONE = "players.none";
    public static final String KEY_STAFF_HEADER = "players.staffHeader";
    public static final String KEY_STAFF_SERVER = "players.staffServer";
    public static final String KEY_SEPARATOR = "players.separator";

    private final ProxyServer proxy;
    private final PluginConfig.Players config;
    private final Messages messages;
    private final NameFormatter names;

    public PlayersCommand(ProxyServer proxy, PluginConfig.Players config, Messages messages, NameFormatter names) {
        this.proxy = proxy;
        this.config = config;
        this.messages = messages;
        this.names = names;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        List<Player> online = new ArrayList<>();
        for (Player p : proxy.getAllPlayers()) {
            if (p.getCurrentServer().isPresent()) {
                online.add(p);
            }
        }
        online.sort(Comparator.comparing(Player::getUsername, String.CASE_INSENSITIVE_ORDER));
        if (online.isEmpty()) {
            source.sendMessage(messages.render(KEY_NONE));
            return;
        }
        if (mayseeServers(source)) {
            Map<String, List<Player>> byServer = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Player p : online) {
                byServer.computeIfAbsent(p.getCurrentServer().get().getServerInfo().getName(), k -> new ArrayList<>()).add(p);
            }
            source.sendMessage(messages.render(KEY_STAFF_HEADER,
                    Placeholder.unparsed("online", Integer.toString(online.size())),
                    Placeholder.unparsed("servers", Integer.toString(byServer.size()))));
            byServer.forEach((server, players) -> source.sendMessage(messages.render(KEY_STAFF_SERVER,
                    Placeholder.unparsed("server", server),
                    Placeholder.unparsed("count", Integer.toString(players.size())),
                    Placeholder.component("names", join(players)))));
            return;
        }
        source.sendMessage(messages.render(KEY_LIST,
                Placeholder.unparsed("online", Integer.toString(online.size())),
                Placeholder.component("names", join(online))));
    }

    private boolean mayseeServers(CommandSource source) {
        if (source instanceof ConsoleCommandSource) {
            return true;
        }
        return config.hasServersPermission() && source.hasPermission(config.serversPermission());
    }

    private Component join(List<Player> players) {
        List<Component> parts = new ArrayList<>(players.size());
        for (Player p : players) {
            parts.add(names.nameOf(p));
        }
        return Component.join(JoinConfiguration.separator(messages.render(KEY_SEPARATOR)), parts);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true; // open to everyone; the server breakdown is gated inside
    }
}
