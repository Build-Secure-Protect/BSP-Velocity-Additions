package com.mrgregles.bsp_velocity_additions.lastserver;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-time import of VelocityRememberServer's data (owner decision D1). Its {@code locations.yml} is a flat list of
 * {@code <uuid>: <server>} lines, read here without a YAML library.
 */
public final class VelocityRememberServerImport {

    /** Where VelocityRememberServer keeps its file, relative to the proxy's plugins folder. */
    public static final String RELATIVE_PATH = "velocityrememberserver/locations.yml";
    /**
     * Written in this plugin's data folder once the import has run, or once the store is found in use, so an empty
     * store later (after a season reset, D45) never brings VelocityRememberServer's old servers back.
     */
    public static final String DONE_MARKER = "velocityrememberserver-imported";

    private VelocityRememberServerImport() {
    }

    /** Parses the file's lines. Blank lines, comments and lines that are not {@code uuid: server} are skipped. */
    public static Map<UUID, String> parse(List<String> lines) {
        Map<UUID, String> out = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = unquote(line.substring(0, colon).strip());
            String value = unquote(line.substring(colon + 1).strip());
            if (value.isEmpty()) {
                continue;
            }
            try {
                out.put(UUID.fromString(key), value);
            } catch (IllegalArgumentException notAUuid) {
                // a YAML header or something else that is not a player line
            }
        }
        return out;
    }

    /**
     * Copies the entries into {@code store} once: only when {@code doneMarker} does not exist, the store is empty and
     * the file exists. The marker is written after an import and whenever the store is found in use.
     *
     * @return how many players were imported, 0 when nothing was done
     */
    public static int importOnce(LastServerStore store, Path locationsYml, Path doneMarker, Logger logger) {
        if (Files.exists(doneMarker)) {
            return 0;
        }
        if (store.size() > 0) {
            markDone(doneMarker, logger);
            return 0;
        }
        int imported = importInto(store, locationsYml, logger);
        if (imported > 0) {
            markDone(doneMarker, logger);
        }
        return imported;
    }

    private static void markDone(Path doneMarker, Logger logger) {
        try {
            Files.createDirectories(doneMarker.toAbsolutePath().getParent());
            Files.writeString(doneMarker, "VelocityRememberServer's locations.yml is not imported again while this file exists.\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("Could not write {} ({}); an empty last-server list would import locations.yml again", doneMarker, e.toString());
        }
    }

    /**
     * Copies the entries into {@code store} if the store is empty and the file exists.
     *
     * @return how many players were imported, 0 when nothing was done
     */
    public static int importInto(LastServerStore store, Path locationsYml, Logger logger) {
        if (store.size() > 0 || Files.notExists(locationsYml)) {
            return 0;
        }
        try {
            Map<UUID, String> parsed = parse(Files.readAllLines(locationsYml, StandardCharsets.UTF_8));
            parsed.forEach((uuid, server) -> store.put(uuid, "", server));
            logger.info("Imported {} last-server entries from {}", parsed.size(), locationsYml);
            return parsed.size();
        } catch (IOException e) {
            logger.warn("Could not import {} ({})", locationsYml, e.toString());
            return 0;
        }
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
