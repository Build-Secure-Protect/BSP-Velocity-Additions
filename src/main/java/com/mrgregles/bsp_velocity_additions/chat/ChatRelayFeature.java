package com.mrgregles.bsp_velocity_additions.chat;

import com.mrgregles.bsp_velocity_additions.config.Messages;
import com.mrgregles.bsp_velocity_additions.config.PluginConfig;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feature 2, shared chat (decisions D5, D6; BSP-Core request VA-C2). BSP-Core sends every public chat line to the
 * proxy on {@code bsp_core:proxy} through the speaker's own connection; this class forwards it to the players on
 * every other server. The backend is the authority on what was public: team chat and blocked chat never arrive.
 * Only messages from a backend are trusted; a client sending bytes on the channel is dropped.
 */
public final class ChatRelayFeature {

    public static final String CHANNEL = "bsp_core:proxy";
    public static final MinecraftChannelIdentifier IDENTIFIER = MinecraftChannelIdentifier.from(CHANNEL);
    public static final String KEY_RELAY = "chat.relay";
    public static final String KEY_RELAY_STAFF = "chat.relayStaff";

    private final ProxyServer proxy;
    private final PluginConfig.Chat config;
    private final PluginConfig.Players players;
    private final Messages messages;
    private final Logger logger;
    private final Set<String> nameMismatchWarned = ConcurrentHashMap.newKeySet();

    public ChatRelayFeature(ProxyServer proxy, PluginConfig.Chat config, PluginConfig.Players players, Messages messages, Logger logger) {
        this.proxy = proxy;
        this.config = config;
        this.players = players;
        this.messages = messages;
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
        Component line = line(payload);
        Component everyone = render(KEY_RELAY, payload, originServer, line);
        Component staff = render(KEY_RELAY_STAFF, payload, originServer, line);
        for (Player viewer : proxy.getAllPlayers()) {
            Optional<ServerConnection> on = viewer.getCurrentServer();
            if (on.isEmpty() || on.get().getServerInfo().getName().equals(originServer)) {
                continue; // not in chat yet, or already saw the backend's own broadcast
            }
            boolean seesServers = players.hasServersPermission() && viewer.hasPermission(players.serversPermission());
            viewer.sendMessage(seesServers ? staff : everyone);
        }
        if (config.logToConsole()) {
            logger.info("[{}] {}", originServer, PlainTextComponentSerializer.plainText().serialize(line));
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
                Placeholder.unparsed("player", payload.name()),
                Placeholder.unparsed("text", payload.text()));
    }
}
