package com.mrgregles.bsp_velocity_additions.reset;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D45: the {@code season_reset} notice BSP-Core publishes on its Redis channel ({@code NetworkStorage.seasonResetPublish}). */
class SeasonResetMessageTest {

    /** VA-C4: BSP-Core's {@code NetworkStorage.seasonResetPayload(3, 12)} is {@code 3|12}, the starting season last. */
    @Test
    void readsBspCoresNoticeWithTheNewSeasonsNumber() {
        SeasonResetMessage m = SeasonResetMessage.parse("season_reset:BSP-Spawn:3|12").orElseThrow();

        assertEquals("BSP-Spawn", m.origin());
        assertEquals(3, m.resetNumber());
        assertEquals(OptionalInt.of(12), m.season());
    }

    /** A BSP-Core from before VA-C4 sends the reset number alone. */
    @Test
    void readsAnOlderNoticeWithoutTheSeasonNumber() {
        SeasonResetMessage m = SeasonResetMessage.parse("season_reset:BSP-Spawn:4").orElseThrow();

        assertEquals("BSP-Spawn", m.origin());
        assertEquals(4, m.resetNumber());
        assertEquals(OptionalInt.empty(), m.season());
    }

    @Test
    void aSeasonNumberOfZeroIsNotUsed() {
        SeasonResetMessage m = SeasonResetMessage.parse("season_reset:BSP-Spawn:4|7").orElseThrow();

        assertEquals(4, m.resetNumber());
        assertEquals(OptionalInt.of(7), m.season());
        assertEquals(OptionalInt.empty(), SeasonResetMessage.parse("season_reset:BSP-Spawn:4|0").orElseThrow().season(),
                "season 0 is not a season: the wording without a number is used");
    }

    /** Edge audit: BSP-Core asks readers to stop at the {@code |}, so later fields never cost the reset. */
    @Test
    void fieldsAfterTheSeasonAreIgnoredAndAnOddSeasonMeansNoNumber() {
        SeasonResetMessage more = SeasonResetMessage.parse("season_reset:BSP-Spawn:4|12|1764000000000").orElseThrow();
        assertEquals(4, more.resetNumber());
        assertEquals(OptionalInt.of(12), more.season());

        for (String odd : new String[] {"season_reset:BSP-Spawn:4|", "season_reset:BSP-Spawn:4|x", "season_reset:BSP-Spawn:4|x|12",
                "season_reset:BSP-Spawn:4|9999999999"}) {
            SeasonResetMessage m = SeasonResetMessage.parse(odd).orElseThrow();
            assertEquals(4, m.resetNumber(), odd);
            assertEquals(OptionalInt.empty(), m.season(), odd);
        }
    }

    @Test
    void aServerIdWithAColonIsReadWhole() {
        SeasonResetMessage m = SeasonResetMessage.parse("season_reset:mc-main:BSP-Spawn:12").orElseThrow();

        assertEquals("mc-main:BSP-Spawn", m.origin());
        assertEquals(12, m.resetNumber());
    }

    @Test
    void otherKindsOnTheChannelAreIgnored() {
        assertTrue(SeasonResetMessage.parse("player_reset:BSP-Spawn:aff80d73-fa58-464d-b419-11187ffd8cd3|1|a|b").isEmpty());
        assertTrue(SeasonResetMessage.parse("market:BSP-Spawn:").isEmpty());
        assertTrue(SeasonResetMessage.parse("season_resets:BSP-Spawn:4").isEmpty());
        assertTrue(SeasonResetMessage.parse("season:BSP-Spawn:4").isEmpty(), "the season sync, not a reset");
        assertFalse(SeasonResetMessage.isSeasonResetKind("market:BSP-Spawn:"));
        assertFalse(SeasonResetMessage.isSeasonResetKind(null));
    }

    @Test
    void malformedNoticesAreIgnored() {
        for (String bad : new String[] {
                "season_reset",
                "season_reset:",
                "season_reset:BSP-Spawn",
                "season_reset:BSP-Spawn:",
                "season_reset::4",
                "season_reset:BSP-Spawn:0",
                "season_reset:BSP-Spawn:-3",
                "season_reset:BSP-Spawn:four",
                "season_reset:BSP-Spawn:4 ",
                "season_reset:BSP-Spawn:4|7\nINFO fake",
                "season_reset:BSP|Spawn:4",
                "season_reset:BSP-Spawn:9999999999",
                "season_reset:BSP-Spawn:4\nINFO fake",
        }) {
            assertEquals(Optional.empty(), SeasonResetMessage.parse(bad), bad);
            assertEquals(bad.startsWith("season_reset:"), SeasonResetMessage.isSeasonResetKind(bad), bad);
        }
    }
}
