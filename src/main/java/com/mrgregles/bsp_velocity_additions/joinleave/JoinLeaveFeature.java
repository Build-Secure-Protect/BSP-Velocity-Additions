package com.mrgregles.bsp_velocity_additions.joinleave;

import com.mrgregles.bsp_velocity_additions.config.Messages;
import com.mrgregles.bsp_velocity_additions.config.PluginConfig;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feature 4: one join line when a player lands on their first backend, one leave line when they disconnect from
 * the proxy, and nothing in between. Server switches are never announced (owner decision D15: a move line would
 * let players track each other). A switch between backends never raises {@link DisconnectEvent}, which is what
 * makes this work without Forge help. The backends' own vanilla lines are a separate matter (request VA-T1 to
 * BSP-Teleport).
 */
public final class JoinLeaveFeature {

    public static final String KEY_JOIN = "joinLeave.join";
    public static final String KEY_LEAVE = "joinLeave.leave";

    private final ProxyServer proxy;
    private final PluginConfig.JoinLeave config;
    private final Messages messages;
    private final Logger logger;
    /** Players who have landed on a backend, with the server they are on; a leave line goes out only for them. */
    private final Map<UUID, String> onNetwork = new ConcurrentHashMap<>();

    public JoinLeaveFeature(ProxyServer proxy, PluginConfig.JoinLeave config, Messages messages, Logger logger) {
        this.proxy = proxy;
        this.config = config;
        this.messages = messages;
        this.logger = logger;
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        String server = player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("");
        String previous = onNetwork.put(player.getUniqueId(), server);
        if (previous == null) {
            announce(player, KEY_JOIN, Placeholder.unparsed("player", player.getUsername()),
                    Placeholder.unparsed("server", server));
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        String server = onNetwork.remove(player.getUniqueId());
        if (server == null) {
            return; // never reached a backend, so nobody saw a join line
        }
        announce(player, KEY_LEAVE, Placeholder.unparsed("player", player.getUsername()),
                Placeholder.unparsed("server", server));
    }

    /** For tests and later features: whether a join line has gone out for this player. */
    public boolean isOnNetwork(UUID player) {
        return onNetwork.containsKey(player);
    }

    private void announce(Player subject, String key, TagResolver... placeholders) {
        if (config.hasSilentPermission() && subject.hasPermission(config.silentPermission())) {
            return;
        }
        Component line = messages.render(key, placeholders);
        for (Player p : proxy.getAllPlayers()) {
            p.sendMessage(line);
        }
        Audience console = proxy.getConsoleCommandSource();
        if (console != null) {
            console.sendMessage(line);
        }
    }

    static String name(RegisteredServer server) {
        return server.getServerInfo().getName();
    }
}
