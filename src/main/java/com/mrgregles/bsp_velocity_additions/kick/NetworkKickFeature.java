package com.mrgregles.bsp_velocity_additions.kick;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Network kicks (BSP-Server Utilities request SU-VA2, decision D36). A backend's own kick may let Velocity move the
 * player to another server; a kick asked for here disconnects them from the proxy, so they leave the network. Server
 * Utilities sends the request over the kicked player's connection and kicks them itself after 3 s if they are still
 * there. Only a backend is trusted: bytes on the channel from a client are dropped, and nothing is forwarded.
 *
 * <p>The channel is registered and swallowed even with {@code networkKick.enabled = false}: Velocity passes a message
 * on an unregistered channel straight on to the client (checked in {@code BackendPlaySessionHandler}), so switching
 * the kick off must not let Server Utilities' message reach the player. Off, it is dropped unread.
 */
public final class NetworkKickFeature {

    public static final String CHANNEL = "bsp_server_utilities:proxy";
    public static final MinecraftChannelIdentifier IDENTIFIER = MinecraftChannelIdentifier.from(CHANNEL);
    /** The reason when the backend's JSON cannot be read: vanilla's "Kicked by an operator". */
    static final Component DEFAULT_REASON = Component.translatable("multiplayer.disconnect.kicked");

    private final ProxyServer proxy;
    private final boolean enabled;
    private final Logger logger;

    public NetworkKickFeature(ProxyServer proxy, boolean enabled, Logger logger) {
        this.proxy = proxy;
        this.enabled = enabled;
        this.logger = logger;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier().getId())) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled()); // never on to the client, never from one
        if (!enabled || !(event.getSource() instanceof ServerConnection origin)) {
            return;
        }
        Optional<KickPayload> decoded = KickPayload.decode(event.getData());
        if (decoded.isEmpty() || !decoded.get().isKick()) {
            return; // unknown versions and kinds are ignored, so Server Utilities may add kinds later
        }
        Optional<Player> target = proxy.getPlayer(decoded.get().player());
        if (target.isEmpty()) {
            return; // already gone
        }
        Component reason = reason(decoded.get().reasonJson());
        Player player = target.get();
        player.disconnect(reason);
        // Plain text of the reason as sent: a translatable reason shows its key here, its words on the client.
        logger.info("[{}] kicked {}: {}", origin.getServerInfo().getName(), player.getUsername(),
                PlainTextComponentSerializer.plainText().serialize(reason));
    }

    static Component reason(String json) {
        if (json.isBlank()) {
            return DEFAULT_REASON;
        }
        try {
            return GsonComponentSerializer.gson().deserialize(json);
        } catch (RuntimeException badJson) {
            return DEFAULT_REASON;
        }
    }
}
