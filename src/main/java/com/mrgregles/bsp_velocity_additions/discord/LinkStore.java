package com.mrgregles.bsp_velocity_additions.discord;

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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Who gets Herald's direct messages: one Discord user per Minecraft account (decisions 9, 11 and D22), kept in memory
 * and mirrored to {@code discord-links.json} in the plugin's data folder (D2):
 * {@code {"<minecraft uuid>": {"discord": "<discord user id>", "name": "<minecraft name>", "at": <ms>}}}.
 * Nothing about the Discord user but the id is kept: no name, avatar or email. The file is readable by its owner only
 * where the file system allows it, and the ids never leave this class except to address a direct message.
 */
public final class LinkStore {

    public static final String FILE_NAME = "discord-links.json";

    /** A Minecraft account and the Discord user its warnings go to. */
    public record Link(UUID player, String name, long discordUser, long at) {
    }

    private record Stored(String discord, String name, long at) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type FILE_TYPE = new TypeToken<Map<String, Stored>>() { }.getType();

    private final Path file;
    private final Executor saver;
    private final Logger logger;
    private final Map<UUID, Link> links = new ConcurrentHashMap<>();
    private final AtomicBoolean savePending = new AtomicBoolean();

    public LinkStore(Path file, Executor saver, Logger logger) {
        this.file = file;
        this.saver = saver;
        this.logger = logger;
        load();
    }

    public Optional<Link> get(UUID player) {
        return Optional.ofNullable(links.get(player));
    }

    /** Links the account to the Discord user, replacing any earlier link of that account. Returns the replaced link. */
    public Optional<Link> link(UUID player, String name, long discordUser, long now) {
        Link old = links.put(player, new Link(player, name, discordUser, now));
        scheduleSave();
        return Optional.ofNullable(old);
    }

    public Optional<Link> remove(UUID player) {
        Link old = links.remove(player);
        if (old != null) {
            scheduleSave();
        }
        return Optional.ofNullable(old);
    }

    /** The accounts linked to one Discord user, by name. */
    public List<Link> linksOf(long discordUser) {
        List<Link> out = new ArrayList<>();
        for (Link l : links.values()) {
            if (l.discordUser() == discordUser) {
                out.add(l);
            }
        }
        out.sort(Comparator.comparing((Link l) -> l.name().toLowerCase(Locale.ROOT)).thenComparing(l -> l.player().toString()));
        return out;
    }

    public List<Link> all() {
        return List.copyOf(links.values());
    }

    public int size() {
        return links.size();
    }

    public void flush() {
        save();
    }

    private void load() {
        if (Files.notExists(file)) {
            return;
        }
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Stored> read = GSON.fromJson(in, FILE_TYPE);
            if (read == null) {
                return;
            }
            int skipped = 0;
            for (Map.Entry<String, Stored> e : read.entrySet()) {
                try {
                    Stored s = e.getValue();
                    long id = Long.parseLong(s.discord());
                    if (id <= 0) {
                        throw new NumberFormatException();
                    }
                    UUID player = UUID.fromString(e.getKey());
                    links.put(player, new Link(player, s.name() == null ? "" : s.name(), id, s.at()));
                } catch (RuntimeException bad) {
                    skipped++;
                }
            }
            if (skipped > 0) {
                logger.warn("Skipped {} broken line(s) in {}", skipped, file);
            }
        } catch (IOException | JsonParseException e) {
            logger.error("Could not read {}; starting with no Discord links ({})", file, e.getClass().getSimpleName());
        }
    }

    private void scheduleSave() {
        if (savePending.compareAndSet(false, true)) {
            saver.execute(this::save);
        }
    }

    private synchronized void save() {
        savePending.set(false);
        Map<String, Stored> out = new TreeMap<>();
        links.forEach((uuid, l) -> out.put(uuid.toString(), new Stored(Long.toString(l.discordUser()), l.name(), l.at())));
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(out, FILE_TYPE, w);
            }
            ownerOnly(tmp);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.error("Could not write {} ({})", file, e.getClass().getSimpleName());
        }
    }

    private static void ownerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException notPosix) {
            // Windows and some container mounts: the folder's own permissions apply.
        }
    }
}
