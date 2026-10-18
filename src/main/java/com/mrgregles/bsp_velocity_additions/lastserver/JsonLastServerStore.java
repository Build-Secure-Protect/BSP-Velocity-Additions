package com.mrgregles.bsp_velocity_additions.lastserver;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link LastServerStore} kept in memory and mirrored to one JSON file, {@code last-servers.json}:
 * {@code {"<uuid>": {"server": "BSP-Wild", "name": "Steve", "at": 1760000000000}}}.
 * Every change schedules one save on the given executor; the file is replaced atomically.
 */
public final class JsonLastServerStore implements LastServerStore {

    public static final String FILE_NAME = "last-servers.json";

    /** One remembered player. {@code at} is epoch milliseconds of the last change. */
    public record Entry(String server, String name, long at) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type FILE_TYPE = new TypeToken<Map<String, Entry>>() { }.getType();

    private final Path file;
    private final Executor saver;
    private final Logger logger;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicBoolean savePending = new AtomicBoolean();

    public JsonLastServerStore(Path file, Executor saver, Logger logger) {
        this.file = file;
        this.saver = saver;
        this.logger = logger;
        load();
    }

    @Override
    public Optional<String> get(UUID player) {
        Entry e = entries.get(player);
        return e == null ? Optional.empty() : Optional.of(e.server());
    }

    @Override
    public void put(UUID player, String name, String server) {
        Entry old = entries.put(player, new Entry(server, name, System.currentTimeMillis()));
        if (old == null || !old.server().equals(server) || !old.name().equals(name)) {
            scheduleSave();
        }
    }

    @Override
    public int size() {
        return entries.size();
    }

    @Override
    public void flush() {
        save();
    }

    private void load() {
        if (Files.notExists(file)) {
            return;
        }
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Entry> read = GSON.fromJson(in, FILE_TYPE);
            if (read == null) {
                return;
            }
            read.forEach((key, entry) -> {
                try {
                    if (entry != null && entry.server() != null) {
                        entries.put(UUID.fromString(key), new Entry(entry.server(), entry.name() == null ? "" : entry.name(), entry.at()));
                    }
                } catch (IllegalArgumentException bad) {
                    logger.warn("Skipping a bad player id in {}: {}", file, key);
                }
            });
        } catch (IOException | JsonParseException e) {
            logger.error("Could not read {}; starting with an empty last-server list ({})", file, e.toString());
        }
    }

    private void scheduleSave() {
        if (savePending.compareAndSet(false, true)) {
            saver.execute(this::save);
        }
    }

    private synchronized void save() {
        savePending.set(false);
        Map<String, Entry> out = new TreeMap<>();
        entries.forEach((uuid, entry) -> out.put(uuid.toString(), entry));
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(out, FILE_TYPE, w);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.error("Could not write {} ({})", file, e.toString());
        }
    }
}
