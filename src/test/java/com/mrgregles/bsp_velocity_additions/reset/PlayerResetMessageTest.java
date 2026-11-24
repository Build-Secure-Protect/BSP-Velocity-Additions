package com.mrgregles.bsp_velocity_additions.reset;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PR21: the {@code player_reset} notice BSP-Core publishes on its Redis channel. */
class PlayerResetMessageTest {

    private static final String STEVE = "aff80d73-fa58-464d-b419-11187ffd8cd3";

    @Test
    void readsBspCoresNotice() {
        PlayerResetMessage m = PlayerResetMessage.parse("player_reset:BSP-Spawn:" + STEVE + "|1764000000000|Mrgregles|Steve")
                .orElseThrow();

        assertEquals("BSP-Spawn", m.origin());
        assertEquals(UUID.fromString(STEVE), m.player());
        assertEquals(1764000000000L, m.resetMs());
        assertEquals("Mrgregles", m.by());
        assertEquals("Steve", m.name());
    }

    @Test
    void theNameIsLastSoItIsReadWholeAndEmptyFieldsAreAllowed() {
        PlayerResetMessage m = PlayerResetMessage.parse("player_reset:BSP-Wild:" + STEVE + "|5||odd|name").orElseThrow();

        assertEquals("", m.by());
        assertEquals("odd|name", m.name());
    }

    /** Audit 5: the parse is anchored on the uuid, so a server id with a colon is read whole. */
    @Test
    void aServerIdWithAColonIsReadWhole() {
        PlayerResetMessage m = PlayerResetMessage.parse("player_reset:mc-main:BSP-Spawn:" + STEVE + "|42|Mrgregles|Steve")
                .orElseThrow();

        assertEquals("mc-main:BSP-Spawn", m.origin());
        assertEquals(UUID.fromString(STEVE), m.player());
        assertEquals(42L, m.resetMs());
        assertEquals("Steve", m.name());
    }

    @Test
    void otherKindsOnTheChannelAreIgnored() {
        assertTrue(PlayerResetMessage.parse("shop:BSP-Spawn:" + STEVE + "|1|a|b").isEmpty());
        assertTrue(PlayerResetMessage.parse("credit:BSP-Spawn:whatever").isEmpty());
        assertTrue(PlayerResetMessage.parse("player_resets:BSP-Spawn:" + STEVE + "|1|a|b").isEmpty());
        assertTrue(PlayerResetMessage.parse("{\"v\":1,\"type\":\"warning\"}").isEmpty(), "a Discord alert");
    }

    @Test
    void malformedNoticesAreIgnored() {
        for (String bad : new String[] {
                null,
                "",
                "player_reset",
                "player_reset:BSP-Spawn",
                "player_reset:BSP-Spawn:" + STEVE,
                "player_reset:BSP-Spawn:" + STEVE + "|1764000000000|Mrgregles",
                "player_reset:BSP-Spawn:not-a-uuid|1764000000000|Mrgregles|Steve",
                "player_reset:BSP-Spawn:" + STEVE + "|soon|Mrgregles|Steve",
                "player_reset:BSP-Spawn:" + STEVE + "|0|Mrgregles|Steve",
                "player_reset:BSP-Spawn:" + STEVE + "|-5|Mrgregles|Steve",
                "player_reset:BSP-Spawn:" + STEVE + "|99999999999999999999|Mrgregles|Steve",
                "player_reset::" + STEVE + "|1|Mrgregles|Steve",
                "player_reset:BSP-Spawn:" + STEVE + "x|1|Mrgregles|Steve",
        }) {
            assertEquals(Optional.empty(), PlayerResetMessage.parse(bad), String.valueOf(bad));
        }
    }
}
