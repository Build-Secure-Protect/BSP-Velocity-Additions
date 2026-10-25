package com.mrgregles.bsp_velocity_additions.discord;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RespRedisTest {

    private ServerSocket server;
    private final List<List<Object>> received = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    /** A Redis stand-in on 127.0.0.1: each command is recorded and answered by {@code answer(connection number, command)}. */
    private int serve(BiFunction<Integer, List<Object>, String> answer) throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            int n = 0;
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    int connection = n++;
                    Thread c = new Thread(() -> {
                        try (s) {
                            InputStream in = new BufferedInputStream(s.getInputStream());
                            OutputStream out = s.getOutputStream();
                            while (true) {
                                @SuppressWarnings("unchecked")
                                List<Object> command = (List<Object>) RespRedis.read(in);
                                received.add(command);
                                String reply = answer.apply(connection, command);
                                if (reply == null) {
                                    return;
                                }
                                out.write(reply.getBytes(StandardCharsets.UTF_8));
                                out.flush();
                            }
                        } catch (IOException closed) {
                            // the client went away
                        }
                    });
                    c.setDaemon(true);
                    c.start();
                } catch (IOException closed) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return server.getLocalPort();
    }

    @Test
    void writesCommandsAsArraysOfBulkStrings() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RespRedis.write(out, "SET", "k", "é");
        assertEquals("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$2\r\né\r\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void readsEveryReplyType() throws IOException {
        assertEquals("OK", read("+OK\r\n"));
        assertEquals(3L, read(":3\r\n"));
        assertEquals("a\r\nb", read("$4\r\na\r\nb\r\n"));
        assertNull(read("$-1\r\n"));
        assertEquals(List.of("message", "ch", "hi"), read("*3\r\n$7\r\nmessage\r\n$2\r\nch\r\n$2\r\nhi\r\n"));
    }

    @Test
    void anErrorReplyKeepsOnlyItsFirstWord() {
        IOException e = assertThrows(IOException.class, () -> read("-WRONGPASS invalid username-password pair secret\r\n"));
        assertEquals("WRONGPASS", e.getMessage());
    }

    private static Object read(String bytes) throws IOException {
        return RespRedis.read(new ByteArrayInputStream(bytes.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void authenticatesWithUserAndPasswordThenRunsTheCommands() throws Exception {
        int port = serve((c, cmd) -> switch (String.valueOf(cmd.get(0))) {
            case "AUTH", "SET" -> "+OK\r\n";
            case "GETDEL" -> cmd.get(1).equals("bsp_core:discord:code:K7QX2M") ? "$5\r\nvalue\r\n" : "$-1\r\n";
            case "DEL" -> ":1\r\n";
            default -> "-ERR unknown\r\n";
        });
        try (RespRedis redis = new RespRedis("127.0.0.1", port, "herald", "pw", mock(Logger.class))) {
            assertEquals(Optional.of("value"), redis.getDel("bsp_core:discord:code:K7QX2M"));
            assertEquals(Optional.empty(), redis.getDel("bsp_core:discord:code:NOPE22"));
            redis.set("bsp_core:discord:linked:x", "1");
            redis.setExpiring("bsp_core:discord:undelivered:x", "1", 604800L);
            redis.delete("bsp_core:discord:linked:x");
        }

        assertEquals(List.of("AUTH", "herald", "pw"), received.get(0));
        assertEquals(List.of("SET", "bsp_core:discord:undelivered:x", "1", "EX", "604800"), received.get(4));
        assertEquals(List.of("DEL", "bsp_core:discord:linked:x"), received.get(5));
    }

    @Test
    void withoutAPasswordNoAuthIsSent() throws Exception {
        int port = serve((c, cmd) -> "+OK\r\n");
        try (RespRedis redis = new RespRedis("127.0.0.1", port, "", "", mock(Logger.class))) {
            redis.set("k", "v");
        }
        assertEquals(List.of(List.of("SET", "k", "v")), received);
    }

    @Test
    void aRefusedCommandIsAnIOExceptionAndARefusedPortToo() throws Exception {
        int port = serve((c, cmd) -> "-NOPERM this user has no permissions\r\n");
        try (RespRedis redis = new RespRedis("127.0.0.1", port, "", "", mock(Logger.class))) {
            assertEquals("NOPERM", assertThrows(IOException.class, () -> redis.set("k", "v")).getMessage());
        }
        server.close();
        try (RespRedis redis = new RespRedis("127.0.0.1", port, "", "", mock(Logger.class))) {
            assertThrows(IOException.class, () -> redis.delete("k"));
        }
    }

    @Test
    void subscribesTellsWhenReadyAndPassesMessagesOnAndComesBackAfterADrop() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        List<String> messages = new CopyOnWriteArrayList<>();
        CountDownLatch gotTwo = new CountDownLatch(2);
        int port = serve((connection, cmd) -> {
            if (!cmd.get(0).equals("SUBSCRIBE")) {
                return "+OK\r\n";
            }
            String text = connection == 0 ? "first" : "second";
            String reply = "*3\r\n$9\r\nsubscribe\r\n$16\r\nbsp_core:discord\r\n:1\r\n"
                    + "*3\r\n$7\r\nmessage\r\n$16\r\nbsp_core:discord\r\n$" + text.length() + "\r\n" + text + "\r\n";
            return connection == 0 ? reply + "-ERR drop\r\n" : reply;
        });
        try (RespRedis redis = new RespRedis("127.0.0.1", port, "", "pw", mock(Logger.class))) {
            redis.subscribe("bsp_core:discord", m -> {
                messages.add(m);
                gotTwo.countDown();
            }, ready::countDown);

            assertTrue(gotTwo.await(10, TimeUnit.SECONDS), "a message before and after the reconnect");
            assertTrue(ready.await(1, TimeUnit.SECONDS), "ready again after the reconnect");
        }
        assertEquals(List.of("first", "second"), messages);
        assertTrue(received.contains(List.of("AUTH", "pw")));
        assertFalse(received.isEmpty());
    }
}
