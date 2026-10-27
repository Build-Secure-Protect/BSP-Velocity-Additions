package com.mrgregles.bsp_velocity_additions.journeymap;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorldIdsTest {

    @Test
    void theDefaultIdIsANameBasedUuidThatNeverChanges() {
        WorldIds ids = new WorldIds(Map.of(), mock(Logger.class));

        String spawn = ids.idOf("BSP-Spawn");
        assertEquals(UUID.nameUUIDFromBytes("bsp:BSP-Spawn".getBytes(StandardCharsets.UTF_8)).toString(), spawn);
        assertEquals(spawn, new WorldIds(Map.of(), mock(Logger.class)).idOf("BSP-Spawn"), "the same after a restart");
        assertTrue(WorldIds.SAFE.matcher(spawn).matches(), "safe as part of a folder name");
    }

    @Test
    void everyServerOnTheNetworkGetsItsOwnId() {
        WorldIds ids = new WorldIds(Map.of(), mock(Logger.class));
        String[] servers = {"BSP-Spawn", "BSP-SafeRoom", "BSP-Sanctuary", "BSP-Frostwarf", "BSP-Boulderbrook",
                "BSP-Moongrove", "BSP-Stormfall", "BSP-Test"};

        assertEquals(servers.length, java.util.Arrays.stream(servers).map(ids::idOf).distinct().count());
    }

    @Test
    void anOverrideWinsWhateverTheCaseOfTheName() {
        WorldIds ids = new WorldIds(Map.of("bsp-spawn", "cc7a1dc1-1f9f-4922-83e4-cb353ca585cd"), mock(Logger.class));

        assertEquals("cc7a1dc1-1f9f-4922-83e4-cb353ca585cd", ids.idOf("BSP-Spawn"));
        assertTrue(ids.isOverridden("BSP-Spawn"));
        assertFalse(ids.isOverridden("BSP-Frostwarf"));
        assertNotEquals("cc7a1dc1-1f9f-4922-83e4-cb353ca585cd", ids.idOf("BSP-Frostwarf"));
    }

    @Test
    void anOverrideThatCouldBreakAFolderNameIsIgnoredAndLogged() {
        Logger logger = mock(Logger.class);
        WorldIds ids = new WorldIds(Map.of("bsp-spawn", "../../evil", "bsp-test", "x".repeat(65)), logger);

        assertEquals(WorldIds.derived("BSP-Spawn"), ids.idOf("BSP-Spawn"));
        assertEquals(WorldIds.derived("BSP-Test"), ids.idOf("BSP-Test"));
        verify(logger).warn(anyString(), eq("bsp-spawn"));
        verify(logger).warn(anyString(), eq("bsp-test"));
    }
}
