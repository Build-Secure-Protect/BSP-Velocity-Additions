package com.mrgregles.bsp_velocity_additions.chat;

import com.mrgregles.bsp_velocity_additions.config.Messages;
import com.mrgregles.bsp_velocity_additions.config.PluginConfig;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatRelayFeatureTest {

    /** The client translates a real line's chat.type.text itself; the plain serializer used here cannot, so the test JSON is a text component. */
    private static final String JSON = "{\"text\":\"<Steve> hi all\",\"color\":\"gold\"}";

    private final ProxyServer proxy = mock(ProxyServer.class);
    private final Player steve = player("Steve", "BSP-Wild");    // the speaker
    private final Player alex = player("Alex", "BSP-Wild");      // same server: already saw it
    private final Player nadia = player("Nadia", "BSP-Spawn");   // other server
    private final Player mod = player("Dani", "BSP-SafeRoom");   // other server, staff
    private final Player limbo = player("Ghost", null);
    private final Logger logger = mock(Logger.class);
    private ChatRelayFeature feature;

    @BeforeEach
    void setUp() {
        when(proxy.getAllPlayers()).thenReturn(List.of(steve, alex, nadia, mod, limbo));
        when(mod.hasPermission("bspva.players.servers")).thenReturn(true);
        feature = feature(true);
    }

    private ChatRelayFeature feature(boolean backendFormatting) {
        return new ChatRelayFeature(proxy, new PluginConfig.Chat(true, backendFormatting, true),
                new PluginConfig.Players(true, "bspva.players.servers"), Messages.defaults(), logger);
    }

    private static Player player(String name, String server) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.getUsername()).thenReturn(name);
        Optional<ServerConnection> on = server == null ? Optional.empty() : Optional.of(connection(server));
        when(p.getCurrentServer()).thenReturn(on);
        return p;
    }

    private static ServerConnection connection(String server) {
        ServerConnection c = mock(ServerConnection.class);
        when(c.getServerInfo()).thenReturn(new ServerInfo(server, new InetSocketAddress("127.0.0.1", 25565)));
        return c;
    }

    private PluginMessageEvent fromBackend(String server, byte[] data) {
        return new PluginMessageEvent(connection(server), steve, MinecraftChannelIdentifier.from(ChatRelayFeature.CHANNEL), data);
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static Optional<String> lineSentTo(Player p) {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(p, org.mockito.Mockito.atMost(1)).sendMessage(captor.capture());
        return captor.getAllValues().stream().findFirst().map(ChatRelayFeatureTest::plain);
    }

    @Test
    void aLineFromABackendReachesOtherServersOnlyAndIsHandled() {
        PluginMessageEvent event = fromBackend("BSP-Wild", RelayPayloadTest.chat(steve.getUniqueId(), "Steve", "hi all", JSON, "BSP-Wild"));

        feature.onPluginMessage(event);

        assertFalse(event.getResult().isAllowed(), "handled: the bytes do not go on to the client");
        assertEquals(Optional.of("<Steve> hi all"), lineSentTo(nadia), "the backend's own decorated line, rendered from its JSON");
        assertEquals(Optional.of("[BSP-Wild] <Steve> hi all"), lineSentTo(mod), "staff see the server tag");
        verify(steve, never()).sendMessage(any(Component.class));
        verify(alex, never()).sendMessage(any(Component.class));
        verify(limbo, never()).sendMessage(any(Component.class));
    }

    @Test
    void withoutBackendFormattingThePlainFormIsUsed() {
        feature = feature(false);

        feature.onPluginMessage(fromBackend("BSP-Wild", RelayPayloadTest.chat(steve.getUniqueId(), "Steve", "hi all", JSON, "BSP-Wild")));

        assertEquals(Optional.of("<Steve> hi all"), lineSentTo(nadia));
    }

    @Test
    void unusableJsonFallsBackToThePlainForm() {
        feature.onPluginMessage(fromBackend("BSP-Wild", RelayPayloadTest.chat(steve.getUniqueId(), "Steve", "hi all", "{not json", "BSP-Wild")));

        assertEquals(Optional.of("<Steve> hi all"), lineSentTo(nadia));
    }

    @Test
    void bytesFromAClientAreDroppedButStillSwallowed() {
        PluginMessageEvent event = new PluginMessageEvent(steve, connection("BSP-Wild"),
                MinecraftChannelIdentifier.from(ChatRelayFeature.CHANNEL), RelayPayloadTest.chat(steve.getUniqueId(), "Steve", "spoof", "", "BSP-Wild"));

        feature.onPluginMessage(event);

        assertFalse(event.getResult().isAllowed());
        verify(nadia, never()).sendMessage(any(Component.class));
    }

    @Test
    void otherChannelsAreLeftAlone() {
        PluginMessageEvent event = new PluginMessageEvent(connection("BSP-Wild"), steve, MinecraftChannelIdentifier.from("bungeecord:main"), new byte[] {1});

        feature.onPluginMessage(event);

        assertTrue(event.getResult().isAllowed());
        verify(nadia, never()).sendMessage(any(Component.class));
    }

    @Test
    void anUnknownKindOrGarbageIsIgnored() {
        byte[] garbage = {1, 2, 3};
        feature.onPluginMessage(fromBackend("BSP-Wild", garbage));
        byte[] v9 = RelayPayloadTest.chat(steve.getUniqueId(), "Steve", "future", "", "BSP-Wild");
        v9[0] = 9;
        feature.onPluginMessage(fromBackend("BSP-Wild", v9));

        verify(nadia, never()).sendMessage(any(Component.class));
    }

    @Test
    void theTagUsesVelocitysServerNameAndWarnsOnceAboutAMismatch() {
        byte[] data = RelayPayloadTest.chat(steve.getUniqueId(), "Steve", "hi", "", "wild-1");
        feature.onPluginMessage(fromBackend("BSP-Wild", data));
        feature.onPluginMessage(fromBackend("BSP-Wild", data));

        verify(logger, org.mockito.Mockito.times(1)).warn(any(String.class), any(), any());
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(mod, org.mockito.Mockito.times(2)).sendMessage(captor.capture());
        assertEquals("[BSP-Wild] <Steve> hi", plain(captor.getAllValues().get(0)));
    }
}
