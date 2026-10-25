package com.mrgregles.bsp_velocity_additions.discord;

import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A small Redis client of the plugin's own, like each BSP mod's: RESP over two sockets, one for commands
 * ({@code GETDEL}, {@code SET}, {@code DEL}) and one that stays subscribed to {@code bsp_core:discord}. Both reconnect
 * after a drop; the subscriber waits 1 s, doubling to 30 s, and pings an idle connection so a dead one is noticed.
 * The password is passed in (from {@code BSP_REDIS_PASSWORD}) and never logged; Redis error replies are logged by
 * their first word only ({@code WRONGPASS}, {@code NOPERM}...). {@code GETDEL} needs Redis 6.2 or newer.
 */
public final class RespRedis implements KeyValueStore, AutoCloseable {

    static final int CONNECT_TIMEOUT_MS = 3_000;
    static final int READ_TIMEOUT_MS = 5_000;
    static final int SUBSCRIBER_IDLE_MS = 30_000;
    static final long MAX_BACKOFF_MS = 30_000L;

    /** A {@code -ERR ...} reply. Only the first word is kept, so nothing the server echoes back reaches a log. */
    static final class RedisError extends IOException {
        RedisError(String line) {
            super(firstWord(line));
        }

        private static String firstWord(String line) {
            int space = line.indexOf(' ');
            return space < 0 ? line : line.substring(0, space);
        }
    }

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final Logger logger;

    private Socket commandSocket;
    private InputStream commandIn;
    private OutputStream commandOut;

    private volatile boolean closed;
    private volatile Socket subscriberSocket;
    private Thread subscriber;

    public RespRedis(String host, int port, String user, String password, Logger logger) {
        this.host = host;
        this.port = port;
        this.user = user == null ? "" : user;
        this.password = password == null ? "" : password;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ commands

    @Override
    public Optional<String> getDel(String key) throws IOException {
        Object reply = command("GETDEL", key);
        return reply instanceof String s ? Optional.of(s) : Optional.empty();
    }

    @Override
    public void set(String key, String value) throws IOException {
        command("SET", key, value);
    }

    @Override
    public void setExpiring(String key, String value, long seconds) throws IOException {
        command("SET", key, value, "EX", Long.toString(seconds));
    }

    @Override
    public void delete(String key) throws IOException {
        command("DEL", key);
    }

    /** Sends one command; on a broken connection it reconnects and tries once more. */
    synchronized Object command(String... args) throws IOException {
        if (closed) {
            throw new IOException("closed");
        }
        try {
            return roundTrip(args);
        } catch (RedisError refused) {
            throw refused;
        } catch (IOException broken) {
            closeCommandSocket();
            return roundTrip(args);
        }
    }

    private Object roundTrip(String... args) throws IOException {
        if (commandSocket == null) {
            Socket s = open(READ_TIMEOUT_MS);
            commandSocket = s;
            commandIn = new BufferedInputStream(s.getInputStream());
            commandOut = s.getOutputStream();
            authenticate(commandIn, commandOut);
        }
        write(commandOut, args);
        return read(commandIn);
    }

    private void closeCommandSocket() {
        try {
            if (commandSocket != null) {
                commandSocket.close();
            }
        } catch (IOException ignored) {
            // already gone
        }
        commandSocket = null;
        commandIn = null;
        commandOut = null;
    }

    // ------------------------------------------------------------------ subscription

    /**
     * Starts the subscriber thread. {@code onReady} runs each time the subscription is (re)established;
     * {@code onMessage} gets each message's text. Both run on the subscriber thread and must hand work on quickly.
     */
    public void subscribe(String channel, Consumer<String> onMessage, Runnable onReady) {
        subscriber = new Thread(() -> listen(channel, onMessage, onReady), "BSP Herald Redis");
        subscriber.setDaemon(true);
        subscriber.start();
    }

    private void listen(String channel, Consumer<String> onMessage, Runnable onReady) {
        long backoff = 1_000L;
        boolean down = false;
        while (!closed) {
            try (Socket s = open(SUBSCRIBER_IDLE_MS)) {
                subscriberSocket = s;
                InputStream in = new BufferedInputStream(s.getInputStream());
                OutputStream out = s.getOutputStream();
                authenticate(in, out);
                write(out, "SUBSCRIBE", channel);
                boolean waitingForPong = false;
                while (!closed) {
                    Object reply;
                    try {
                        reply = read(in);
                    } catch (SocketTimeoutException idle) {
                        if (waitingForPong) {
                            throw new IOException("Redis stopped answering");
                        }
                        write(out, "PING");
                        waitingForPong = true;
                        continue;
                    }
                    waitingForPong = false;
                    if (!(reply instanceof List<?> parts) || parts.isEmpty()) {
                        continue;
                    }
                    String kind = String.valueOf(parts.get(0));
                    if (kind.equals("subscribe")) {
                        backoff = 1_000L;
                        if (down) {
                            logger.info("Herald: Redis answers again at {}:{}", host, port);
                        } else {
                            logger.info("Herald: listening on Redis {}:{} channel {}", host, port, channel);
                        }
                        down = false;
                        safely(onReady);
                    } else if (kind.equals("message") && parts.size() >= 3 && parts.get(2) instanceof String text) {
                        safely(() -> onMessage.accept(text));
                    }
                }
            } catch (IOException e) {
                if (closed) {
                    break;
                }
                if (!down) {
                    logger.warn("Herald: Redis at {}:{} is not answering ({}); retrying", host, port,
                            e instanceof RedisError ? e.getMessage() : e.getClass().getSimpleName());
                    down = true;
                }
            }
            if (closed) {
                break;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
                break;
            }
            backoff = Math.min(MAX_BACKOFF_MS, backoff * 2);
        }
    }

    private void safely(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            logger.warn("Herald: a Redis message could not be handled ({})", e.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        closed = true;
        Socket s = subscriberSocket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // already gone
            }
        }
        synchronized (this) {
            closeCommandSocket();
        }
    }

    // ------------------------------------------------------------------ RESP

    private Socket open(int readTimeout) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(readTimeout);
            s.setTcpNoDelay(true);
            return s;
        } catch (IOException e) {
            s.close();
            throw e;
        }
    }

    private void authenticate(InputStream in, OutputStream out) throws IOException {
        if (password.isEmpty()) {
            return;
        }
        if (user.isEmpty()) {
            write(out, "AUTH", password);
        } else {
            write(out, "AUTH", user, password);
        }
        read(in);
    }

    /** One command as a RESP array of bulk strings. */
    static void write(OutputStream out, String... args) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            buf.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            buf.write(bytes);
            buf.write('\r');
            buf.write('\n');
        }
        out.write(buf.toByteArray());
        out.flush();
    }

    /**
     * One reply: a simple string or bulk string as {@code String}, an integer as {@code Long}, a null bulk as
     * {@code null}, an array as {@code List}. An error reply throws {@link RedisError}.
     */
    static Object read(InputStream in) throws IOException {
        int type = in.read();
        if (type < 0) {
            throw new EOFException("Redis closed the connection");
        }
        String line = readLine(in);
        switch (type) {
            case '+':
                return line;
            case '-':
                throw new RedisError(line);
            case ':':
                return Long.parseLong(line);
            case '$': {
                int length = Integer.parseInt(line);
                if (length < 0) {
                    return null;
                }
                byte[] bytes = in.readNBytes(length);
                if (bytes.length < length) {
                    throw new EOFException("Redis closed the connection");
                }
                readLine(in);
                return new String(bytes, StandardCharsets.UTF_8);
            }
            case '*': {
                int count = Integer.parseInt(line);
                if (count < 0) {
                    return null;
                }
                List<Object> items = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    items.add(read(in));
                }
                return items;
            }
            default:
                throw new IOException("Unexpected reply from Redis");
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("Redis closed the connection");
            }
            if (b == '\r') {
                int next = in.read();
                if (next == '\n') {
                    return line.toString(StandardCharsets.UTF_8);
                }
                line.write(b);
                if (next < 0) {
                    throw new EOFException("Redis closed the connection");
                }
                line.write(next);
            } else {
                line.write(b);
            }
        }
    }
}
