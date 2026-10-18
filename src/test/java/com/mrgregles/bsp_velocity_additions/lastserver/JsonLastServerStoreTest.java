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
}
