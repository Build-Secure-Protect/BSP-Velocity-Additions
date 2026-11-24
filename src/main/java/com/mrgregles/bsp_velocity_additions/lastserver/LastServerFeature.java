package com.mrgregles.bsp_velocity_additions.lastserver;

import com.mrgregles.bsp_velocity_additions.config.PluginConfig;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    /**
     * Players reset (PR21) while online, with the session (the {@link Player} object) that was online then: nothing
     * is recorded for that session. Compared by identity, so a refused duplicate login or a later session of the same
     * player is never mistaken for it.
     */
    private final Map<UUID, Player> resetThisSession = new ConcurrentHashMap<>();

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
        Player player = event.getPlayer();
        Player held = resetThisSession.get(player.getUniqueId());
        if (held == player) {
            return;
        }
        if (held != null) {
            // left over from a session that ended before the hold was set; this is a new session
            releaseHold(player.getUniqueId(), held);
        }
        if (!config.isIgnored(server)) {
            store.put(player.getUniqueId(), player.getUsername(), server);
        }
    }

    /**
     * A player reset (PR21): forgets the player's last server so their next join goes where a new player's does.
     * Only an entry last changed before {@code resetMs} is forgotten, so a late or repeated notice never forgets a
     * player who has joined a server since. A player who is online ({@code session}) when an entry is forgotten
     * keeps playing, but nothing is recorded for that session, so a server switch before they leave the network
     * does not bring a last server back. Any thread; idempotent.
     *
     * @return the server that was forgotten; empty when nothing older than the reset was on file
     */
    public Optional<String> forget(UUID player, long resetMs, Optional<Player> session) {
        Optional<String> forgotten = store.remove(player, resetMs);
        if (forgotten.isPresent() && session.isPresent()) {
            Player online = session.get();
            resetThisSession.put(player, online);
            if (!online.isActive()) {
                // they left between the lookup and the hold: no session to hold
                releaseHold(player, online);
            }
        }
        return forgotten;
    }

    /** True while the given session is held after a reset. */
    boolean isHeld(Player session) {
        return resetThisSession.get(session.getUniqueId()) == session;
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (event.getLoginStatus() == DisconnectEvent.LoginStatus.CONFLICTING_LOGIN) {
            return; // a refused duplicate login; the real session is still online
        }
        releaseHold(event.getPlayer().getUniqueId(), event.getPlayer());
    }

    private void releaseHold(UUID player, Player session) {
        resetThisSession.computeIfPresent(player, (id, held) -> held == session ? null : held);
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
