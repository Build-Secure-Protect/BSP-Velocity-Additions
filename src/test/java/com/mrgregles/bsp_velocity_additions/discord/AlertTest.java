package com.mrgregles.bsp_velocity_additions.discord;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The layout in BSP-Core's docs/API.md, "Discord alerts", version 1. */
class AlertTest {

    static final String PLAYER = "0f3c1a2b-3c4d-4e5f-8a9b-0c1d2e3f4a5b";

    @Test
    void readsTheApiExampleWarning() {
        Alert a = Alert.parse("{\"v\":1,\"type\":\"warning\",\"server\":\"Frostwarf\",\"sentAt\":1761419040000,\"player\":\"" + PLAYER
                + "\",\"world\":\"minecraft:overworld\",\"x\":120,\"y\":64,\"z\":-340,\"thief\":\"Steve\",\"secondsLeft\":45}").orElseThrow();

        Alert.Warning w = assertInstanceOf(Alert.Warning.class, a);
        assertEquals(UUID.fromString(PLAYER), w.player());
        assertEquals(new Alert.Place("Frostwarf", "minecraft:overworld", 120, 64, -340), w.place());
        assertEquals("Steve", w.thief());
        assertEquals(45, w.secondsLeft());
        assertEquals(1761419040000L, w.sentAt());
    }

    @Test
    void readsSavedStolenChannelAndUnlink() {
        String place = ",\"server\":\"S\",\"sentAt\":5,\"player\":\"" + PLAYER + "\",\"world\":\"w\",\"x\":1,\"y\":2,\"z\":3,\"thief\":\"T\"";
        assertEquals("totem_gone", assertInstanceOf(Alert.Saved.class,
                Alert.parse("{\"v\":1,\"type\":\"saved\"" + place + ",\"reason\":\"totem_gone\"}").orElseThrow()).reason());
        assertEquals("T", assertInstanceOf(Alert.Stolen.class, Alert.parse("{\"v\":1,\"type\":\"stolen\"" + place + "}").orElseThrow()).thief());
        assertEquals(new Alert.Channel("S", 5L), Alert.parse("{\"v\":1,\"type\":\"channel\",\"server\":\"S\",\"sentAt\":5}").orElseThrow());
        assertEquals(new Alert.Unlink(UUID.fromString(PLAYER), 5L),
                Alert.parse("{\"v\":1,\"type\":\"unlink\",\"player\":\"" + PLAYER + "\",\"sentAt\":5}").orElseThrow());
    }

    @Test
    void ignoresWhatItDoesNotKnow() {
        assertTrue(Alert.parse("{\"v\":2,\"type\":\"channel\",\"server\":\"S\",\"sentAt\":5}").isEmpty());
        assertTrue(Alert.parse("{\"type\":\"channel\",\"server\":\"S\",\"sentAt\":5}").isEmpty());
        assertTrue(Alert.parse("{\"v\":1,\"type\":\"raid\",\"server\":\"S\",\"sentAt\":5}").isEmpty());
        assertTrue(Alert.parse("{\"v\":1,\"type\":\"warning\",\"server\":\"S\",\"sentAt\":5}").isEmpty());
        assertTrue(Alert.parse("{\"v\":1,\"type\":\"unlink\",\"player\":\"not-a-uuid\",\"sentAt\":5}").isEmpty());
        assertTrue(Alert.parse("{\"v\":\"one\",\"type\":\"channel\"}").isEmpty());
        assertTrue(Alert.parse("bsp_core:discord:{}").isEmpty());
        assertTrue(Alert.parse("").isEmpty());
        assertTrue(Alert.parse("null").isEmpty());
    }
}
