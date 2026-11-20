package com.mrgregles.bsp_velocity_additions.store;

import com.moandjiezana.toml.Toml;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * What each Fourthwall product variant (by SKU) and membership tier (by tier id) gives: {@code store-rewards.toml}.
 * Every reward is checked when the file loads: its key and display name fit the {@code shop_rewards} columns, its
 * target is {@code any} or a server in velocity.toml (compared exactly, as BSP-Server Shop does), and every command,
 * filled with sample values, passes BSP-Server Shop's checks. A reward that fails is left out with one error line, so
 * nothing is ever written that a backend would refuse.
 */
public record RewardsConfig(Map<String, Reward> products, Map<String, Reward> memberships, List<String> problems) {

    public static final String FILE_NAME = "store-rewards.toml";

    public enum Kind { PRODUCT, MEMBERSHIP }

    public record Reward(Kind kind, String key, String displayName, String target, boolean requireOnline,
                         boolean safeToRetry, boolean perQuantity, List<String> grant, List<String> revoke) {

        public boolean canRevoke() {
            return !revoke.isEmpty();
        }
    }

    public static RewardsConfig empty() {
        return new RewardsConfig(Map.of(), Map.of(), List.of());
    }

    public Optional<Reward> product(String sku) {
        return Optional.ofNullable(products.get(sku));
    }

    public Optional<Reward> membership(String tierId) {
        return Optional.ofNullable(memberships.get(tierId));
    }

    /** A product or, failing that, a membership tier with this key (staff grants). */
    public Optional<Reward> any(String key) {
        return product(key).or(() -> membership(key));
    }

    /** Writes the template when missing, reads and checks the file. Throws when the file cannot be read at all. */
    public static RewardsConfig load(Path dataDirectory, Predicate<String> serverExists, Logger logger) throws IOException {
        StoreConfig.writeTemplateIfMissing(dataDirectory, FILE_NAME, logger);
        Path file = dataDirectory.resolve(FILE_NAME);
        try {
            return parse(new Toml().read(file.toFile()), serverExists);
        } catch (RuntimeException e) {
            throw new IOException(FILE_NAME + " is not valid TOML: " + e.getMessage(), e);
        }
    }

    static RewardsConfig parse(Toml toml, Predicate<String> serverExists) {
        List<String> problems = new ArrayList<>();
        Map<String, Reward> products = section(toml, "products", Kind.PRODUCT, serverExists, problems);
        Map<String, Reward> memberships = section(toml, "memberships", Kind.MEMBERSHIP, serverExists, problems);
        return new RewardsConfig(Collections.unmodifiableMap(products), Collections.unmodifiableMap(memberships), List.copyOf(problems));
    }

    private static Map<String, Reward> section(Toml toml, String name, Kind kind, Predicate<String> serverExists,
                                               List<String> problems) {
        Map<String, Reward> out = new LinkedHashMap<>();
        Toml table = toml.getTable(name);
        if (table == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : table.toMap().entrySet()) {
            String key = unquote(e.getKey());
            String where = "[" + name + ".\"" + key + "\"]";
            if (!(e.getValue() instanceof Map<?, ?> raw)) {
                problems.add(where + " is not a table; left out");
                continue;
            }
            try {
                out.put(key, reward(kind, key, raw, serverExists));
            } catch (IllegalArgumentException ex) {
                problems.add(where + " " + ex.getMessage() + "; left out");
            }
        }
        return out;
    }

    private static Reward reward(Kind kind, String key, Map<?, ?> raw, Predicate<String> serverExists) {
        if (key.isBlank() || key.length() > 64 || Commands.problem(key) != null || !key.equals(key.trim())) {
            throw new IllegalArgumentException("has a key that is blank, longer than 64 characters or holds control characters");
        }
        String display = string(raw, "displayName", "");
        if (display.isBlank() || display.length() > 128 || Commands.problem(display) != null) {
            throw new IllegalArgumentException("needs a displayName of 1 to 128 plain characters");
        }
        String target = string(raw, "target", "any");
        if (!target.equals("any")) {
            if (target.length() > 64 || !serverExists.test(target)) {
                throw new IllegalArgumentException("has target \"" + target
                        + "\", which is not \"any\" or a server in velocity.toml (names are compared exactly)");
            }
        }
        boolean requireOnline = bool(raw, "requireOnline", kind == Kind.PRODUCT && !target.equals("any"));
        boolean safeToRetry = bool(raw, "safeToRetry", false);
        boolean perQuantity = bool(raw, "perQuantity", true);
        List<String> grant = commands(raw, "grant", key);
        if (grant.isEmpty()) {
            throw new IllegalArgumentException("has no grant commands");
        }
        List<String> revoke = commands(raw, "revoke", key);
        return new Reward(kind, key, display.trim(), target, requireOnline, safeToRetry, perQuantity, grant, revoke);
    }

    private static List<String> commands(Map<?, ?> raw, String field, String key) {
        Object value = raw.get(field);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("has " + field + " that is not a list of strings");
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof String s)) {
                throw new IllegalArgumentException("has " + field + "[" + (i + 1) + "] that is not a string");
            }
            List<String> unknown = Commands.unknownPlaceholders(s);
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("has " + field + " command " + (i + 1) + " with unknown placeholder(s) "
                        + String.join(", ", unknown) + " (known: {player} {uuid} {quantity} {order} {sku})");
            }
            String problem = Commands.problem(Commands.fill(s, Commands.sample(key)));
            if (problem != null) {
                throw new IllegalArgumentException("has " + field + " command " + (i + 1) + " that " + problem);
            }
            out.add(s);
        }
        return List.copyOf(out);
    }

    private static String string(Map<?, ?> raw, String field, String fallback) {
        Object v = raw.get(field);
        return v == null ? fallback : v.toString().trim();
    }

    private static boolean bool(Map<?, ?> raw, String field, boolean fallback) {
        Object v = raw.get(field);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException("has " + field + " that is not true or false");
    }

    private static String unquote(String key) {
        return key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"") ? key.substring(1, key.length() - 1) : key;
    }
}
