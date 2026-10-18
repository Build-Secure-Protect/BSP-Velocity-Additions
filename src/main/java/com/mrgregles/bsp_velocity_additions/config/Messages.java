package com.mrgregles.bsp_velocity_additions.config;

import com.moandjiezana.toml.Toml;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Player-facing text from {@code messages.toml} (decision D0b). Keys are {@code section.key}. A line missing from the
 * owner's file falls back to the bundled template, so a new message in a later version never shows up empty.
 */
public final class Messages {

    public static final String FILE_NAME = "messages.toml";

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Map<String, String> lines;

    Messages(Map<String, String> lines) {
        this.lines = Map.copyOf(lines);
    }

    /** Only the bundled defaults, for tests and for a broken file. */
    public static Messages defaults() {
        return new Messages(readBundled());
    }

    public static Messages load(Path dataDirectory, Logger logger) {
        Path file = dataDirectory.resolve(FILE_NAME);
        Map<String, String> merged = new HashMap<>(readBundled());
        try {
            Files.createDirectories(dataDirectory);
            if (Files.notExists(file)) {
                try (InputStream in = Messages.class.getResourceAsStream("/" + FILE_NAME)) {
                    if (in == null) {
                        throw new IOException("bundled " + FILE_NAME + " is missing from the jar");
                    }
                    Files.copy(in, file);
                }
                logger.info("Wrote the default {}", file);
            }
            merged.putAll(flatten(new Toml().read(file.toFile())));
        } catch (IOException | RuntimeException e) {
            logger.error("Could not read {}; using the bundled messages ({})", file, e.toString());
        }
        return new Messages(merged);
    }

    /** The raw MiniMessage line, or the key itself when even the template lacks it. */
    public String raw(String key) {
        return lines.getOrDefault(key, key);
    }

    public Component render(String key, TagResolver... placeholders) {
        return MINI.deserialize(raw(key), placeholders);
    }

    static Map<String, String> flatten(Toml toml) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, Object> section : toml.entrySet()) {
            if (section.getValue() instanceof Toml table) {
                for (Map.Entry<String, Object> line : table.entrySet()) {
                    if (line.getValue() instanceof String s) {
                        out.put(section.getKey() + "." + line.getKey(), s);
                    }
                }
            } else if (section.getValue() instanceof String s) {
                out.put(section.getKey(), s);
            }
        }
        return out;
    }

    private static Map<String, String> readBundled() {
        try (InputStream in = Messages.class.getResourceAsStream("/" + FILE_NAME)) {
            if (in == null) {
                return Map.of();
            }
            return flatten(new Toml().read(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }
}
