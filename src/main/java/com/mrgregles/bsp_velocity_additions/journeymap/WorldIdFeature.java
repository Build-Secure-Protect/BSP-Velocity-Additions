package com.mrgregles.bsp_velocity_additions.journeymap;

import com.mrgregles.bsp_velocity_additions.config.PluginConfig;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChannelRegisterEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JourneyMap world ids from the proxy (T19, decisions D28 to D30). Behind Velocity every backend has the same
 * address, so the JourneyMap client can keep their maps apart only by the world id. Today no backend sends one
 * ({@code useWorldId} is false on all of them), so every server's map lands in one client folder.
 * <ul>
 *   <li>A client's request on {@code worldinfo:world_id} is answered here with the id of the backend the player is on
 *   ({@link WorldIds}) and never forwarded.</li>
 *   <li>A backend's own reply on the channel is dropped, so it can never race the proxy's.</li>
 *   <li>After every server switch the id of the new backend is sent again, {@code switchResendDelayMs} after
 *   {@link ServerPostConnectEvent}, to players whose client has asked at least once this session (so a client without
 *   JourneyMap never gets a packet on a channel it does not know). JourneyMap 6.0.7 takes an id it did not ask for
 *   and remaps when it changes; the same id again changes nothing.</li>
 * </ul>
 * The backend a player is on is taken from {@link ServerConnectedEvent}, which fires as soon as the new backend has
 * accepted them, so a request that arrives during a switch is answered with the new backend's id.
 *
 * <p>Velocity raises {@link PluginMessageEvent} only for channels a plugin has registered (checked in the 3.4.0 proxy:
 * {@code getFromId} before the event), so the channel is registered at start. For the same reason
 * {@code debugAllChannels} cannot see every message; it logs the channels each client registers instead, and the
 * messages on the channels the proxy does listen to.
 */
public final class WorldIdFeature {

    public static final String CHANNEL = "worldinfo:world_id";
    public static final MinecraftChannelIdentifier IDENTIFIER = MinecraftChannelIdentifier.from(CHANNEL);

    /** Runs a task later on the proxy's scheduler; the tests turn time by hand. */
    public interface Later {
        void after(long millis, Runnable task);
    }

    private final PluginConfig.JourneyMap config;
    private final WorldIds ids;
    private final Later later;
    private final Logger logger;

    private final Map<UUID, String> backendOf = new ConcurrentHashMap<>();
    private final Map<UUID, Long> switchNumber = new ConcurrentHashMap<>();
    private final Set<UUID> hasJourneyMap = ConcurrentHashMap.newKeySet();

    public WorldIdFeature(PluginConfig.JourneyMap config, WorldIds ids, Later later, Logger logger) {
        this.config = config;
        this.ids = ids;
        this.later = later;
        this.logger = logger;
    }

    // Early, so the drop happens before any other plugin forwards the message.
    @Subscribe(order = PostOrder.FIRST)
    public void onPluginMessage(PluginMessageEvent event) {
        boolean ours = event.getIdentifier().getId().equals(CHANNEL);
        if (config.debugAllChannels()) {
            logger.info("[journeyMap] plugin message {} -> {} on {} ({} bytes)", describe(event.getSource()),
                    describe(event.getTarget()), event.getIdentifier().getId(), event.getData().length);
        }
        if (!ours) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (event.getSource() instanceof Player player) {
            hasJourneyMap.add(player.getUniqueId());
            debug("client -> proxy", player, event.getData());
            answer(player, "reply");
        } else if (event.getSource() instanceof ServerConnection server) {
            debug("backend -> client (dropped)", server.getPlayer(), event.getData());
        }
    }

    /** {@code debugAllChannels}: the channels a client announces, which shows any other JourneyMap channel. */
    @Subscribe
    public void onChannelRegister(PlayerChannelRegisterEvent event) {
        if (config.debugAllChannels()) {
            logger.info("[journeyMap] client {} registers channels: {}", event.getPlayer().getUsername(),
                    event.getChannels().stream().map(ChannelIdentifier::getId).sorted().toList());
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        backendOf.put(event.getPlayer().getUniqueId(), event.getServer().getServerInfo().getName());
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        long number = switchNumber.merge(uuid, 1L, Long::sum);
        if (!hasJourneyMap.contains(uuid)) {
            return;
        }
        later.after(config.switchResendDelayMs(), () -> {
            if (switchNumber.getOrDefault(uuid, -1L) == number && player.isActive() && hasJourneyMap.contains(uuid)) {
                answer(player, "resend after switch");
            }
        });
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        backendOf.remove(uuid);
        switchNumber.remove(uuid);
        hasJourneyMap.remove(uuid);
    }

    private void answer(Player player, String why) {
        Optional<String> backend = backendName(player);
        if (backend.isEmpty()) {
            if (config.debug()) {
                logger.info("[journeyMap] {} is on no server yet; no id sent ({})", player.getUsername(), why);
            }
            return;
        }
        byte[] data = WorldIdPacket.encode(ids.idOf(backend.get()));
        player.sendPluginMessage(IDENTIFIER, data);
        if (config.debug()) {
            logger.info("[journeyMap] proxy -> client ({}) {} on {}: {} = {}", why, player.getUsername(), backend.get(),
                    WorldIdPacket.hex(data), ids.idOf(backend.get()));
        }
    }

    private Optional<String> backendName(Player player) {
        String known = backendOf.get(player.getUniqueId());
        if (known != null) {
            return Optional.of(known);
        }
        return player.getCurrentServer().map(s -> s.getServerInfo().getName());
    }

    private void debug(String direction, Player player, byte[] data) {
        if (!config.debug()) {
            return;
        }
        String backend = backendName(player).orElse("no server");
        String id = WorldIdPacket.decode(data).map(s -> s.isEmpty() ? "(request, no id)" : s).orElse("(not a world id packet)");
        logger.info("[journeyMap] {} {} on {}: {} = {}", direction, player.getUsername(), backend, WorldIdPacket.hex(data), id);
    }

    private static String describe(Object side) {
        if (side instanceof Player p) {
            return "client " + p.getUsername();
        }
        if (side instanceof ServerConnection s) {
            return "backend " + s.getServerInfo().getName();
        }
        return String.valueOf(side);
    }
}
