package com.mrgregles.bsp_velocity_additions.journeymap;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The layout of JourneyMap 6.0.7's WorldIdPacket on a Forge SimpleChannel (index 0), read from its jar. */
class WorldIdPacketTest {

    @Test
    void writesIndexThenZeroThenMagicThenAMinecraftString() {
        assertArrayEquals(new byte[] {0, 0, 42, 3, 'a', 'b', 'c'}, WorldIdPacket.encode("abc"));
        assertEquals("00 00 2a 03 61 62 63", WorldIdPacket.hex(WorldIdPacket.encode("abc")));
    }

    @Test
    void aUuidIdRoundTrips() {
        String id = "cc7a1dc1-1f9f-4922-83e4-cb353ca585cd";
        byte[] data = WorldIdPacket.encode(id);

        assertEquals(3 + 1 + 36, data.length);
        assertEquals(Optional.of(id), WorldIdPacket.decode(data));
    }

    @Test
    void aLongIdUsesATwoByteLength() {
        String id = "x".repeat(200);
        byte[] data = WorldIdPacket.encode(id);

        assertEquals((byte) 0xC8, data[3]);
        assertEquals(0x01, data[4]);
        assertEquals(Optional.of(id), WorldIdPacket.decode(data));
    }

    @Test
    void theClientsRequestIsAnEmptyId() {
        assertEquals(Optional.of(""), WorldIdPacket.decode(new byte[] {0, 0, 42, 0}));
        assertEquals(Optional.of(""), WorldIdPacket.decode(new byte[] {0, 0}));
    }

    @Test
    void readsTheOlderVoxelMapAndMapModCompanionLayouts() {
        assertEquals(Optional.of("12"), WorldIdPacket.decode(new byte[] {0, 42, 2, '1', '2'}));
        assertEquals(Optional.of("12"), WorldIdPacket.decode(new byte[] {0, 2, '1', '2'}));
    }

    @Test
    void refusesBrokenBytes() {
        assertTrue(WorldIdPacket.decode(new byte[0]).isEmpty());
        assertTrue(WorldIdPacket.decode(new byte[] {0}).isEmpty());
        assertTrue(WorldIdPacket.decode(new byte[] {0, 0, 42, 9, 'a'}).isEmpty(), "a length past the end");
        assertTrue(WorldIdPacket.decode(new byte[] {0, 0, 42, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}).isEmpty());
    }
}
