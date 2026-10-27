package com.mrgregles.bsp_velocity_additions.journeymap;

import com.mrgregles.bsp_velocity_additions.config.PluginConfig;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChannelRegisterEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorldIdFeatureTest {

    static final byte[] REQUEST = {0, 0, 42, 0};

    /** Tasks the feature asked to run later, with their delay; run by hand. */
    final List<Runnable> later = new ArrayList<>();
    final List<Long> delays = new ArrayList<>();
    final Logger logger = mock(Logger.class);
    final WorldIds ids = new WorldIds(Map.of("bsp-safeRoom", "kept-saferoom-id"), logger);
    final AtomicReference<String> currentServer = new AtomicReference<>();
    Player steve;
    WorldIdFeature feature;

    @BeforeEach
    void setUp() {
        steve = mock(Player.class);
        when(steve.getUniqueId()).thenReturn(UUID.randomUUID());
        when(steve.getUsername()).thenReturn("Steve");
        when(steve.isActive()).thenReturn(true);
        when(steve.getCurrentServer()).thenAnswer(i -> Optional.ofNullable(currentServer.get()).map(this::connection));
        feature = feature(false);
    }

    WorldIdFeature feature(boolean debug) {
        return new WorldIdFeature(new PluginConfig.JourneyMap(true, debug, debug, 1500, Map.of()), ids,
                (millis, task) -> {
                    delays.add(millis);
                    later.add(task);
                }, logger);
    }

    ServerConnection connection(String name) {
        ServerConnection c = mock(ServerConnection.class);
        when(c.getServerInfo()).thenReturn(info(name));
        when(c.getPlayer()).thenReturn(steve);
        return c;
    }

    static ServerInfo info(String name) {
        return new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565));
    }

    static RegisteredServer server(String name) {
        RegisteredServer s = mock(RegisteredServer.class);
        when(s.getServerInfo()).thenReturn(info(name));
        return s;
    }

    /** Velocity's order for a join or switch: connected (new server accepted), then post-connect. */
    void moveTo(String name) {
        RegisteredServer previous = currentServer.get() == null ? null : server(currentServer.get());
        feature.onServerConnected(new ServerConnectedEvent(steve, server(name), previous));
        currentServer.set(name);
        feature.onServerPostConnect(new ServerPostConnectEvent(steve, previous));
    }

    PluginMessageEvent fromClient(byte[] data) {
        return new PluginMessageEvent(steve, connection(currentServer.get()), WorldIdFeature.IDENTIFIER, data);
    }

    PluginMessageEvent fromBackend(String server, byte[] data) {
        return new PluginMessageEvent(connection(server), steve, WorldIdFeature.IDENTIFIER, data);
    }

    List<String> idsSent() {
        ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
        verify(steve, org.mockito.Mockito.atLeast(0)).sendPluginMessage(any(ChannelIdentifier.class), sent.capture());
        return sent.getAllValues().stream().map(b -> WorldIdPacket.decode(b).orElseThrow()).toList();
    }

    void runLater() {
        List<Runnable> due = new ArrayList<>(later);
        later.clear();
        due.forEach(Runnable::run);
    }

    // ------------------------------------------------------------------

    @Test
    void aRequestIsAnsweredByTheProxyWithTheCurrentServersIdAndNotForwarded() {
        moveTo("BSP-Spawn");
        PluginMessageEvent request = fromClient(REQUEST);

        feature.onPluginMessage(request);

        assertFalse(request.getResult().isAllowed(), "the request never reaches the backend");
        assertEquals(List.of(WorldIds.derived("BSP-Spawn")), idsSent());
        verify(steve).sendPluginMessage(WorldIdFeature.IDENTIFIER, WorldIdPacket.encode(WorldIds.derived("BSP-Spawn")));
    }

    @Test
    void aBackendsOwnReplyIsDroppedSoItCannotRace() {
        moveTo("BSP-Spawn");
        PluginMessageEvent reply = fromBackend("BSP-Spawn", WorldIdPacket.encode("cc7a1dc1-1f9f-4922-83e4-cb353ca585cd"));

        feature.onPluginMessage(reply);

        assertFalse(reply.getResult().isAllowed());
        assertTrue(idsSent().isEmpty());
    }

    @Test
    void afterASwitchTheNewServersIdIsSentAgainAfterTheDelay() {
        moveTo("BSP-Spawn");
        feature.onPluginMessage(fromClient(REQUEST));

        moveTo("BSP-Frostwarf");
        assertEquals(List.of(1500L), delays);
        assertEquals(1, idsSent().size(), "nothing before the delay");
        runLater();

        assertEquals(List.of(WorldIds.derived("BSP-Spawn"), WorldIds.derived("BSP-Frostwarf")), idsSent());
    }

    @Test
    void aRequestDuringASwitchGetsTheNewServersId() {
        moveTo("BSP-Spawn");
        feature.onServerConnected(new ServerConnectedEvent(steve, server("BSP-Frostwarf"), server("BSP-Spawn")));
        // the client is still drawing Spawn's world and asks before Velocity has finished the switch

        feature.onPluginMessage(fromClient(REQUEST));

        assertEquals(List.of(WorldIds.derived("BSP-Frostwarf")), idsSent());
    }

    @Test
    void switchingBackAndForthAlwaysEndsOnTheRightIdAndDropsStaleResends() {
        moveTo("BSP-Spawn");
        feature.onPluginMessage(fromClient(REQUEST));
        moveTo("BSP-Frostwarf");
        moveTo("BSP-Spawn");
        moveTo("BSP-Frostwarf");
        runLater();

        assertEquals(List.of(WorldIds.derived("BSP-Spawn"), WorldIds.derived("BSP-Frostwarf")), idsSent(),
                "only the newest switch's resend goes out");
    }

    @Test
    void aClientThatNeverAskedGetsNothingSoClientsWithoutJourneyMapAreSafe() {
        moveTo("BSP-Spawn");
        moveTo("BSP-Frostwarf");
        runLater();

        assertTrue(later.isEmpty());
        assertTrue(idsSent().isEmpty());
        verify(steve, never()).sendPluginMessage(any(ChannelIdentifier.class), any(byte[].class));
    }

    @Test
    void nothingIsSentToAPlayerWhoLeftBeforeTheDelay() {
        moveTo("BSP-Spawn");
        feature.onPluginMessage(fromClient(REQUEST));
        moveTo("BSP-Frostwarf");
        feature.onDisconnect(new DisconnectEvent(steve, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));

        runLater();

        assertEquals(1, idsSent().size());
    }

    @Test
    void afterRejoiningTheClientMustAskAgainBeforeGettingResends() {
        moveTo("BSP-Spawn");
        feature.onPluginMessage(fromClient(REQUEST));
        feature.onDisconnect(new DisconnectEvent(steve, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
        currentServer.set(null);

        moveTo("BSP-Frostwarf");
        runLater();

        assertEquals(1, idsSent().size());
    }

    @Test
    void aConfiguredIdIsUsedForThatServer() {
        moveTo("BSP-SafeRoom");

        feature.onPluginMessage(fromClient(REQUEST));

        assertEquals(List.of("kept-saferoom-id"), idsSent());
    }

    @Test
    void otherChannelsAreLeftAlone() {
        moveTo("BSP-Spawn");
        PluginMessageEvent other = new PluginMessageEvent(steve, connection("BSP-Spawn"),
                MinecraftChannelIdentifier.from("bsp_core:proxy"), new byte[] {1, 2});

        feature.onPluginMessage(other);

        assertTrue(other.getResult().isAllowed());
        assertTrue(idsSent().isEmpty());
    }

    @Test
    void aRequestBeforeAnyServerIsIgnoredQuietly() {
        PluginMessageEvent request = new PluginMessageEvent(steve, mock(ServerConnection.class), WorldIdFeature.IDENTIFIER, REQUEST);

        feature.onPluginMessage(request);

        assertFalse(request.getResult().isAllowed());
        assertTrue(idsSent().isEmpty());
    }

    @Test
    void debugLogsDirectionPlayerServerHexAndId() {
        feature = feature(true);
        moveTo("BSP-Spawn");

        feature.onPluginMessage(fromClient(REQUEST));
        feature.onPluginMessage(fromBackend("BSP-Spawn", WorldIdPacket.encode("abc")));

        verify(logger).info("[journeyMap] {} {} on {}: {} = {}", "client -> proxy", "Steve", "BSP-Spawn", "00 00 2a 00", "(request, no id)");
        verify(logger).info("[journeyMap] {} {} on {}: {} = {}", "backend -> client (dropped)", "Steve", "BSP-Spawn",
                "00 00 2a 03 61 62 63", "abc");
        String id = WorldIds.derived("BSP-Spawn");
        verify(logger).info("[journeyMap] proxy -> client ({}) {} on {}: {} = {}", "reply", "Steve", "BSP-Spawn",
                WorldIdPacket.hex(WorldIdPacket.encode(id)), id);
    }

    @Test
    void withoutDebugNothingIsLogged() {
        moveTo("BSP-Spawn");
        feature.onPluginMessage(fromClient(REQUEST));
        feature.onChannelRegister(new PlayerChannelRegisterEvent(steve, List.of(WorldIdFeature.IDENTIFIER)));

        verify(logger, never()).info(anyString(), any(Object[].class));
        verify(logger, never()).info(anyString(), any(Object.class), any(Object.class));
    }

    @Test
    void channelDebugListsTheChannelsAClientRegisters() {
        feature = feature(true);

        feature.onChannelRegister(new PlayerChannelRegisterEvent(steve, List.of(
                MinecraftChannelIdentifier.from("worldinfo:world_id"), MinecraftChannelIdentifier.from("journeymap:handshake"))));

        verify(logger, times(1)).info("[journeyMap] client {} registers channels: {}", "Steve",
                List.of("journeymap:handshake", "worldinfo:world_id"));
    }
}
