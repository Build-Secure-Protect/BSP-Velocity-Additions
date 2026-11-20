package com.mrgregles.bsp_velocity_additions.store;

import com.moandjiezana.toml.Toml;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Fourthwall store's settings, {@code store.toml} in the data folder (BSP-Server Shop request SH-R1, decision D39).
 * A missing file is written from the bundled template, whose comments are the documentation; missing keys take their
 * defaults. Secrets are never settings: see {@link Secrets}.
 */
public record StoreConfig(boolean enabled, Http http, Fourthwall fourthwall, Database database, Mojang mojang,
                          Membership membership, Reconcile reconcile, Security security) {

    public static final String FILE_NAME = "store.toml";

    /** The webhook receiver (D38: {@code 0.0.0.0} inside the proxy's container, published on mc-main's loopback). */
    public record Http(String bind, int port, String path, int maxBodyBytes) {
        public static final Http DEFAULTS = new Http("0.0.0.0", 8765, "/fourthwall/webhook", 1_048_576);
    }

    public record Fourthwall(String apiBaseUrl, String shopUrl, boolean processTestEvents, boolean captureOnly) {
        public static final Fourthwall DEFAULTS = new Fourthwall("https://api.fourthwall.com", "https://shop.example.com", false, false);
    }

    public record Database(String host, int port, String name, String user, int poolSize) {
        public static final Database DEFAULTS = new Database("192.168.1.25", 3306, "bsp_shop", "bsp_shop", 4);

        public String where() {
            return host + ":" + port + "/" + name;
        }
    }

    public record Mojang(String profileUrl, int cacheMinutes) {
        public static final Mojang DEFAULTS = new Mojang("https://api.mojang.com/users/profiles/minecraft/", 60);
    }

    /** Which subscription types take a membership's rewards away; CANCELLED keeps them until the paid period ends. */
    public record Membership(Set<String> revokeOn) {
        public static final Membership DEFAULTS = new Membership(Set.of("SUSPENDED", "NONE"));
    }

    public record Reconcile(boolean enabled, int intervalHours, int orderLookbackDays) {
        public static final Reconcile DEFAULTS = new Reconcile(true, 24, 14);
    }

    public record Security(int maxFailedClaimsPer10Min) {
        public static final Security DEFAULTS = new Security(5);
    }

    public static StoreConfig defaults() {
        return new StoreConfig(false, Http.DEFAULTS, Fourthwall.DEFAULTS, Database.DEFAULTS, Mojang.DEFAULTS,
                Membership.DEFAULTS, Reconcile.DEFAULTS, Security.DEFAULTS);
    }

    /** Writes the template when the file is missing, then reads it. A broken file keeps the store off. */
    public static StoreConfig load(Path dataDirectory, Logger logger) {
        try {
            return read(dataDirectory, logger);
        } catch (IOException e) {
            logger.error("Could not read {}; the store stays off ({})", dataDirectory.resolve(FILE_NAME), e.getMessage());
            return defaults();
        }
    }

    /** Like {@link #load} but throws when the file cannot be read, so a reload can keep the settings in use. */
    public static StoreConfig read(Path dataDirectory, Logger logger) throws IOException {
        writeTemplateIfMissing(dataDirectory, FILE_NAME, logger);
        try {
            return parse(new Toml().read(dataDirectory.resolve(FILE_NAME).toFile()));
        } catch (RuntimeException e) {
            throw new IOException(FILE_NAME + " is not valid TOML: " + e.getMessage(), e);
        }
    }

    static void writeTemplateIfMissing(Path dataDirectory, String name, Logger logger) throws IOException {
        Path file = dataDirectory.resolve(name);
        Files.createDirectories(dataDirectory);
        if (Files.notExists(file)) {
            try (InputStream in = StoreConfig.class.getResourceAsStream("/" + name)) {
                if (in == null) {
                    throw new IOException("bundled " + name + " is missing from the jar");
                }
                Files.copy(in, file);
            }
            logger.info("Wrote the default {}", file);
        }
    }

    static StoreConfig parse(Toml toml) {
        StoreConfig d = defaults();
        return new StoreConfig(toml.getBoolean("enabled", d.enabled()), parseHttp(toml.getTable("http")),
                parseFourthwall(toml.getTable("fourthwall")), parseDatabase(toml.getTable("database")),
                parseMojang(toml.getTable("mojang")), parseMembership(toml.getTable("membership")),
                parseReconcile(toml.getTable("reconcile")), parseSecurity(toml.getTable("security")));
    }

    private static Http parseHttp(Toml s) {
        Http d = Http.DEFAULTS;
        if (s == null) {
            return d;
        }
        String path = s.getString("path", d.path()).trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return new Http(nonBlank(s.getString("bind", d.bind()), d.bind()), clamp(s.getLong("port", (long) d.port()), 1, 65535),
                path, clamp(s.getLong("maxBodyBytes", (long) d.maxBodyBytes()), 1024, 16 * 1024 * 1024));
    }

    private static Fourthwall parseFourthwall(Toml s) {
        Fourthwall d = Fourthwall.DEFAULTS;
        if (s == null) {
            return d;
        }
        return new Fourthwall(stripSlash(nonBlank(s.getString("apiBaseUrl", d.apiBaseUrl()), d.apiBaseUrl())),
                nonBlank(s.getString("shopUrl", d.shopUrl()), d.shopUrl()),
                s.getBoolean("processTestEvents", d.processTestEvents()), s.getBoolean("captureOnly", d.captureOnly()));
    }

    private static Database parseDatabase(Toml s) {
        Database d = Database.DEFAULTS;
        if (s == null) {
            return d;
        }
        return new Database(nonBlank(s.getString("host", d.host()), d.host()), clamp(s.getLong("port", (long) d.port()), 1, 65535),
                nonBlank(s.getString("name", d.name()), d.name()), nonBlank(s.getString("user", d.user()), d.user()),
                clamp(s.getLong("poolSize", (long) d.poolSize()), 1, 20));
    }

    private static Mojang parseMojang(Toml s) {
        Mojang d = Mojang.DEFAULTS;
        if (s == null) {
            return d;
        }
        String url = nonBlank(s.getString("profileUrl", d.profileUrl()), d.profileUrl());
        return new Mojang(url.endsWith("/") ? url : url + "/", clamp(s.getLong("cacheMinutes", (long) d.cacheMinutes()), 0, 1440));
    }

    private static Membership parseMembership(Toml s) {
        Membership d = Membership.DEFAULTS;
        if (s == null || !s.contains("revokeOn")) {
            return d;
        }
        List<String> out = new ArrayList<>();
        for (Object o : s.getList("revokeOn", List.<Object>of())) {
            String t = String.valueOf(o).trim().toUpperCase(Locale.ROOT);
            if (t.equals("ACTIVE")) {
                continue; // an active membership always keeps its rewards
            }
            out.add(t);
        }
        return new Membership(Set.copyOf(out));
    }

    private static Reconcile parseReconcile(Toml s) {
        Reconcile d = Reconcile.DEFAULTS;
        if (s == null) {
            return d;
        }
        return new Reconcile(s.getBoolean("enabled", d.enabled()), clamp(s.getLong("intervalHours", (long) d.intervalHours()), 1, 168),
                clamp(s.getLong("orderLookbackDays", (long) d.orderLookbackDays()), 1, 365));
    }

    private static Security parseSecurity(Toml s) {
        Security d = Security.DEFAULTS;
        if (s == null) {
            return d;
        }
        return new Security(clamp(s.getLong("maxFailedClaimsPer10Min", (long) d.maxFailedClaimsPer10Min()), 1, 100));
    }

    private static int clamp(long v, long min, long max) {
        return (int) Math.max(min, Math.min(max, v));
    }

    private static String nonBlank(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
