package com.mrgregles.bsp_velocity_additions.lastserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class JsonLastServerStoreTest {

    private final UUID steve = UUID.fromString("aff80d73-fa58-464d-b419-11187ffd8cd3");
    private final UUID alex = UUID.fromString("376f7d5b-6510-46e9-8bbf-936377461446");

    @Test
    void remembersAndSurvivesARestart(@TempDir Path dir) {
        Path file = dir.resolve("data").resolve(JsonLastServerStore.FILE_NAME);
        JsonLastServerStore store = new JsonLastServerStore(file, Runnable::run, mock(Logger.class));
        store.put(steve, "Steve", "BSP-Wild");
        store.put(alex, "Alex", "BSP-Spawn");
        store.put(steve, "Steve", "BSP-Spawn");

        assertTrue(Files.exists(file));
        assertFalse(Files.exists(dir.resolve("data").resolve(JsonLastServerStore.FILE_NAME + ".tmp")));

        JsonLastServerStore reloaded = new JsonLastServerStore(file, Runnable::run, mock(Logger.class));
        assertEquals(2, reloaded.size());
        assertEquals(Optional.of("BSP-Spawn"), reloaded.get(steve));
        assertEquals(Optional.of("BSP-Spawn"), reloaded.get(alex));
        assertEquals(Optional.empty(), reloaded.get(UUID.randomUUID()));
    }

    @Test
    void aBrokenFileStartsEmptyInsteadOfCrashing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(JsonLastServerStore.FILE_NAME);
        Files.writeString(file, "{ not json");

        JsonLastServerStore store = new JsonLastServerStore(file, Runnable::run, mock(Logger.class));

        assertEquals(0, store.size());
    }

    @Test
    void badIdsAreSkippedAndGoodOnesKept(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(JsonLastServerStore.FILE_NAME);
        Files.writeString(file, """
                {"not-a-uuid": {"server": "BSP-Wild", "name": "x", "at": 1},
                 "aff80d73-fa58-464d-b419-11187ffd8cd3": {"server": "BSP-Wild", "at": 1}}
                """);

        JsonLastServerStore store = new JsonLastServerStore(file, Runnable::run, mock(Logger.class));

        assertEquals(1, store.size());
        assertEquals(Optional.of("BSP-Wild"), store.get(steve));
    }

    /** PR21: removing a player is saved to the file, keeps the others, and a second removal is harmless. */
    @Test
    void removeForgetsOnePlayerAndSurvivesARestart(@TempDir Path dir) {
        Path file = dir.resolve(JsonLastServerStore.FILE_NAME);
        JsonLastServerStore store = new JsonLastServerStore(file, Runnable::run, mock(Logger.class));
        store.put(steve, "Steve", "BSP-Wild");
        store.put(alex, "Alex", "BSP-Spawn");
        long resetMs = System.currentTimeMillis() + 60_000L;

        assertEquals(Optional.of("BSP-Wild"), store.remove(steve, resetMs));
        assertEquals(Optional.empty(), store.remove(steve, resetMs));
        assertEquals(Optional.empty(), store.remove(UUID.randomUUID(), resetMs));

        JsonLastServerStore reloaded = new JsonLastServerStore(file, Runnable::run, mock(Logger.class));
        assertEquals(1, reloaded.size());
        assertEquals(Optional.empty(), reloaded.get(steve));
        assertEquals(Optional.of("BSP-Spawn"), reloaded.get(alex));
    }

    /** Audit 3: an entry written after the reset (the player has played since) is kept, also after a restart. */
    @Test
    void anEntryNewerThanTheResetIsKept(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(JsonLastServerStore.FILE_NAME);
        Files.writeString(file, """
                {"aff80d73-fa58-464d-b419-11187ffd8cd3": {"server": "BSP-Wild", "name": "Steve", "at": 2000},
                 "376f7d5b-6510-46e9-8bbf-936377461446": {"server": "BSP-Spawn", "name": "Alex", "at": 1000}}
                """);
        JsonLastServerStore store = new JsonLastServerStore(file, Runnable::run, mock(Logger.class));

        assertEquals(Optional.empty(), store.remove(steve, 2000L), "same millisecond: kept");
        assertEquals(Optional.empty(), store.remove(steve, 1500L));
        assertEquals(Optional.of("BSP-Spawn"), store.remove(alex, 1500L));

        assertEquals(Optional.of("BSP-Wild"), store.get(steve));
    }

    /** D45: a season reset forgets every entry last changed before it, saves once, and keeps a newer one. */
    @Test
    void removeAllForgetsEveryOlderEntryAndKeepsANewerOne(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(JsonLastServerStore.FILE_NAME);
        Files.writeString(file, """
                {"aff80d73-fa58-464d-b419-11187ffd8cd3": {"server": "BSP-Wild", "name": "Steve", "at": 2000},
                 "376f7d5b-6510-46e9-8bbf-936377461446": {"server": "BSP-Spawn", "name": "Alex", "at": 1000},
                 "0f8fad5b-d9cb-469f-a165-70867728950e": {"server": "BSP-Spawn", "name": "Nova", "at": 1499}}
                """);
        java.util.concurrent.atomic.AtomicInteger saves = new java.util.concurrent.atomic.AtomicInteger();
        JsonLastServerStore store = new JsonLastServerStore(file, task -> { saves.incrementAndGet(); task.run(); },
                mock(Logger.class));

        assertEquals(2, store.removeAll(1500L));
        assertEquals(1, saves.get(), "one save for the whole reset");
        assertEquals(0, store.removeAll(1500L), "twice is harmless");
        assertEquals(1, saves.get(), "and saves nothing");

        JsonLastServerStore reloaded = new JsonLastServerStore(file, Runnable::run, mock(Logger.class));
        assertEquals(1, reloaded.size());
        assertEquals(Optional.of("BSP-Wild"), reloaded.get(steve), "played since the reset: kept");
        assertEquals(Optional.empty(), reloaded.get(alex));
    }

    @Test
    void removingSomeoneNotOnFileSchedulesNoSave(@TempDir Path dir) {
        java.util.concurrent.atomic.AtomicInteger saves = new java.util.concurrent.atomic.AtomicInteger();
        JsonLastServerStore store = new JsonLastServerStore(dir.resolve(JsonLastServerStore.FILE_NAME),
                task -> { saves.incrementAndGet(); task.run(); }, mock(Logger.class));

        store.remove(steve, Long.MAX_VALUE);

        assertEquals(0, saves.get());
        assertFalse(Files.exists(dir.resolve(JsonLastServerStore.FILE_NAME)));
    }
}
