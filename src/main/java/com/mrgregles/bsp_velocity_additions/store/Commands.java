package com.mrgregles.bsp_velocity_additions.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reward commands: filling the placeholders and checking the result exactly as BSP-Server Shop's {@code CommandList}
 * will before running it, so a bad config is caught on the proxy rather than as a FAILED row.
 *
 * <p>Only validated values are ever put into a command: a Mojang-checked name and UUID, a number, an order number
 * that matched {@link #FRIENDLY_ID}, and the reward's own key from the config. Nothing a buyer types (message, email,
 * nickname, the raw username) can reach a command.
 */
public final class Commands {

    /** BSP-Server Shop's limit per command string. */
    public static final int MAX_LENGTH = 32_500;
    public static final Set<String> PLACEHOLDERS = Set.of("player", "uuid", "quantity", "order", "sku");
    public static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    /** Order numbers and member ids as they may appear in a command or a row's {@code source_id}. */
    public static final Pattern FRIENDLY_ID = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");
    public static final Pattern SOURCE_ID = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_]+)}");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Commands() {
    }

    /** The values one row's commands are filled with. */
    public record Values(String player, UUID uuid, int quantity, String order, String sku) {
        public Values {
            if (!NAME.matcher(player).matches()) {
                throw new IllegalArgumentException("not a valid player name: " + player);
            }
            if (quantity < 1) {
                throw new IllegalArgumentException("quantity below 1");
            }
            if (!FRIENDLY_ID.matcher(order).matches() && !SOURCE_ID.matcher(order).matches()) {
                throw new IllegalArgumentException("not a valid order or member id");
            }
        }

        Map<String, String> asMap() {
            return Map.of("player", player, "uuid", uuid.toString(), "quantity", Integer.toString(quantity),
                    "order", order, "sku", sku);
        }
    }

    /** Values that pass every check, for checking a config's templates when it loads. */
    static Values sample(String sku) {
        return new Values("Steve", UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7"), 1, "ABC123", sku);
    }

    /** Placeholders in a template that are not one of {@link #PLACEHOLDERS}; empty when there are none. */
    public static List<String> unknownPlaceholders(String template) {
        List<String> out = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            if (!PLACEHOLDERS.contains(m.group(1))) {
                out.add("{" + m.group(1) + "}");
            }
        }
        return out;
    }

    public static String fill(String template, Values values) {
        String out = template;
        for (Map.Entry<String, String> e : values.asMap().entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    /** BSP-Server Shop's check of one command; null when it would run, else the reason it would fail the row. */
    public static String problem(String command) {
        for (int k = 0; k < command.length(); k++) {
            char ch = command.charAt(k);
            if ((ch < 0x20 && ch != ' ') || ch == 0x7f || ch == '§') {
                return "holds a line break, control character or section sign";
            }
        }
        String c = command.trim();
        if (c.startsWith("/")) {
            c = c.substring(1).trim();
        }
        if (c.isEmpty()) {
            return "is blank";
        }
        if (c.length() > MAX_LENGTH) {
            return "is longer than " + MAX_LENGTH + " characters";
        }
        return null;
    }

    /** Fills every template and returns the {@code commands} column, or throws when a filled command would fail. */
    public static String toJson(List<String> templates, Values values) {
        if (templates.isEmpty()) {
            throw new IllegalArgumentException("no commands");
        }
        List<String> filled = new ArrayList<>(templates.size());
        for (int i = 0; i < templates.size(); i++) {
            String c = fill(templates.get(i), values);
            String problem = problem(c);
            if (problem != null) {
                throw new IllegalArgumentException("command " + (i + 1) + " " + problem);
            }
            filled.add(c);
        }
        return GSON.toJson(filled);
    }
}
