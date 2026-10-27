package com.mrgregles.bsp_velocity_additions.journeymap;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;

/**
 * The bytes on {@code worldinfo:world_id}, as JourneyMap 6.0.7 for Forge 1.20.1 writes and reads them (checked in its
 * jar: {@code journeymap.common.network.packets.WorldIdPacket}, sent through the bundled Common Network 1.0.6 as a Forge
 * {@code SimpleChannel} with message index 0):
 * <pre>
 *   00          SimpleChannel message index
 *   00 2A       JourneyMap's own two bytes (2A is 42, the "magic" byte VoxelMap started)
 *   len id      the id as a Minecraft string: a VarInt byte count, then UTF-8
 * </pre>
 * The client's request is the same packet with an empty id ({@code 00 00 2A 00}). The reader also accepts the older
 * VoxelMap and MapModCompanion layouts (any number of leading zeros, the magic byte optional), so a debug log can
 * show what any client or server sent.
 */
public final class WorldIdPacket {

    static final int MAGIC = 42;
    /** Minecraft's limit for a string in a packet, in characters; JourneyMap reads at most this. */
    static final int MAX_CHARS = 32767;

    private WorldIdPacket() {
    }

    /** The reply carrying {@code id}. */
    public static byte[] encode(String id) {
        byte[] text = id.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length + 8);
        out.write(0);
        out.write(0);
        out.write(MAGIC);
        writeVarInt(out, text.length);
        out.write(text, 0, text.length);
        return out.toByteArray();
    }

    /** The id in a packet: empty for a request, the id for a reply; nothing when the bytes are not this format. */
    public static Optional<String> decode(byte[] data) {
        int i = 0;
        while (i < data.length && data[i] == 0) {
            i++;
        }
        if (i == data.length) {
            return i >= 2 ? Optional.of("") : Optional.empty();
        }
        if ((data[i] & 0xFF) == MAGIC) {
            i++;
        }
        int[] position = {i};
        Integer length = readVarInt(data, position);
        if (length == null || length < 0 || position[0] + length > data.length || length > MAX_CHARS * 3) {
            return Optional.empty();
        }
        return Optional.of(new String(data, position[0], length, StandardCharsets.UTF_8));
    }

    /** Lower-case hex with a space between bytes, for the debug log. */
    public static String hex(byte[] data) {
        return HexFormat.ofDelimiter(" ").formatHex(data);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static Integer readVarInt(byte[] data, int[] position) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (position[0] >= data.length) {
                return null;
            }
            int b = data[position[0]++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        return null;
    }
}
