package com.mrgregles.bsp_velocity_additions.chat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * One message on BSP-Core's {@code bsp_core:proxy} channel (BSP-Core docs/API.md, "Proxy relay", request VA-C2).
 * Layout, version 1, in Minecraft's FriendlyByteBuf encodings: version (byte), kind (Utf), sender (UUID as two
 * big-endian longs), name (Utf), text (Utf), json (Utf), server (Utf); Utf is a VarInt byte length and UTF-8.
 */
public record RelayPayload(int version, String kind, UUID sender, String name, String text, String json, String server) {

    public static final int VERSION = 1;
    public static final String KIND_CHAT = "chat";

    /** Decodes the bytes; empty when they are not a well-formed payload. */
    public static Optional<RelayPayload> decode(byte[] bytes) {
        try {
            Reader in = new Reader(bytes);
            int version = in.readByte();
            String kind = in.readUtf(32);
            UUID sender = new UUID(in.readLong(), in.readLong());
            String name = in.readUtf(64);
            String text = in.readUtf(32767);
            String json = in.readUtf(32767);
            String server = in.readUtf(64);
            return Optional.of(new RelayPayload(version, kind, sender, name, text, json, server));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    public boolean isChat() {
        return version == VERSION && KIND_CHAT.equals(kind);
    }

    /** A cursor over the bytes with Minecraft's encodings. Throws on anything short or oversized. */
    static final class Reader {
        private final byte[] bytes;
        private int pos;

        Reader(byte[] bytes) {
            this.bytes = bytes;
        }

        int readByte() {
            if (pos >= bytes.length) {
                throw new IllegalStateException("short");
            }
            return bytes[pos++] & 0xFF;
        }

        long readLong() {
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | readByte();
            }
            return v;
        }

        int readVarInt() {
            int value = 0;
            int shift = 0;
            while (true) {
                int b = readByte();
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
                shift += 7;
                if (shift > 28) {
                    throw new IllegalStateException("VarInt too long");
                }
            }
        }

        String readUtf(int maxChars) {
            int length = readVarInt();
            if (length < 0 || length > maxChars * 3 || pos + length > bytes.length) {
                throw new IllegalStateException("bad string length " + length);
            }
            String s = new String(bytes, pos, length, StandardCharsets.UTF_8);
            pos += length;
            if (s.length() > maxChars) {
                throw new IllegalStateException("string too long");
            }
            return s;
        }
    }
}
