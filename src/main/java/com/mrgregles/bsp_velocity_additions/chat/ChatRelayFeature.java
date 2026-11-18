package com.mrgregles.bsp_velocity_additions.chat;

import com.mrgregles.bsp_velocity_additions.config.Messages;
import com.mrgregles.bsp_velocity_additions.config.PluginConfig;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feature 2, shared chat (decisions D5, D6; BSP-Core request VA-C2). BSP-Core sends every public chat line to the
 * proxy on {@code bsp_core:proxy} through the speaker's own connection; this class forwards it to the players on
 * every other server. The backend is the authority on what was public: team chat and blocked chat never arrive.
 * Only messages from a backend are trusted; a client sending bytes on the channel is dropped.
 *
 * <p>Every relayed line is also kept in {@link ChatHistory}, and a player joining the network gets the recent ones
 * (T25, D37), rendered for them exactly as live relayed lines, between {@code chatHistory.header} and
 * {@code chatHistory.footer}, before the network join line.
 */
public final class ChatRelayFeature {

    public static final String CHANNEL = "bsp_core:proxy";
    public static final MinecraftChannelIdentifier IDENTIFIER = MinecraftChannelIdentifier.from(CHANNEL);
    public static final String KEY_RELAY = "chat.relay";
    public static final String KEY_RELAY_STAFF = "chat.relayStaff";
    public static final String KEY_HISTORY_HEADER = "chatHistory.header";
    public static final String KEY_HISTORY_FOOTER = "chatHistory.footer";

    private final ProxyServer proxy;
    private final PluginConfig.Chat config;
    private final PluginConfig.Players players;
    private final Messages messages;
    private final ChatHistory history;
    private final Logger logger;
    private final Set<String> nameMismatchWarned = ConcurrentHashMap.newKeySet();

    public ChatRelayFeature(ProxyServer proxy, PluginConfig.Chat config, PluginConfig.Players players, Messages messages,
                            ChatHistory history, Logger logger) {
        this.proxy = proxy;
        this.config = config;
        this.players = players;
        this.messages = messages;
        this.history = history;
        this.logger = logger;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier().getId())) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled()); // never on to the client, never from one
        if (!(event.getSource() instanceof ServerConnection origin)) {
            return;
        }
        Optional<RelayPayload> decoded = RelayPayload.decode(event.getData());
        if (decoded.isEmpty() || !decoded.get().isChat()) {
            return;
        }
        RelayPayload payload = decoded.get();
        String originServer = origin.getServerInfo().getName();
        if (!payload.server().equals(originServer) && nameMismatchWarned.add(originServer)) {
            logger.warn("BSP-Core on {} calls itself '{}' (storage.serverId); the names must match velocity.toml",
                    originServer, payload.server());
        }
        history.add(originServer, payload);
        Component line = line(payload);
        Component everyone = render(KEY_RELAY, payload, originServer, line);
        Component staff = render(KEY_RELAY_STAFF, payload, originServer, line);
        for (Player viewer : proxy.getAllPlayers()) {
            Optional<ServerConnection> on = viewer.getCurrentServer();
            if (on.isEmpty() || on.get().getServerInfo().getName().equals(originServer)) {
                continue; // not in chat yet, or already saw the backend's own broadcast
            }
            viewer.sendMessage(seesServers(viewer) ? staff : everyone);
        }
        if (config.logToConsole()) {
            // From the payload's own fields: the backend's line is usually the translatable chat.type.text, which a
            // plain serializer turns into its key, not the words (bug B4). Players' clients translate it themselves.
            logger.info("[{}] {}", originServer, "<" + payload.name() + "> " + payload.text());
        }
    }

    /**
     * A network join (no previous server): the recent chat, before the join line ({@code JoinLeaveFeature} listens at
     * the normal order). Nothing at all when there is none. A server switch sends nothing: the client keeps its chat.
     */
    @Subscribe(order = PostOrder.EARLY)
    public void onServerPostConnect(ServerPostConnectEvent event) {
        if (event.getPreviousServer() != null) {
            return;
        }
        List<ChatHistory.Line> recent = history.recent();
        if (recent.isEmpty()) {
            return;
        }
        Player viewer = event.getPlayer();
        String key = seesServers(viewer) ? KEY_RELAY_STAFF : KEY_RELAY;
        sendIfNotBlank(viewer, messages.render(KEY_HISTORY_HEADER));
        for (ChatHistory.Line old : recent) {
            viewer.sendMessage(render(key, old.payload(), old.server(), line(old.payload())));
        }
        sendIfNotBlank(viewer, messages.render(KEY_HISTORY_FOOTER));
    }

    private boolean seesServers(Player viewer) {
        return players.hasServersPermission() && viewer.hasPermission(players.serversPermission());
    }

    private static void sendIfNotBlank(Player viewer, Component line) {
        if (!PlainTextComponentSerializer.plainText().serialize(line).isBlank()) {
            viewer.sendMessage(line);
        }
    }

    /** The line as the backend showed it, or a plain {@code <Name> text} when asked to or when the JSON is unusable. */
    Component line(RelayPayload payload) {
        if (config.useBackendFormatting() && !payload.json().isBlank()) {
            try {
                return GsonComponentSerializer.gson().deserialize(payload.json());
            } catch (RuntimeException badJson) {
                // fall through to the plain form
            }
        }
        return Component.text("<" + payload.name() + "> " + payload.text());
    }

    private Component render(String key, RelayPayload payload, String server, Component line) {
        return messages.render(key,
                Placeholder.component("line", line),
                Placeholder.unparsed("server", server),
                ServerTag.placeholder(server, messages),
                Placeholder.unparsed("player", payload.name()),
                Placeholder.unparsed("text", payload.text()));
    }
}
