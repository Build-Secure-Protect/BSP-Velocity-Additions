package com.mrgregles.bsp_velocity_additions.config;

import com.moandjiezana.toml.Toml;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The plugin's settings, read from {@code config.toml} in the data folder. A missing file is created from the
 * bundled template, so the comments in the file are the documentation. Missing keys take their defaults.
 */
public record PluginConfig(LastServer lastServer, JoinLeave joinLeave, TabList tabList, Players players, Chat chat) {

    public static final String FILE_NAME = "config.toml";

    /** Settings for the "last server" feature. Server names are compared case-insensitively. */
    public record LastServer(boolean enabled, Set<String> ignoredServers, String fallbackServer, int pingSeconds,
                             boolean importVelocityRememberServer) {

        public static final LastServer DEFAULTS = new LastServer(true, Set.of(), "", 10, true);

        public boolean isIgnored(String serverName) {
            return ignoredServers.contains(serverName.toLowerCase(Locale.ROOT));
        }

        public boolean hasFallbackServer() {
            return !fallbackServer.isBlank();
        }
    }

    /**
     * Settings for the network join and leave lines (feature 4, decisions D7, D14, D15). There is deliberately no
     * setting to announce server switches: that would let players track each other.
     */
    public record JoinLeave(boolean enabled, String silentPermission) {

        public static final JoinLeave DEFAULTS = new JoinLeave(true, "bspva.joinleave.silent");

        public boolean hasSilentPermission() {
            return !silentPermission.isBlank();
        }
    }

    /** Settings for the network tab list (feature 3, decisions D3, D9, D16). */
    public record TabList(boolean enabled, int refreshSeconds, String staffPermission) {

        public static final TabList DEFAULTS = new TabList(true, 3, "bspva.tab.staff");

        public boolean hasStaffPermission() {
            return !staffPermission.isBlank();
        }
    }

    /** Settings for {@code /players} (feature 3, decisions D8, D16). */
    public record Players(boolean enabled, String serversPermission) {

        public static final Players DEFAULTS = new Players(true, "bspva.players.servers");

        public boolean hasServersPermission() {
            return !serversPermission.isBlank();
        }
    }

    /** Settings for shared chat (feature 2, decisions D5, D6). */
    public record Chat(boolean enabled, boolean useBackendFormatting, boolean logToConsole) {

        public static final Chat DEFAULTS = new Chat(true, true, true);
    }

    public static PluginConfig defaults() {
        return new PluginConfig(LastServer.DEFAULTS, JoinLeave.DEFAULTS, TabList.DEFAULTS, Players.DEFAULTS, Chat.DEFAULTS);
    }

    /** Creates the file from the template when it is missing, then reads it. A broken file falls back to the defaults. */
    public static PluginConfig load(Path dataDirectory, Logger logger) {
        Path file = dataDirectory.resolve(FILE_NAME);
        try {
            Files.createDirectories(dataDirectory);
            if (Files.notExists(file)) {
                try (InputStream in = PluginConfig.class.getResourceAsStream("/" + FILE_NAME)) {
                    if (in == null) {
                        throw new IOException("bundled " + FILE_NAME + " is missing from the jar");
                    }
                    Files.copy(in, file);
                }
                logger.info("Wrote the default {}", file);
            }
            return parse(new Toml().read(file.toFile()));
        } catch (IOException | RuntimeException e) {
            logger.error("Could not read {}; using the defaults ({})", file, e.toString());
            return defaults();
        }
    }

    static PluginConfig parse(Toml toml) {
        return new PluginConfig(parseLastServer(toml.getTable("lastServer")), parseJoinLeave(toml.getTable("joinLeave")),
                parseTabList(toml.getTable("tabList")), parsePlayers(toml.getTable("players")), parseChat(toml.getTable("chat")));
    }

    private static Chat parseChat(Toml section) {
        Chat d = Chat.DEFAULTS;
        if (section == null) {
            return d;
        }
        return new Chat(
                section.getBoolean("enabled", d.enabled()),
                section.getBoolean("useBackendFormatting", d.useBackendFormatting()),
                section.getBoolean("logToConsole", d.logToConsole()));
    }

    private static TabList parseTabList(Toml section) {
        TabList d = TabList.DEFAULTS;
        if (section == null) {
            return d;
        }
        return new TabList(
                section.getBoolean("enabled", d.enabled()),
                (int) Math.max(1L, section.getLong("refreshSeconds", (long) d.refreshSeconds())),
                section.getString("staffPermission", d.staffPermission()).trim());
    }

    private static Players parsePlayers(Toml section) {
        Players d = Players.DEFAULTS;
        if (section == null) {
            return d;
        }
        return new Players(
                section.getBoolean("enabled", d.enabled()),
                section.getString("serversPermission", d.serversPermission()).trim());
    }

    private static JoinLeave parseJoinLeave(Toml section) {
        JoinLeave d = JoinLeave.DEFAULTS;
        if (section == null) {
            return d;
        }
        return new JoinLeave(
                section.getBoolean("enabled", d.enabled()),
                section.getString("silentPermission", d.silentPermission()).trim());
    }

    private static LastServer parseLastServer(Toml section) {
        if (section == null) {
            return LastServer.DEFAULTS;
        }
        LastServer d = LastServer.DEFAULTS;
        List<String> ignored = section.getList("ignoredServers", List.of());
        Set<String> ignoredLower = ignored.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        int pingSeconds = (int) Math.max(1L, section.getLong("pingSeconds", (long) d.pingSeconds()));
        return new LastServer(
                section.getBoolean("enabled", d.enabled()),
                ignoredLower,
                section.getString("fallbackServer", d.fallbackServer()).trim(),
                pingSeconds,
                section.getBoolean("importVelocityRememberServer", d.importVelocityRememberServer()));
    }
}
