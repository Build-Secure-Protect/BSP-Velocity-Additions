package com.mrgregles.bsp_velocity_additions.kick;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SU-VA2: a network kick asked by a backend on {@code bsp_server_utilities:proxy}. */
class NetworkKickFeatureTest {

    /** Writes what Server Utilities sends: FriendlyByteBuf's byte, Utf (VarInt length, UTF-8) and UUID (two longs). */
    static final class Writer {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Writer writeByte(int b) { out.write(b); return this; }

        Writer writeUtf(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            int v = b.length;
            while ((v & ~0x7F) != 0) { out.write((v & 0x7F) | 0x80); v >>>= 7; }
            out.write(v);
            out.write(b, 0, b.length);
            return this;
        }

        Writer writeUuid(UUID u) {
            for (long v : new long[] {u.getMostSignificantBits(), u.getLeastSignificantBits()}) {
                for (int i = 7; i >= 0; i--) { out.write((int) (v >>> (8 * i)) & 0xFF); }
            }
            return this;
        }

        byte[] bytes() { return out.toByteArray(); }
    }

    static byte[] kick(int version, String kind, UUID player, String reasonJson) {
        return new Writer().writeByte(version).writeUtf(kind).writeUuid(player).writeUtf(reasonJson).bytes();
    }

    static final String REASON = "{\"text\":\"AFK for 30 minutes\",\"color\":\"red\"}";

    private final ProxyServer proxy = mock(ProxyServer.class);
    private final Logger logger = mock(Logger.class);
    private final Player steve = mock(Player.class);
    private final UUID steveId = UUID.fromString("aff80d73-fa58-464d-b419-11187ffd8cd3");
    private NetworkKickFeature feature;

    @BeforeEach
    void setUp() {
        when(steve.getUniqueId()).thenReturn(steveId);
        when(steve.getUsername()).thenReturn("Steve");
        when(proxy.getPlayer(any(UUID.class))).thenReturn(Optional.empty());
        when(proxy.getPlayer(steveId)).thenReturn(Optional.of(steve));
        feature = new NetworkKickFeature(proxy, true, logger);
    }

    private static ServerConnection backend(String name) {
        ServerConnection c = mock(ServerConnection.class);
        when(c.getServerInfo()).thenReturn(new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565)));
        return c;
    }

    private PluginMessageEvent fromBackend(byte[] data) {
        return new PluginMessageEvent(backend("BSP-Spawn"), steve, NetworkKickFeature.IDENTIFIER, data);
    }

    @Test
    void aKickFromABackendDisconnectsThePlayerFromTheProxyWithTheReason() {
        PluginMessageEvent event = fromBackend(kick(1, "kick", steveId, REASON));

        feature.onPluginMessage(event);

        verify(steve).disconnect(Component.text("AFK for 30 minutes", NamedTextColor.RED));
        assertFalse(event.getResult().isAllowed(), "handled: the bytes never reach the client");
        verify(logger).info("[{}] kicked {}: {}", "BSP-Spawn", "Steve", "AFK for 30 minutes");
    }

    @Test
    void theSameBytesFromAClientAreIgnoredAndSwallowed() {
        PluginMessageEvent event = new PluginMessageEvent(steve, backend("BSP-Spawn"), NetworkKickFeature.IDENTIFIER,
                kick(1, "kick", steveId, REASON));

        feature.onPluginMessage(event);

        verify(steve, never()).disconnect(any());
        assertFalse(event.getResult().isAllowed(), "not forwarded to the backend either");
    }

    @Test
    void anUnknownVersionOrKindIsIgnored() {
        feature.onPluginMessage(fromBackend(kick(2, "kick", steveId, REASON)));
        feature.onPluginMessage(fromBackend(kick(1, "ban", steveId, REASON)));

        verify(steve, never()).disconnect(any());
        verify(logger, never()).info(anyString(), any(Object[].class));
    }

    @Test
    void garbageAndTruncatedBytesAreIgnored() {
        byte[] whole = kick(1, "kick", steveId, REASON);
        feature.onPluginMessage(fromBackend(new byte[] {1, 2, 3}));
        feature.onPluginMessage(fromBackend(java.util.Arrays.copyOf(whole, whole.length - 5)));
        feature.onPluginMessage(fromBackend(new byte[0]));

        verify(steve, never()).disconnect(any());
    }

    @Test
    void aPlayerWhoIsAlreadyGoneIsNoError() {
        feature.onPluginMessage(fromBackend(kick(1, "kick", UUID.randomUUID(), REASON)));

        verify(steve, never()).disconnect(any());
    }

    @Test
    void unreadableReasonJsonStillKicksWithVanillasReason() {
        feature.onPluginMessage(fromBackend(kick(1, "kick", steveId, "{not json")));
        feature.onPluginMessage(fromBackend(kick(1, "kick", steveId, "")));

        verify(steve, org.mockito.Mockito.times(2)).disconnect(Component.translatable("multiplayer.disconnect.kicked"));
    }

    @Test
    void otherChannelsAreLeftAlone() {
        PluginMessageEvent event = new PluginMessageEvent(backend("BSP-Spawn"), steve,
                MinecraftChannelIdentifier.from("bsp_core:proxy"), kick(1, "kick", steveId, REASON));

        feature.onPluginMessage(event);

        assertTrue(event.getResult().isAllowed());
        verify(steve, never()).disconnect(any());
    }

    /** Switched off, the message is still swallowed (Velocity would pass an unregistered channel to the client). */
    @Test
    void switchedOffNobodyIsKickedAndNothingReachesTheClient() {
        feature = new NetworkKickFeature(proxy, false, logger);
        PluginMessageEvent event = fromBackend(kick(1, "kick", steveId, REASON));

        feature.onPluginMessage(event);

        verify(steve, never()).disconnect(any());
        assertFalse(event.getResult().isAllowed());
    }

    @Test
    void theChannelNameIsTheOneServerUtilitiesUses() {
        assertEquals("bsp_server_utilities:proxy", NetworkKickFeature.IDENTIFIER.getId());
    }
}
