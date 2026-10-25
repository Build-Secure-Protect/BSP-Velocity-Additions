package com.mrgregles.bsp_velocity_additions.discord;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.Optional;
import java.util.UUID;

/**
 * One message from BSP-Core on the Redis channel {@code bsp_core:discord} (BSP-Core {@code docs/API.md}, "Discord
 * alerts", layout version 1). Unknown versions, unknown types and broken JSON are ignored, as the contract asks.
 */
public sealed interface Alert {

    int VERSION = 1;

    /** Where a totem stands. */
    record Place(String server, String world, int x, int y, int z) {
    }

    /** To the owner: their totem is being stolen, with {@code secondsLeft} of the steal to go when it was sent. */
    record Warning(UUID player, Place place, String thief, int secondsLeft, long sentAt) implements Alert {
    }

    /** To the owner: the steal failed. {@code reason} is {@code left}, {@code interrupted}, {@code totem_gone}, {@code cancelled} or {@code ended}. */
    record Saved(UUID player, Place place, String thief, String reason, long sentAt) implements Alert {
    }

    /** To the owner: the totem was stolen. */
    record Stolen(UUID player, Place place, String thief, long sentAt) implements Alert {
    }

    /** The public line. Holds only the server: the contract allows nothing else in it. */
    record Channel(String server, long sentAt) implements Alert {
    }

    /** The player unlinked in game; BSP-Core has already deleted the {@code linked} key. */
    record Unlink(UUID player, long sentAt) implements Alert {
    }

    static Optional<Alert> parse(String json) {
        JsonObject o;
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) {
                return Optional.empty();
            }
            o = e.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            return Optional.empty();
        }
        try {
            if (intOf(o, "v") != VERSION) {
                return Optional.empty();
            }
            String type = text(o, "type");
            long sentAt = longOf(o, "sentAt");
            return switch (type) {
                case "warning" -> Optional.of(new Warning(uuid(o), place(o), text(o, "thief"), intOf(o, "secondsLeft"), sentAt));
                case "saved" -> Optional.of(new Saved(uuid(o), place(o), text(o, "thief"), text(o, "reason"), sentAt));
                case "stolen" -> Optional.of(new Stolen(uuid(o), place(o), text(o, "thief"), sentAt));
                case "channel" -> Optional.of(new Channel(text(o, "server"), sentAt));
                case "unlink" -> Optional.of(new Unlink(uuid(o), sentAt));
                default -> Optional.empty();
            };
        } catch (RuntimeException missingOrWrong) {
            return Optional.empty();
        }
    }

    private static Place place(JsonObject o) {
        return new Place(text(o, "server"), text(o, "world"), intOf(o, "x"), intOf(o, "y"), intOf(o, "z"));
    }

    private static UUID uuid(JsonObject o) {
        return UUID.fromString(text(o, "player"));
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return e.getAsString();
    }

    private static int intOf(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return e.getAsInt();
    }

    private static long longOf(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return e.getAsLong();
    }
}
