package com.mrgregles.bsp_velocity_additions.lastserver;

import com.mrgregles.bsp_velocity_additions.config.PluginConfig;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Decides where a joining player goes. Pure logic, so it is unit-tested without a proxy.
 *
 * <ol>
 *   <li>The remembered server, if it exists, is not ignored and answers pings.</li>
 *   <li>Otherwise "spawn": the configured {@code fallbackServer} if it answers, else the first server of the
 *   {@code try} list that answers.</li>
 *   <li>Otherwise nothing: Velocity keeps its own choice and its own error handling.</li>
 * </ol>
 */
public final class InitialServerChooser {

    /** The outcome and why, for the log line. */
    public record Choice(Optional<RegisteredServer> server, Reason reason) {
        public enum Reason { REMEMBERED, FALLBACK_INSTEAD_OF_OFFLINE, FALLBACK_INSTEAD_OF_UNKNOWN, FALLBACK_IGNORED, FALLBACK_FIRST_TIME, NONE }
    }

    private final PluginConfig.LastServer config;
    private final Function<String, Optional<RegisteredServer>> lookup;
    private final Predicate<String> reachable;
    private final Supplier<List<String>> tryList;

    public InitialServerChooser(PluginConfig.LastServer config, Function<String, Optional<RegisteredServer>> lookup,
                                Predicate<String> reachable, Supplier<List<String>> tryList) {
        this.config = config;
        this.lookup = lookup;
        this.reachable = reachable;
        this.tryList = tryList;
    }

    public Choice choose(Optional<String> remembered) {
        if (remembered.isEmpty()) {
            return new Choice(fallback(Set.of()), Choice.Reason.FALLBACK_FIRST_TIME);
        }
        String name = remembered.get();
        if (config.isIgnored(name)) {
            return new Choice(fallback(Set.of()), Choice.Reason.FALLBACK_IGNORED);
        }
        Optional<RegisteredServer> server = lookup.apply(name);
        if (server.isEmpty()) {
            return new Choice(fallback(Set.of()), Choice.Reason.FALLBACK_INSTEAD_OF_UNKNOWN);
        }
        if (!reachable.test(name)) {
            return new Choice(fallback(Set.of(name.toLowerCase(Locale.ROOT))), Choice.Reason.FALLBACK_INSTEAD_OF_OFFLINE);
        }
        return new Choice(server, Choice.Reason.REMEMBERED);
    }

    /** Spawn: the fallback server or the first reachable {@code try} server, skipping {@code excludeLower}. */
    public Optional<RegisteredServer> fallback(Set<String> excludeLower) {
        if (config.hasFallbackServer()) {
            Optional<RegisteredServer> s = usable(config.fallbackServer(), excludeLower);
            if (s.isPresent()) {
                return s;
            }
        }
        for (String name : tryList.get()) {
            Optional<RegisteredServer> s = usable(name, excludeLower);
            if (s.isPresent()) {
                return s;
            }
        }
        return Optional.empty();
    }

    private Optional<RegisteredServer> usable(String name, Set<String> excludeLower) {
        if (excludeLower.contains(name.toLowerCase(Locale.ROOT)) || !reachable.test(name)) {
            return Optional.empty();
        }
        return lookup.apply(name);
    }
}
