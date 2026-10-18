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
