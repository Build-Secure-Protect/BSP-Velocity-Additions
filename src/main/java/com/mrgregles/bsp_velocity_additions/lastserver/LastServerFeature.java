package com.mrgregles.bsp_velocity_additions.lastserver;

import com.mrgregles.bsp_velocity_additions.config.PluginConfig;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Feature 1, "remember the last server" (project brief, decisions D1, D2, D10, D13).
 * Listens on the proxy's event bus; the decision itself lives in {@link InitialServerChooser}.
 */
public final class LastServerFeature {

    private final PluginConfig.LastServer config;
    private final LastServerStore store;
    private final ServerReachability reachability;
    private final InitialServerChooser chooser;
    private final Logger logger;

    public LastServerFeature(PluginConfig.LastServer config, LastServerStore store, ServerReachability reachability,
                             InitialServerChooser chooser, Logger logger) {
        this.config = config;
        this.store = store;
        this.reachability = reachability;
        this.chooser = chooser;
        this.logger = logger;
    }

    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();
        Optional<String> remembered = store.get(player.getUniqueId());
        InitialServerChooser.Choice choice = chooser.choose(remembered);
        if (choice.server().isEmpty()) {
            if (remembered.isPresent()) {
                logger.warn("{}'s last server {} is not usable and no fallback answers; leaving Velocity's choice",
                        player.getUsername(), remembered.get());
            }
            return;
        }
        RegisteredServer target = choice.server().get();
        event.setInitialServer(target);
        switch (choice.reason()) {
            case REMEMBERED -> logger.info("{} returns to {}", player.getUsername(), name(target));
            case FALLBACK_INSTEAD_OF_OFFLINE -> logger.info("{}'s last server {} is offline; sending them to {}",
                    player.getUsername(), remembered.orElse("?"), name(target));
            case FALLBACK_INSTEAD_OF_UNKNOWN -> logger.info("{}'s last server {} no longer exists; sending them to {}",
                    player.getUsername(), remembered.orElse("?"), name(target));
            default -> { }
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        String server = name(event.getServer());
        reachability.mark(server, true);
        if (!config.isIgnored(server)) {
            store.put(event.getPlayer().getUniqueId(), event.getPlayer().getUsername(), server);
        }
    }

    /**
     * The guard behind D13: when the first connection fails (the remembered server went down since the last ping),
     * send the player to spawn instead of letting Velocity disconnect them. A player kicked later, whom Velocity
     * would disconnect, is also sent to spawn.
     */
    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        String failed = name(event.getServer());
        reachability.mark(failed, false);
        boolean initial = event.kickedDuringServerConnect() && event.getPlayer().getCurrentServer().isEmpty();
        boolean velocityGivesUp = event.getResult() instanceof KickedFromServerEvent.DisconnectPlayer;
        if (!initial && !velocityGivesUp) {
            return;
        }
        Optional<RegisteredServer> spawn = chooser.fallback(Set.of(failed.toLowerCase(Locale.ROOT)));
        if (spawn.isEmpty()) {
            logger.warn("{} could not join {} and no fallback server answers", event.getPlayer().getUsername(), failed);
            return;
        }
        logger.info("{} could not join {}; sending them to {}", event.getPlayer().getUsername(), failed, name(spawn.get()));
        event.setResult(KickedFromServerEvent.RedirectPlayer.create(spawn.get()));
    }

    private static String name(RegisteredServer server) {
        return server.getServerInfo().getName();
    }
}
