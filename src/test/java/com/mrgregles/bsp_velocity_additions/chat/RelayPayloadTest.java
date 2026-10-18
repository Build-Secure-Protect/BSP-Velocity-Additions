package com.mrgregles.bsp_velocity_additions.chat;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayPayloadTest {

    /** Mirrors Minecraft's FriendlyByteBuf: writeByte, writeUtf (VarInt length + UTF-8), writeUUID (two longs). */
    static final class Writer {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Writer writeByte(int b) { out.write(b); return this; }

        Writer writeVarInt(int v) {
            while ((v & ~0x7F) != 0) { out.write((v & 0x7F) | 0x80); v >>>= 7; }
            out.write(v);
            return this;
        }

        Writer writeUtf(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            writeVarInt(b.length);
            out.write(b, 0, b.length);
            return this;
        }

        Writer writeLong(long v) {
            for (int i = 7; i >= 0; i--) { out.write((int) (v >>> (8 * i)) & 0xFF); }
            return this;
        }

        Writer writeUuid(UUID u) { return writeLong(u.getMostSignificantBits()).writeLong(u.getLeastSignificantBits()); }

        byte[] bytes() { return out.toByteArray(); }
    }

    static byte[] chat(UUID sender, String name, String text, String json, String server) {
        return new Writer().writeByte(1).writeUtf("chat").writeUuid(sender).writeUtf(name).writeUtf(text).writeUtf(json).writeUtf(server).bytes();
    }

    @Test
    void decodesWhatBspCoreEncodes() {
        UUID id = UUID.fromString("aff80d73-fa58-464d-b419-11187ffd8cd3");
        String json = "{\"translate\":\"chat.type.text\",\"with\":[\"Steve\",\"héllo ✦\"]}";

        RelayPayload p = RelayPayload.decode(chat(id, "Steve", "héllo ✦", json, "BSP-Wild")).orElseThrow();

        assertEquals(1, p.version());
        assertEquals("chat", p.kind());
        assertEquals(id, p.sender());
        assertEquals("Steve", p.name());
        assertEquals("héllo ✦", p.text());
        assertEquals(json, p.json());
        assertEquals("BSP-Wild", p.server());
        assertTrue(p.isChat());
    }

    @Test
    void aLongMessageUsesAMultiByteVarInt() {
        String text = "x".repeat(300);

        RelayPayload p = RelayPayload.decode(chat(UUID.randomUUID(), "A", text, "", "s")).orElseThrow();

        assertEquals(text, p.text());
    }

    @Test
    void unknownVersionOrKindIsNotChat() {
        byte[] v2 = chat(UUID.randomUUID(), "A", "t", "", "s");
        v2[0] = 2;
        assertFalse(RelayPayload.decode(v2).orElseThrow().isChat());

        byte[] other = new Writer().writeByte(1).writeUtf("ping").writeUuid(UUID.randomUUID()).writeUtf("A").writeUtf("t").writeUtf("").writeUtf("s").bytes();
        assertFalse(RelayPayload.decode(other).orElseThrow().isChat());
    }

    @Test
    void shortOrGarbageBytesDecodeToNothing() {
        byte[] good = chat(UUID.randomUUID(), "A", "t", "", "s");

        assertEquals(Optional.empty(), RelayPayload.decode(Arrays.copyOf(good, good.length - 3)));
        assertEquals(Optional.empty(), RelayPayload.decode(new byte[0]));
        assertEquals(Optional.empty(), RelayPayload.decode(new byte[] {1, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));
    }
}
