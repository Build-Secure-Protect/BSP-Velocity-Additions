package com.mrgregles.bsp_velocity_additions.lastserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class VelocityRememberServerImportTest {

    private final UUID a = UUID.fromString("aff80d73-fa58-464d-b419-11187ffd8cd3");
    private final UUID b = UUID.fromString("ca49a807-ba03-4ea8-b454-b380460cc095");

    @Test
    void parsesTheFlatYamlAndSkipsTheRest() {
        Map<UUID, String> parsed = VelocityRememberServerImport.parse(List.of(
                "# comment",
                "",
                "aff80d73-fa58-464d-b419-11187ffd8cd3: BSP-Wild",
                "'ca49a807-ba03-4ea8-b454-b380460cc095': \"BSP-Spawn\"",
                "not-a-uuid: BSP-Spawn",
                "aff80d73-fa58-464d-b419-11187ffd8cd4:",
                "version: 1"));

        assertEquals(Map.of(a, "BSP-Wild", b, "BSP-Spawn"), parsed);
    }

    @Test
    void importsOnlyIntoAnEmptyStore(@TempDir Path dir) throws Exception {
        Path yml = dir.resolve("locations.yml");
        Files.writeString(yml, "aff80d73-fa58-464d-b419-11187ffd8cd3: BSP-Wild\n");
        JsonLastServerStore store = new JsonLastServerStore(dir.resolve("last.json"), Runnable::run, mock(Logger.class));

        assertEquals(1, VelocityRememberServerImport.importInto(store, yml, mock(Logger.class)));
        assertEquals(Optional.of("BSP-Wild"), store.get(a));

        store.put(a, "Steve", "BSP-Spawn");
        assertEquals(0, VelocityRememberServerImport.importInto(store, yml, mock(Logger.class)), "a non-empty store is left alone");
        assertEquals(Optional.of("BSP-Spawn"), store.get(a));
    }

    /** D45 audit: after a season reset empties the store, a restart never imports the old servers again. */
    @Test
    void importsOnlyOnceSoAnEmptyStoreAfterASeasonResetStaysEmpty(@TempDir Path dir) throws Exception {
        Path yml = dir.resolve("locations.yml");
        Files.writeString(yml, "aff80d73-fa58-464d-b419-11187ffd8cd3: BSP-Wild\n");
        Path marker = dir.resolve(VelocityRememberServerImport.DONE_MARKER);
        JsonLastServerStore store = new JsonLastServerStore(dir.resolve("last.json"), Runnable::run, mock(Logger.class));

        assertEquals(1, VelocityRememberServerImport.importOnce(store, yml, marker, mock(Logger.class)));
        assertTrue(Files.exists(marker));
        store.removeAll(Long.MAX_VALUE);

        assertEquals(0, VelocityRememberServerImport.importOnce(store, yml, marker, mock(Logger.class)));
        assertEquals(0, store.size());
    }

    /** A proxy that imported before the marker existed: a store in use marks the import done. */
    @Test
    void aStoreAlreadyInUseMarksTheImportDone(@TempDir Path dir) throws Exception {
        Path yml = dir.resolve("locations.yml");
        Files.writeString(yml, "aff80d73-fa58-464d-b419-11187ffd8cd3: BSP-Wild\n");
        Path marker = dir.resolve(VelocityRememberServerImport.DONE_MARKER);
        JsonLastServerStore store = new JsonLastServerStore(dir.resolve("last.json"), Runnable::run, mock(Logger.class));
        store.put(a, "Steve", "BSP-Spawn");

        assertEquals(0, VelocityRememberServerImport.importOnce(store, yml, marker, mock(Logger.class)));
        assertTrue(Files.exists(marker));
        store.removeAll(Long.MAX_VALUE);
        assertEquals(0, VelocityRememberServerImport.importOnce(store, yml, marker, mock(Logger.class)));
        assertEquals(0, store.size());
    }

    @Test
    void withoutVelocityRememberServersFileNothingIsMarked(@TempDir Path dir) {
        JsonLastServerStore store = new JsonLastServerStore(dir.resolve("last.json"), Runnable::run, mock(Logger.class));
        Path marker = dir.resolve(VelocityRememberServerImport.DONE_MARKER);

        assertEquals(0, VelocityRememberServerImport.importOnce(store, dir.resolve("nope.yml"), marker, mock(Logger.class)));
        assertFalse(Files.exists(marker), "a fresh proxy still imports if the file turns up later, as before");
    }

    @Test
    void aMissingFileImportsNothing(@TempDir Path dir) {
        JsonLastServerStore store = new JsonLastServerStore(dir.resolve("last.json"), Runnable::run, mock(Logger.class));

        assertEquals(0, VelocityRememberServerImport.importInto(store, dir.resolve("nope.yml"), mock(Logger.class)));
    }
}
