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

    @Test
    void aMissingFileImportsNothing(@TempDir Path dir) {
        JsonLastServerStore store = new JsonLastServerStore(dir.resolve("last.json"), Runnable::run, mock(Logger.class));

        assertEquals(0, VelocityRememberServerImport.importInto(store, dir.resolve("nope.yml"), mock(Logger.class)));
    }
}
