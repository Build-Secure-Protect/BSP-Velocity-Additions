package com.mrgregles.bsp_velocity_additions.lastserver;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Which backend servers are up. Two signals, in order of trust:
 * <ol>
 *   <li>A server with any player connected is up.</li>
 *   <li>Otherwise a plain TCP connection to the server's address, opened and closed again, with a short timeout.
 *   A Minecraft server binds its port just before it reports "Done", so an open port means ready.</li>
 * </ol>
 * A status ping is deliberately not used (bug B1): a Forge backend packs its mod list into the status reply, and
 * with a large pack Velocity cannot decode it, which looked like "no answer". A server nobody has checked yet
 * counts as up. Owner decision D13.
 */
public final class ServerReachability {

    public static final Duration CHECK_TIMEOUT = Duration.ofSeconds(3);

    private final Map<String, Boolean> reachable = new ConcurrentHashMap<>();
    private final Logger logger;
    private final Executor checks;
    private final ProxyServer proxy;

    /**
     * @param checks runs the blocking connection checks, one task per server (the proxy scheduler in production)
     */
    public ServerReachability(Logger logger, Executor checks, ProxyServer proxy) {
        this.logger = logger;
        this.checks = checks;
        this.proxy = proxy;
    }

    public boolean isReachable(String serverName) {
        if (hasPlayers(serverName)) {
            return true;
        }
        return reachable.getOrDefault(key(serverName), true);
    }

    public void mark(String serverName, boolean up) {
        Boolean before = reachable.put(key(serverName), up);
        if (before == null || before != up) {
            logger.info("Server {} is {}", serverName, up ? "reachable" : "not answering");
        }
    }

    /** Checks every registered server; results arrive on the {@code checks} executor. */
    public void refresh(ProxyServer proxy) {
        for (RegisteredServer server : proxy.getAllServers()) {
            String name = server.getServerInfo().getName();
            if (!server.getPlayersConnected().isEmpty()) {
                mark(name, true);
                continue;
            }
            InetSocketAddress address = server.getServerInfo().getAddress();
            checks.execute(() -> {
                IOException failure = connect(address);
                if (failure != null && reachable.getOrDefault(key(name), true)) {
                    logger.info("Connection check to {} ({}) failed: {}", name, address, rootCause(failure));
                }
                mark(name, failure == null);
            });
        }
    }

    /** Opens and closes one TCP connection. Returns the failure, or null when the port accepted. */
    static IOException connect(InetSocketAddress address) {
        try (Socket socket = new Socket()) {
            socket.connect(address, (int) CHECK_TIMEOUT.toMillis());
            return null;
        } catch (IOException e) {
            return e;
        }
    }

    /** The innermost cause, for a one-line log. */
    static String rootCause(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    private boolean hasPlayers(String serverName) {
        return proxy.getServer(serverName).map(s -> !s.getPlayersConnected().isEmpty()).orElse(false);
    }

    private static String key(String serverName) {
        return serverName.toLowerCase(Locale.ROOT);
    }
}
