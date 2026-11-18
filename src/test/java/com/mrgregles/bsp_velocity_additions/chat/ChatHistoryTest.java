package com.mrgregles.bsp_velocity_additions.chat;

import com.mrgregles.bsp_velocity_additions.config.Messages;
import com.mrgregles.bsp_velocity_additions.config.PluginConfig;
import com.mrgregles.bsp_velocity_additions.joinleave.JoinLeaveFeature;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T25 (D37): a player joining the network gets the recent public chat of every server. */
class ChatHistoryTest {

    /** A clock turned by hand. */
    static final class HandClock extends Clock {
        Instant now = Instant.parse("2026-11-18T20:00:00Z");

        void forward(Duration d) {
            now = now.plus(d);
        }

        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    private final HandClock clock = new HandClock();
    private final ProxyServer proxy = mock(ProxyServer.class);
    private final Logger logger = mock(Logger.class);
    private final Player steve = player("Steve", "BSP-Wild");
    private final Player alex = player("Alex", "BSP-Spawn");   // joins the network
    private final Player dani = player("Dani", "BSP-Spawn");   // staff, joins the network
    private ChatHistory history;
    private ChatRelayFeature feature;

    @BeforeEach
    void setUp() {
        when(proxy.getAllPlayers()).thenReturn(List.of(steve));
        when(dani.hasPermission("bspva.players.servers")).thenReturn(true);
        history = new ChatHistory(50, Duration.ofMinutes(30), clock);
        feature = feature(Messages.defaults());
    }

    private ChatRelayFeature feature(Messages messages) {
        return new ChatRelayFeature(proxy, new PluginConfig.Chat(true, true, false),
                new PluginConfig.Players(true, "bspva.players.servers"), messages, history, logger);
    }

    private static Player player(String name, String server) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.getUsername()).thenReturn(name);
        ServerConnection c = connection(server);
        when(p.getCurrentServer()).thenReturn(Optional.of(c));
        return p;
    }

    private static ServerConnection connection(String server) {
        ServerConnection c = mock(ServerConnection.class);
        when(c.getServerInfo()).thenReturn(new ServerInfo(server, new InetSocketAddress("127.0.0.1", 25565)));
        return c;
    }

    private void say(String server, String name, String text) {
        String json = "{\"text\":\"<" + name + "> " + text + "\"}";
        feature.onPluginMessage(new PluginMessageEvent(connection(server), steve,
                MinecraftChannelIdentifier.from(ChatRelayFeature.CHANNEL),
                RelayPayloadTest.chat(UUID.randomUUID(), name, text, json, server)));
    }

    private void joinNetwork(Player p) {
        feature.onServerPostConnect(new ServerPostConnectEvent(p, null));
    }

    private static List<String> linesSentTo(Player p) {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(p, org.mockito.Mockito.atLeast(0)).sendMessage(captor.capture());
        return captor.getAllValues().stream().map(c -> PlainTextComponentSerializer.plainText().serialize(c)).toList();
    }

    @Test
    void aPlayerJoiningTheNetworkGetsRecentChatFromEveryServerBetweenTheMarkers() {
        say("BSP-Wild", "Steve", "anyone selling plasma?");
        say("BSP-Spawn", "Nadia", "40 each");   // Alex's own server: he was not there to see it

        joinNetwork(alex);

        assertEquals(List.of("─── Earlier chat ───", "<Steve> anyone selling plasma?", "<Nadia> 40 each", "─── Now ───"),
                linesSentTo(alex));
    }

    @Test
    void staffSeeTheServerTagOnOldLinesAsOnLiveOnes() {
        say("BSP-Wild", "Steve", "hi");

        joinNetwork(dani);

        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(dani, org.mockito.Mockito.times(3)).sendMessage(sent.capture());
        ServerTagTest.assertIsTheServerTag(sent.getAllValues().get(1).children().get(0), "BSP-Wild");
        assertTrue(linesSentTo(dani).get(1).endsWith("<Steve> hi"));
    }

    @Test
    void aServerSwitchSendsNothing() {
        say("BSP-Wild", "Steve", "hi");

        feature.onServerPostConnect(new ServerPostConnectEvent(alex, mock(RegisteredServer.class)));

        verify(alex, never()).sendMessage(any(Component.class));
    }

    @Test
    void withNoRecentChatNothingIsSentNotEvenTheMarkers() {
        joinNetwork(alex);
        say("BSP-Wild", "Steve", "hi");
        clock.forward(Duration.ofMinutes(31));

        joinNetwork(dani);

        verify(alex, never()).sendMessage(any(Component.class));
        verify(dani, never()).sendMessage(any(Component.class));
    }

    @Test
    void onlyTheLastFiftyLinesAndNoneOlderThanThirtyMinutes() {
        for (int i = 1; i <= 60; i++) {
            say("BSP-Wild", "Steve", "line " + i);
        }
        assertEquals(50, history.recent().size());
        assertEquals("line 11", history.recent().get(0).payload().text());

        clock.forward(Duration.ofMinutes(20));
        say("BSP-Wild", "Steve", "late");
        clock.forward(Duration.ofMinutes(11)); // the first 50 are now 31 minutes old

        assertEquals(List.of("late"), history.recent().stream().map(l -> l.payload().text()).toList());
    }

    @Test
    void switchedOffNothingIsKept() {
        history = ChatHistory.of(new PluginConfig.ChatHistory(false, 50, 30), clock);
        feature = feature(Messages.defaults());
        say("BSP-Wild", "Steve", "hi");

        joinNetwork(alex);

        assertTrue(history.recent().isEmpty());
        verify(alex, never()).sendMessage(any(Component.class));
    }

    @Test
    void anEmptyHeaderOrFooterIsLeftOut(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(Messages.FILE_NAME), "[chatHistory]\nheader = \"\"\nfooter = \"\"\n");
        feature = feature(Messages.load(dir, logger));
        say("BSP-Wild", "Steve", "hi");

        joinNetwork(alex);

        assertEquals(List.of("<Steve> hi"), linesSentTo(alex));
    }

    /** The history must reach the player before the network join line, which JoinLeaveFeature sends at NORMAL. */
    @Test
    void theHistoryIsSentEarlierThanTheJoinLine() throws Exception {
        Subscribe history = ChatRelayFeature.class.getMethod("onServerPostConnect", ServerPostConnectEvent.class)
                .getAnnotation(Subscribe.class);
        Subscribe join = JoinLeaveFeature.class.getMethod("onServerPostConnect", ServerPostConnectEvent.class)
                .getAnnotation(Subscribe.class);

        assertEquals(PostOrder.EARLY, history.order());
        assertEquals(PostOrder.NORMAL, join.order());
    }
}
