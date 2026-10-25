package com.mrgregles.bsp_velocity_additions.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class LinkStoreTest {

    static final UUID A = UUID.fromString("0f3c1a2b-3c4d-4e5f-8a9b-0c1d2e3f4a5b");
    static final UUID B = UUID.fromString("1a2b3c4d-5e6f-4a1b-9c2d-3e4f5a6b7c8d");

    @Test
    void keepsOnlyTheDiscordIdBesideTheMinecraftAccountAndReloads(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(LinkStore.FILE_NAME);
        LinkStore store = new LinkStore(file, Runnable::run, mock(Logger.class));
        store.link(A, "Mrgregles", 712345678901234567L, 1000L);

        JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject(A.toString());
        assertEquals(Set.of("discord", "name", "at"), saved.keySet());
        assertEquals("712345678901234567", saved.get("discord").getAsString(), "a string, so no tool rounds the id");

        LinkStore again = new LinkStore(file, Runnable::run, mock(Logger.class));
        assertEquals(new LinkStore.Link(A, "Mrgregles", 712345678901234567L, 1000L), again.get(A).orElseThrow());
    }

    @Test
    void theFileIsReadableByItsOwnerOnly(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(LinkStore.FILE_NAME);
        new LinkStore(file, Runnable::run, mock(Logger.class)).link(A, "Mrgregles", 7L, 1L);

        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
    }

    @Test
    void aNewLinkReplacesTheOldAndRemoveDeletesIt(@TempDir Path dir) {
        LinkStore store = new LinkStore(dir.resolve(LinkStore.FILE_NAME), Runnable::run, mock(Logger.class));
        store.link(A, "Mrgregles", 7L, 1L);

        assertEquals(7L, store.link(A, "Mrgregles", 8L, 2L).orElseThrow().discordUser());
        assertEquals(8L, store.get(A).orElseThrow().discordUser());
        assertTrue(store.remove(A).isPresent());
        assertTrue(store.get(A).isEmpty());
        assertTrue(store.remove(A).isEmpty());
    }

    @Test
    void listsOneUsersAccountsByName(@TempDir Path dir) {
        LinkStore store = new LinkStore(dir.resolve(LinkStore.FILE_NAME), Runnable::run, mock(Logger.class));
        store.link(B, "zed", 7L, 1L);
        store.link(A, "Alpha", 7L, 1L);
        store.link(UUID.randomUUID(), "Other", 9L, 1L);

        assertEquals(List.of("Alpha", "zed"), store.linksOf(7L).stream().map(LinkStore.Link::name).toList());
    }

    @Test
    void skipsBrokenLines(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(LinkStore.FILE_NAME);
        Files.writeString(file, "{\"" + A + "\": {\"discord\": \"7\", \"name\": \"Ok\", \"at\": 1},"
                + " \"not-a-uuid\": {\"discord\": \"8\", \"name\": \"X\", \"at\": 1},"
                + " \"" + B + "\": {\"discord\": \"abc\", \"name\": \"Y\", \"at\": 1}}");

        LinkStore store = new LinkStore(file, Runnable::run, mock(Logger.class));

        assertEquals(1, store.size());
        assertEquals("Ok", store.get(A).orElseThrow().name());
    }
}
