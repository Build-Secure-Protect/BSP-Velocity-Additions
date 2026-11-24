package com.mrgregles.bsp_velocity_additions.reset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mrgregles.bsp_velocity_additions.config.Messages;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * BSP-Core's {@code /bsp season reset} on the proxy (decision D45; BSP-Core's SR2 and SR15). For a {@code season_reset}
 * notice on BSP-Core's Redis channel it forgets every player's last server, so everyone's next join goes to the
 * fallback server, then disconnects every player on the network with {@code seasonReset.kick}. BSP-Core also sends
 * the players of each backend off; the proxy's disconnect makes it network-wide at once. The Discord links and store
 * records are left alone.
 * <p>
 * The highest reset number applied is kept in {@code season-reset.json}, so the same notice twice (or an older one)
 * does nothing, across proxy restarts too. Redis pub/sub keeps nothing: a reset sent while the proxy or Redis is down
 * never reaches the proxy (BSP-Core still sends each backend's players off, and each backend's own reset still runs).
 */
public final class SeasonResetFeature {

    public static final String FILE_NAME = "season-reset.json";
    static final String KEY_KICK = "seasonReset.kick";
    static final String KEY_KICK_NO_NUMBER = "seasonReset.kickNoNumber";

    /** What the reset forgets on the proxy: {@code LastServerFeature::forgetAll}. */
    @FunctionalInterface
    public interface AllForgetter {
        /**
         * Forgets entries last changed before {@code resetMs}; {@code online} are the sessions about to be
         * disconnected, held so a switch in between is not recorded.
         *
         * @return how many last servers were forgotten
         */
        int forgetAll(long resetMs, Collection<Player> online);
    }

    /** {@code season-reset.json}: the last reset applied here. */
    record Applied(int lastResetNumber, long appliedAt, String origin) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final AllForgetter lastServers;
    private final Supplier<Collection<Player>> players;
    private final Messages messages;
    private final Path file;
    private final LongSupplier clock;
    private final Logger logger;
    private int lastApplied;

    /**
     * @param lastServers null when the last-server feature is off (nothing to forget; players are still disconnected)
     * @param file        {@code season-reset.json}; null keeps the number in memory only (tests)
     */
    SeasonResetFeature(AllForgetter lastServers, Supplier<Collection<Player>> players, Messages messages, Path file,
                       LongSupplier clock, Logger logger) {
        this.lastServers = lastServers;
        this.players = players;
        this.messages = messages;
        this.file = file;
        this.clock = clock;
        this.logger = logger;
        this.lastApplied = load();
    }

    public static SeasonResetFeature create(AllForgetter lastServers, Supplier<Collection<Player>> players,
                                            Messages messages, Path dataDirectory, Logger logger) {
        return new SeasonResetFeature(lastServers, players, messages, dataDirectory.resolve(FILE_NAME),
                System::currentTimeMillis, logger);
    }

    /** The highest reset number applied here (0 before the first). */
    synchronized int lastApplied() {
        return lastApplied;
    }

    /**
     * One message from BSP-Core's channel, on the Redis thread. True when it was a new season reset and was applied;
     * false for every other message, an unreadable notice (one warning) and a reset already applied (one info line).
     */
    synchronized boolean onRedisMessage(String text) {
        Optional<SeasonResetMessage> parsed = SeasonResetMessage.parse(text);
        if (parsed.isEmpty()) {
            if (SeasonResetMessage.isSeasonResetKind(text)) {
                logger.warn("Season reset: ignored a season_reset notice that could not be read: {}",
                        PlayerResetFeature.clean(text));
            }
            return false;
        }
        SeasonResetMessage reset = parsed.get();
        String origin = PlayerResetFeature.clean(reset.origin());
        if (reset.resetNumber() == lastApplied) {
            logger.info("Season reset: reset {} from {} was already applied here; nothing to do", reset.resetNumber(), origin);
            return false;
        }
        if (reset.resetNumber() < lastApplied) {
            logger.warn("Season reset: ignored reset {} from {}: reset {} was already applied here. If BSP-Core's season "
                            + "data was restored to an older reset number, delete {} and restart the proxy, or later resets are ignored too",
                    reset.resetNumber(), origin, lastApplied, file == null ? FILE_NAME : file);
            return false;
        }
        long now = clock.getAsLong();
        lastApplied = reset.resetNumber();
        save(new Applied(lastApplied, now, reset.origin()));
        List<Player> online = List.copyOf(players.get());
        // Forgotten before the disconnect, so a player who logs straight back in already lands on the fallback server;
        // the online sessions are held meanwhile, so a switch in between is not remembered.
        int forgotten = lastServers == null ? -1 : lastServers.forgetAll(now, online);
        Component reason = reason(reset);
        for (Player player : online) {
            try {
                player.disconnect(reason);
            } catch (RuntimeException e) {
                logger.warn("Season reset: could not disconnect {} ({})", player.getUsername(), e.toString());
            }
        }
        logger.info("Season reset {} from {}{}: {} player(s) disconnected, {}", reset.resetNumber(), origin,
                reset.season().isPresent() ? " (season " + reset.season().getAsInt() + ")" : "",
                online.size(), forgotten < 0 ? "no last servers kept here (lastServer.enabled is false)"
                        : forgotten + " last server(s) forgotten, so everyone joins the fallback server");
        return true;
    }

    /** {@code seasonReset.kick} with {@code <season>} when the notice carries the season, else {@code kickNoNumber}. */
    Component reason(SeasonResetMessage reset) {
        if (reset.season().isPresent()) {
            return messages.render(KEY_KICK, Placeholder.unparsed("season", Integer.toString(reset.season().getAsInt())));
        }
        return messages.render(KEY_KICK_NO_NUMBER);
    }

    private int load() {
        if (file == null || Files.notExists(file)) {
            return 0;
        }
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Applied read = GSON.fromJson(in, Applied.class);
            return read == null ? 0 : Math.max(0, read.lastResetNumber());
        } catch (IOException | JsonParseException e) {
            logger.error("Could not read {}; the next season reset notice will be applied ({})", file, e.toString());
            return 0;
        }
    }

    private void save(Applied applied) {
        if (file == null) {
            return;
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(applied, Applied.class, w);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.error("Could not write {}; a repeat of reset {} would be applied again after a restart ({})",
                    file, applied.lastResetNumber(), e.toString());
        }
    }
}
