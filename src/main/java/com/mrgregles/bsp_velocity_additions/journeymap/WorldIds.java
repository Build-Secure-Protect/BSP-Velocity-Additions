package com.mrgregles.bsp_velocity_additions.journeymap;

import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Each backend's JourneyMap world id. By default a name-based UUID of {@code "bsp:" + the velocity.toml name}, so it
 * is the same after every restart and differs between servers; an override from {@code [journeyMap.worldIds]} wins.
 * The id becomes part of a folder name on the player's computer, so an override may hold only letters, digits,
 * {@code -} and {@code _}, at most 64 characters; anything else is logged and ignored.
 */
public final class WorldIds {

    static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final Map<String, String> overrides = new HashMap<>();

    public WorldIds(Map<String, String> configured, Logger logger) {
        configured.forEach((server, id) -> {
            if (SAFE.matcher(id).matches()) {
                overrides.put(server.toLowerCase(Locale.ROOT), id);
            } else {
                logger.warn("journeyMap.worldIds: the id for {} may hold only letters, digits, - and _ (at most 64); using the default id", server);
            }
        });
    }

    public String idOf(String serverName) {
        String override = overrides.get(serverName.toLowerCase(Locale.ROOT));
        return override != null ? override : derived(serverName);
    }

    public boolean isOverridden(String serverName) {
        return overrides.containsKey(serverName.toLowerCase(Locale.ROOT));
    }

    static String derived(String serverName) {
        return UUID.nameUUIDFromBytes(("bsp:" + serverName).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
