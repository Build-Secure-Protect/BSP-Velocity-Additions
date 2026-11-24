package com.mrgregles.bsp_velocity_additions.reset;

import com.mrgregles.bsp_velocity_additions.config.Messages;
import com.mrgregles.bsp_velocity_additions.lastserver.JsonLastServerStore;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D45: a {@code season_reset} notice disconnects every player on the network with the season message and forgets
 * every last server; the same notice twice does nothing.
 */
class SeasonResetFeatureTest {

    private static final String NO_NUMBER =
            "A new season has begun! Everyone starts over. Log in again to get your fresh Shatter Totem.";

    private final UUID steve = UUID.fromString("aff80d73-fa58-464d-b419-11187ffd8cd3");
    private final UUID alex = UUID.fromString("376f7d5b-6510-46e9-8bbf-936377461446");
    private final List<Player> online = new CopyOnWriteArrayList<>();
    private final List<String> events = new CopyOnWriteArrayList<>();

    private Collection<Player> players() {
        return online;
    }

    private Player player(String name) {
        Player p = mock(Player.class);
        when(p.getUsername()).thenReturn(name);
        doAnswer(i -> events.add("disconnect " + name)).when(p).disconnect(any());
        online.add(p);
        return p;
    }

    private SeasonResetFeature feature(SeasonResetFeature.AllForgetter forgetter, Path file, Logger logger) {
        return new SeasonResetFeature(forgetter, this::players, Messages.defaults(), file, () -> 5_000L, logger);
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static String reason(Player p) {
        ArgumentCaptor<Component> c = ArgumentCaptor.forClass(Component.class);
        verify(p).disconnect(c.capture());
        return plain(c.getValue());
    }

    @Test
    void disconnectsEveryPlayerAndForgetsEveryLastServerInTheFile(@TempDir Path dir) {
        Path file = dir.resolve(JsonLastServerStore.FILE_NAME);
        JsonLastServerStore store = new JsonLastServerStore(file, Runnable::run, mock(Logger.class));
        store.put(steve, "Steve", "BSP-Wild");
        store.put(alex, "Alex", "BSP-Spawn");
        Player p1 = player("Steve");
        Player p2 = player("Alex");
        SeasonResetFeature reset = new SeasonResetFeature((ms, on) -> store.removeAll(ms), this::players, Messages.defaults(),
                dir.resolve(SeasonResetFeature.FILE_NAME), () -> System.currentTimeMillis() + 60_000L, mock(Logger.class));

        assertTrue(reset.onRedisMessage("season_reset:BSP-Spawn:3|12"));

        assertEquals("Season 12 has begun! Everyone starts over. Log in again to get your fresh Shatter Totem.", reason(p1));
        assertEquals("Season 12 has begun! Everyone starts over. Log in again to get your fresh Shatter Totem.", reason(p2));
        JsonLastServerStore reloaded = new JsonLastServerStore(file, Runnable::run, mock(Logger.class));
        assertEquals(0, reloaded.size(), "everyone joins the fallback server next");
    }

    /** VA-C4: the owner's wording with the starting season, from BSP-Core's payload {@code <resetEpoch>|<season>}. */
    @Test
    void usesTheSeasonNumberBspCoreSends() {
        Player p = player("Steve");
        Player q = player("Alex");

        assertTrue(feature((ms, on) -> 0, null, mock(Logger.class)).onRedisMessage("season_reset:BSP-Spawn:3|12"));

        assertEquals("Season 12 has begun! Everyone starts over. Log in again to get your fresh Shatter Totem.", reason(p));
        assertEquals("Season 12 has begun! Everyone starts over. Log in again to get your fresh Shatter Totem.", reason(q));
    }

    /** VA-C4: the repeat check reads the reset number, not the season number after the {@code |}. */
    @Test
    void theRepeatCheckUsesTheResetNumberNotTheSeason() {
        Player p = player("Steve");
        SeasonResetFeature reset = feature((ms, on) -> 0, null, mock(Logger.class));

        assertTrue(reset.onRedisMessage("season_reset:BSP-Spawn:3|12"));
        assertFalse(reset.onRedisMessage("season_reset:BSP-Spawn:3|12"), "the same notice twice");
        assertFalse(reset.onRedisMessage("season_reset:BSP-Wild:2|13"), "a higher season with an older reset number");

        verify(p, times(1)).disconnect(any());
        assertEquals(3, reset.lastApplied());
    }

    /** A BSP-Core from before VA-C4: the wording without a number. */
    @Test
    void anOlderNoticeWithoutTheSeasonUsesTheWordingWithoutANumber() {
        Player p = player("Steve");

        assertTrue(feature((ms, on) -> 0, null, mock(Logger.class)).onRedisMessage("season_reset:BSP-Spawn:3"));

        assertEquals(NO_NUMBER, reason(p));
    }

    /** Same words as BSP-Core's own kick ({@code message.bsp_core.season_reset.kick}) and its gold colour. */
    @Test
    void theBundledLinesMatchBspCoresKick() {
        SeasonResetFeature reset = feature((ms, on) -> 0, null, mock(Logger.class));
        Component numbered = reset.reason(SeasonResetMessage.parse("season_reset:x:1|2").orElseThrow());
        Component plainOne = reset.reason(SeasonResetMessage.parse("season_reset:x:1").orElseThrow());

        assertEquals("Season 2 has begun! Everyone starts over. Log in again to get your fresh Shatter Totem.", plain(numbered));
        assertEquals(NO_NUMBER, plain(plainOne));
        assertEquals(net.kyori.adventure.text.format.NamedTextColor.GOLD, plainOne.color());
    }

    @Test
    void lastServersAreForgottenBeforeAnyoneIsDisconnected() {
        player("Steve");
        player("Alex");
        List<Long> times = new ArrayList<>();
        SeasonResetFeature reset = feature((ms, on) -> {
            events.add("forget");
            times.add(ms);
            return 2;
        }, null, mock(Logger.class));

        reset.onRedisMessage("season_reset:BSP-Spawn:3");

        assertEquals(List.of("forget", "disconnect Steve", "disconnect Alex"), events,
                "a player who logs straight back in already lands on the fallback server");
        assertEquals(List.of(5_000L), times, "everything last changed before the proxy heard the reset");
    }

    /** D45 audit: an older number is a warning naming season-reset.json; the same number is only an info line. */
    @Test
    void anOlderResetNumberIsWarnedAboutAndTheSameOneIsNot(@TempDir Path dir) {
        Logger logger = mock(Logger.class);
        SeasonResetFeature reset = feature((ms, on) -> 0, dir.resolve(SeasonResetFeature.FILE_NAME), logger);
        reset.onRedisMessage("season_reset:BSP-Spawn:3|12");

        reset.onRedisMessage("season_reset:BSP-Spawn:3|12");
        verify(logger, never()).warn(anyString(), any(Object[].class));

        reset.onRedisMessage("season_reset:BSP-Spawn:2|11");
        verify(logger).warn(argThat((String line) -> line.contains("delete {}")), any(Object[].class));
    }

    /** D45 audit: the online sessions are handed to the forget, taken before anyone is disconnected. */
    @Test
    void theOnlineSessionsAreHandedToTheForget() {
        Player p1 = player("Steve");
        Player p2 = player("Alex");
        List<Collection<Player>> seen = new ArrayList<>();

        feature((ms, on) -> {
            seen.add(List.copyOf(on));
            return 0;
        }, null, mock(Logger.class)).onRedisMessage("season_reset:BSP-Spawn:3|12");

        assertEquals(List.of(List.of(p1, p2)), seen);
    }

    @Test
    void theSameNoticeTwiceDoesNothingTheSecondTime() {
        Player p = player("Steve");
        List<Long> forgets = new ArrayList<>();
        SeasonResetFeature reset = feature((ms, on) -> {
            forgets.add(ms);
            return 1;
        }, null, mock(Logger.class));

        assertTrue(reset.onRedisMessage("season_reset:BSP-Spawn:3"));
        assertFalse(reset.onRedisMessage("season_reset:BSP-Spawn:3"));
        assertFalse(reset.onRedisMessage("season_reset:BSP-Wild:2"), "an older reset does nothing either");

        verify(p, times(1)).disconnect(any());
        assertEquals(1, forgets.size());
        assertEquals(3, reset.lastApplied());
    }

    @Test
    void aNewerResetIsAppliedAgain() {
        Player p = player("Steve");
        SeasonResetFeature reset = feature((ms, on) -> 0, null, mock(Logger.class));

        assertTrue(reset.onRedisMessage("season_reset:BSP-Spawn:3"));
        assertTrue(reset.onRedisMessage("season_reset:BSP-Spawn:4"));

        verify(p, times(2)).disconnect(any());
        assertEquals(4, reset.lastApplied());
    }

    @Test
    void theAppliedResetIsRememberedAcrossARestart(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(SeasonResetFeature.FILE_NAME);
        assertTrue(feature((ms, on) -> 0, file, mock(Logger.class)).onRedisMessage("season_reset:BSP-Spawn:3"));
        assertTrue(Files.readString(file).contains("\"lastResetNumber\": 3"), Files.readString(file));

        Player p = player("Steve");
        SeasonResetFeature afterRestart = feature((ms, on) -> 0, file, mock(Logger.class));

        assertEquals(3, afterRestart.lastApplied());
        assertFalse(afterRestart.onRedisMessage("season_reset:BSP-Spawn:3"));
        verify(p, never()).disconnect(any());
    }

    @Test
    void anUnreadableRecordIsLoggedAndTheNextNoticeApplied(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(SeasonResetFeature.FILE_NAME);
        Files.writeString(file, "{not json");
        Logger logger = mock(Logger.class);
        Player p = player("Steve");

        SeasonResetFeature reset = feature((ms, on) -> 0, file, logger);

        verify(logger).error(anyString(), any(), any());
        assertTrue(reset.onRedisMessage("season_reset:BSP-Spawn:3"));
        verify(p).disconnect(any());
        assertEquals(3, feature((ms, on) -> 0, file, mock(Logger.class)).lastApplied(), "the file is written fresh");
    }

    /** With {@code lastServer.enabled = false} there is nothing to forget, but everyone is still disconnected. */
    @Test
    void withoutTheLastServerFeaturePlayersAreStillDisconnected() {
        Player p = player("Steve");

        assertTrue(feature(null, null, mock(Logger.class)).onRedisMessage("season_reset:BSP-Spawn:3"));

        verify(p).disconnect(any());
    }

    @Test
    void oneFailedDisconnectDoesNotStopTheOthers() {
        Player bad = player("Broken");
        doThrow(new IllegalStateException("gone")).when(bad).disconnect(any());
        Player good = player("Alex");

        assertTrue(feature((ms, on) -> 0, null, mock(Logger.class)).onRedisMessage("season_reset:BSP-Spawn:3"));

        verify(good).disconnect(any());
    }

    @Test
    void otherMessagesAndMalformedOnesTouchNothing() {
        Player p = player("Steve");
        List<Long> forgets = new ArrayList<>();
        Logger logger = mock(Logger.class);
        SeasonResetFeature reset = feature((ms, on) -> {
            forgets.add(ms);
            return 0;
        }, null, logger);

        assertFalse(reset.onRedisMessage("market:BSP-Spawn:"));
        assertFalse(reset.onRedisMessage("player_reset:BSP-Spawn:" + steve + "|1|a|b"));
        assertFalse(reset.onRedisMessage(null));
        org.mockito.Mockito.verifyNoInteractions(logger);

        assertFalse(reset.onRedisMessage("season_reset:BSP-Spawn:soon\nINFO fake"));
        verify(logger).warn(eq("Season reset: ignored a season_reset notice that could not be read: {}"),
                (Object) eq("season_reset:BSP-Spawn:soon?INFO fake"));

        verify(p, never()).disconnect(any());
        assertTrue(forgets.isEmpty());
        assertEquals(0, reset.lastApplied());
    }

    @Test
    void theDiscordLinksFileIsNeverTouched(@TempDir Path dir) throws Exception {
        Path links = dir.resolve("discord-links.json");
        Files.writeString(links, "{\"" + steve + "\": {\"discordId\": 1}}");
        JsonLastServerStore store = new JsonLastServerStore(dir.resolve(JsonLastServerStore.FILE_NAME), Runnable::run,
                mock(Logger.class));
        store.put(steve, "Steve", "BSP-Wild");

        feature((ms, on) -> store.removeAll(ms), dir.resolve(SeasonResetFeature.FILE_NAME), mock(Logger.class))
                .onRedisMessage("season_reset:BSP-Spawn:3");

        assertEquals("{\"" + steve + "\": {\"discordId\": 1}}", Files.readString(links));
    }
}
