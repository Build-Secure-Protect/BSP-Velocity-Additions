package com.mrgregles.bsp_velocity_additions.kick;

import com.mrgregles.bsp_velocity_additions.chat.RelayPayload;

import java.util.Optional;
import java.util.UUID;

/**
 * One message on BSP-Server Utilities' {@code bsp_server_utilities:proxy} channel (request SU-VA2). Layout, version
 * 1, in Minecraft's FriendlyByteBuf encodings as {@link RelayPayload}: version (byte), kind (Utf), player (UUID as two
 * big-endian longs), reason (Utf, Component JSON).
 */
public record KickPayload(int version, String kind, UUID player, String reasonJson) {

    public static final int VERSION = 1;
    public static final String KIND_KICK = "kick";

    /** Decodes the bytes; empty when they are not a well-formed payload. */
    public static Optional<KickPayload> decode(byte[] bytes) {
        try {
            RelayPayload.Reader in = new RelayPayload.Reader(bytes);
            int version = in.readByte();
            String kind = in.readUtf(32);
            UUID player = new UUID(in.readLong(), in.readLong());
            String reason = in.readUtf(262144);
            return Optional.of(new KickPayload(version, kind, player, reason));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    public boolean isKick() {
        return version == VERSION && KIND_KICK.equals(kind);
    }
}
