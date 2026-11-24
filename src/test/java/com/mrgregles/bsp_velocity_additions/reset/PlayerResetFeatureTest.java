package com.mrgregles.bsp_velocity_additions.reset;

import com.mrgregles.bsp_velocity_additions.config.PluginConfig;
import com.mrgregles.bsp_velocity_additions.lastserver.JsonLastServerStore;
import com.velocitypowered.api.proxy.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PR21: a {@code player_reset} notice makes the proxy forget the player's last server, and nothing else. */
class PlayerResetFeatureTest {

    private final UUID steve = UUID.fromString("aff80d73-fa58-464d-b419-11187ffd8cd3");
    private final UUID alex = UUID.fromString("376f7d5b-6510-46e9-8bbf-936377461446");
    private final Set<UUID> online = new HashSet<>();
    private final Player alexOnline = mock(Player.class);

    private Optional<Player> session(UUID id) {
        return online.contains(id) ? Optional.of(alexOnline) : Optional.empty();
    }

    /** A reset made a minute from now, so every entry written by the test is older. */
    private String reset(UUID player) {
        return "player_reset:BSP-Spawn:" + player + "|" + (System.currentTimeMillis() + 60_000L) + "|Mrgregles|Steve";
    }

    @Test
    void aResetForgetsOnlyThatPlayersLastServerAndIsSavedToTheFile(@TempDir Path dir) {
        Path file = dir.resolve(JsonLastServerStore.FILE_NAME);
        JsonLastServerStore store = new JsonLastServerStore(file, Runnable::run, mock(Logger.class));
        store.put(steve, "Steve", "BSP-Wild");
        store.put(alex, "Alex", "BSP-Spawn");
        PlayerResetFeature feature = new PlayerResetFeature((p, ms, on) -> store.remove(p, ms), this::session, mock(Logger.class));

        assertTrue(feature.onRedisMessage(reset(steve)));

        JsonLastServerStore reloaded = new JsonLastServerStore(file, Runnable::run, mock(Logger.class));
        assertEquals(Optional.empty(), reloaded.get(steve));
        assertEquals(Optional.of("BSP-Spawn"), reloaded.get(alex));
    }

    @Test
    void theSameResetTwiceIsHarmless() {
        List<UUID> forgotten = new ArrayList<>();
        PlayerResetFeature feature = new PlayerResetFeature((p, ms, on) -> {
            boolean first = !forgotten.contains(p);
            forgotten.add(p);
            return first ? Optional.of("BSP-Wild") : Optional.empty();
        }, this::session, mock(Logger.class));

        assertTrue(feature.onRedisMessage(reset(steve)));
        assertTrue(feature.onRedisMessage(reset(steve)));

        assertEquals(List.of(steve, steve), forgotten);
    }

    @Test
    void otherMessagesAndMalformedOnesTouchNothing() {
        List<UUID> forgotten = new ArrayList<>();
        PlayerResetFeature feature = new PlayerResetFeature((p, ms, on) -> {
            forgotten.add(p);
            return Optional.empty();
        }, this::session, mock(Logger.class));

        assertFalse(feature.onRedisMessage("shop:BSP-Spawn:-diamond"));
        assertFalse(feature.onRedisMessage("player_reset:BSP-Spawn:garbage"));
        assertFalse(feature.onRedisMessage(null));

        assertTrue(forgotten.isEmpty());
    }

    @Test
    void tellsTheLastServerFeatureWhetherThePlayerIsOnline() {
        List<Optional<Player>> seen = new ArrayList<>();
        List<Long> resetTimes = new ArrayList<>();
        PlayerResetFeature feature = new PlayerResetFeature((p, ms, on) -> {
            seen.add(on);
            resetTimes.add(ms);
            return Optional.empty();
        }, this::session, mock(Logger.class));
        online.add(alex);

        feature.onRedisMessage(reset(steve));
        feature.onRedisMessage("player_reset:BSP-Spawn:" + alex + "|1764000000000|Mrgregles|Alex");

        assertEquals(List.of(Optional.empty(), Optional.of(alexOnline)), seen, "the online session is passed on");
        assertEquals(1764000000000L, resetTimes.get(1), "the reset time is passed on");
    }

    @Test
    void theDiscordLinksFileIsNeverTouched(@TempDir Path dir) throws Exception {
        Path links = dir.resolve("discord-links.json");
        Files.writeString(links, "{\"" + steve + "\": {\"discordId\": 1}}");
        JsonLastServerStore store = new JsonLastServerStore(dir.resolve(JsonLastServerStore.FILE_NAME), Runnable::run,
                mock(Logger.class));
        store.put(steve, "Steve", "BSP-Wild");
        PlayerResetFeature feature = new PlayerResetFeature((p, ms, on) -> {
            store.remove(p, ms);
            return Optional.empty();
        }, this::session, mock(Logger.class));

        feature.onRedisMessage(reset(steve));

        assertEquals("{\"" + steve + "\": {\"discordId\": 1}}", Files.readString(links));
    }

    /** Audit 5: a player_reset notice that cannot be read is logged once as a warning; other kinds are not. */
    @Test
    void anUnreadableResetNoticeIsWarnedAbout() {
        Logger logger = mock(Logger.class);
        PlayerResetFeature feature = new PlayerResetFeature((p, ms, on) -> Optional.empty(), this::session, logger);

        feature.onRedisMessage("shop:BSP-Spawn:-diamond");
        verify(logger, never()).warn(anyString(), (Object) argThat(x -> true));

        feature.onRedisMessage("player_reset:BSP-Spawn:garbage\nINFO fake");
        verify(logger).warn(eq("Player reset: ignored a player_reset notice that could not be read: {}"),
                (Object) eq("player_reset:BSP-Spawn:garbage?INFO fake"));
    }

    @Test
    void doesNotStartWhenSwitchedOffOrWithoutTheLastServerFeature() {
        PluginConfig.PlayerReset off = new PluginConfig.PlayerReset(false, "127.0.0.1", 6379, "", "bsp_core");
        assertTrue(PlayerResetFeature.start(off, (p, ms, on) -> Optional.empty(), u -> Optional.empty(), k -> null,
                mock(Logger.class)).isEmpty());
        assertTrue(PlayerResetFeature.start(PluginConfig.PlayerReset.DEFAULTS, null, u -> Optional.empty(), k -> null,
                mock(Logger.class)).isEmpty());
    }

    @Test
    void textFromRedisIsMadeSafeForTheLog() {
        assertEquals("Steve??INFO fake", PlayerResetFeature.clean("Steve\r\nINFO fake"));
        assertEquals(67, PlayerResetFeature.clean("x".repeat(200)).length());
    }

    /**
     * End to end against a Redis stand-in on 127.0.0.1: the feature logs in with its own user and
     * {@code BSP_PLAYER_RESET_REDIS_PASSWORD}, subscribes to the configured channel, and a notice on it forgets.
     */
    @Test
    void listensOnBspCoresChannelWithItsOwnPassword() throws Exception {
        List<List<String>> commands = new CopyOnWriteArrayList<>();
        String notice = reset(steve);
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread t = new Thread(() -> {
                try (Socket s = server.accept()) {
                    InputStream in = new BufferedInputStream(s.getInputStream());
                    OutputStream out = s.getOutputStream();
                    while (true) {
                        List<String> cmd = readCommand(in);
                        commands.add(cmd);
                        String reply = cmd.get(0).equals("SUBSCRIBE")
                                ? "*3\r\n$9\r\nsubscribe\r\n" + bulk(cmd.get(1)) + ":1\r\n"
                                + "*3\r\n$7\r\nmessage\r\n" + bulk(cmd.get(1)) + bulk("credit:BSP-Wild:x")
                                + "*3\r\n$7\r\nmessage\r\n" + bulk(cmd.get(1)) + bulk(notice)
                                : "+OK\r\n";
                        out.write(reply.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                } catch (IOException closed) {
                    // the client went away
                }
            });
            t.setDaemon(true);
            t.start();

            CountDownLatch forgot = new CountDownLatch(1);
            List<UUID> forgotten = new CopyOnWriteArrayList<>();
            PluginConfig.PlayerReset settings = new PluginConfig.PlayerReset(true, "127.0.0.1", server.getLocalPort(),
                    "proxy", "bsp_core");
            PlayerResetFeature feature = PlayerResetFeature.start(settings, (p, ms, on) -> {
                forgotten.add(p);
                forgot.countDown();
                return Optional.of("BSP-Wild");
            }, u -> Optional.empty(), k -> switch (k) {
                case PlayerResetFeature.PASSWORD_VARIABLE -> "reset-pw";
                case PlayerResetFeature.FALLBACK_PASSWORD_VARIABLE -> "herald-pw";
                default -> null;
            }, mock(Logger.class)).orElseThrow();
            try {
                assertTrue(forgot.await(10, TimeUnit.SECONDS), "the notice reached the feature");
            } finally {
                feature.stop();
            }
            assertEquals(List.of(steve), forgotten, "the credit message was ignored");
            assertEquals(List.of("AUTH", "proxy", "reset-pw"), commands.get(0));
            assertEquals(List.of("SUBSCRIBE", "bsp_core"), commands.get(1));
        }
    }

    /** Audit 4: Redis refusing the subscription gives one error naming the [playerReset] user setting. */
    @Test
    void aRefusedSubscriptionNamesThePlayerResetUserSetting() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread t = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket s = server.accept();
                        InputStream in = new BufferedInputStream(s.getInputStream());
                        OutputStream out = s.getOutputStream();
                        while (true) {
                            List<String> cmd = readCommand(in);
                            String reply = cmd.get(0).equals("SUBSCRIBE")
                                    ? "-NOPERM User herald has no permissions to access the 'bsp_core' channel\r\n"
                                    : "+OK\r\n";
                            out.write(reply.getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                    } catch (IOException closed) {
                        // next connection, or the test is over
                    }
                }
            });
            t.setDaemon(true);
            t.start();
            Logger logger = mock(Logger.class);
            PluginConfig.PlayerReset settings = new PluginConfig.PlayerReset(true, "127.0.0.1", server.getLocalPort(),
                    "herald", "bsp_core");
            PlayerResetFeature feature = PlayerResetFeature.start(settings, (p, ms, on) -> Optional.empty(),
                    u -> Optional.empty(), k -> "pw", logger).orElseThrow();
            try {
                verify(logger, timeout(5_000)).error(argThat((String line) -> line.contains("NOPERM")
                        && line.contains("[playerReset] redisUser (now herald)")
                        && line.contains("BSP_PLAYER_RESET_REDIS_PASSWORD")
                        && line.contains("subscribe to bsp_core")
                        && !line.contains("pw;")));
            } finally {
                feature.stop();
            }
        }
    }

    private static String bulk(String text) {
        return "$" + text.getBytes(StandardCharsets.UTF_8).length + "\r\n" + text + "\r\n";
    }

    /** One RESP array of bulk strings, as the client sends commands. */
    private static List<String> readCommand(InputStream in) throws IOException {
        int count = Integer.parseInt(line(in).substring(1));
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int length = Integer.parseInt(line(in).substring(1));
            parts.add(new String(in.readNBytes(length), StandardCharsets.UTF_8));
            line(in);
        }
        return parts;
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        int c;
        while ((c = in.read()) != '\n') {
            if (c < 0) {
                throw new IOException("closed");
            }
            if (c != '\r') {
                b.append((char) c);
            }
        }
        return b.toString();
    }
}
