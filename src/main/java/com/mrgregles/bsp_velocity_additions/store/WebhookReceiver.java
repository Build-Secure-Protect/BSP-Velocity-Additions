package com.mrgregles.bsp_velocity_additions.store;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fourthwall's webhook, {@code POST <path>} on the JDK's own HTTP server. A wrong path gets 404, a wrong method 405, a
 * body over the limit 413 and a missing or wrong {@code X-Fourthwall-Hmac-SHA256} (base64 HMAC-SHA256 of the raw body
 * with the webhook secret, compared in constant time) 401. A signed event is stored in {@code shop_events} and answered
 * 200 at once; processing happens later on the store's worker. A failed insert answers 500, so Fourthwall tries again.
 */
public final class WebhookReceiver implements AutoCloseable {

    public static final String SIGNATURE_HEADER = "X-Fourthwall-Hmac-SHA256";

    /** Stores an event; true when it should be processed (new, or stored before but never processed). */
    @FunctionalInterface
    public interface Sink {
        boolean store(Fourthwall.Envelope envelope, String body) throws SQLException;
    }

    /** Called after the 200 has been sent, for an event the sink said to process. */
    @FunctionalInterface
    public interface AfterStore {
        void accept(String eventId);
    }

    private final HttpServer server;
    private final String path;
    private final int maxBodyBytes;
    private final byte[] secret;
    private final Sink sink;
    private final AfterStore after;
    private final Logger logger;
    private final AtomicLong lastRejectionLog = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    private WebhookReceiver(HttpServer server, String path, int maxBodyBytes, byte[] secret, Sink sink, AfterStore after, Logger logger) {
        this.server = server;
        this.path = path;
        this.maxBodyBytes = maxBodyBytes;
        this.secret = secret;
        this.sink = sink;
        this.after = after;
        this.logger = logger;
    }

    public static WebhookReceiver start(StoreConfig.Http http, String secret, Sink sink, AfterStore after,
                                        ExecutorService executor, Logger logger) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(http.bind(), http.port()), 16);
        WebhookReceiver receiver = new WebhookReceiver(server, http.path(), http.maxBodyBytes(),
                secret.getBytes(StandardCharsets.UTF_8), sink, after, logger);
        server.createContext("/", receiver::handle);
        server.setExecutor(executor);
        server.start();
        return receiver;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** The header value Fourthwall sends for this body. */
    public static String sign(byte[] body, byte[] secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    /** Constant-time check of the header against the body. */
    public static boolean verify(byte[] body, byte[] secret, String header) {
        if (header == null || header.isBlank()) {
            return false;
        }
        byte[] expected = sign(body, secret).getBytes(StandardCharsets.US_ASCII);
        byte[] given = header.trim().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, given);
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            if (!path.equals(ex.getRequestURI().getPath())) {
                reply(ex, 404, "not found");
                return;
            }
            if (!"POST".equals(ex.getRequestMethod())) {
                ex.getResponseHeaders().set("Allow", "POST");
                reply(ex, 405, "method not allowed");
                return;
            }
            String length = ex.getRequestHeaders().getFirst("Content-Length");
            if (length != null) {
                try {
                    if (Long.parseLong(length.trim()) > maxBodyBytes) {
                        reply(ex, 413, "too large");
                        return;
                    }
                } catch (NumberFormatException e) {
                    reply(ex, 400, "bad length");
                    return;
                }
            }
            byte[] body;
            try (InputStream in = ex.getRequestBody()) {
                body = in.readNBytes(maxBodyBytes + 1);
            }
            if (body.length > maxBodyBytes) {
                reply(ex, 413, "too large");
                return;
            }
            if (!verify(body, secret, ex.getRequestHeaders().getFirst(SIGNATURE_HEADER))) {
                noteRejection(ex);
                reply(ex, 401, "bad signature");
                return;
            }
            String text = new String(body, StandardCharsets.UTF_8);
            Fourthwall.Envelope envelope;
            try {
                envelope = Fourthwall.Envelope.parse(text);
            } catch (Fourthwall.BadPayload e) {
                logger.warn("Store: a signed webhook could not be read ({}); answered 400", e.getMessage());
                reply(ex, 400, "bad payload");
                return;
            }
            boolean process;
            try {
                process = sink.store(envelope, text);
            } catch (SQLException | RuntimeException e) {
                logger.warn("Store: webhook {} ({}) could not be stored: {}; answered 500 so Fourthwall sends it again",
                        envelope.id(), envelope.type(), ShopDatabase.describe(e));
                reply(ex, 500, "try again");
                return;
            }
            reply(ex, 200, "ok");
            logger.info("Store: webhook {} {}{} stored", envelope.type(), envelope.id(), envelope.testMode() ? " (test)" : "");
            if (process) {
                after.accept(envelope.id());
            }
        } catch (IOException | RuntimeException e) {
            logger.debug("Store: webhook request failed: {}", e.toString());
        }
    }

    /** One log line a minute at most, with the count, so an unsigned flood cannot fill the log. */
    private void noteRejection(HttpExchange ex) {
        long count = rejected.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastRejectionLog.get();
        if (now - last >= 60_000 && lastRejectionLog.compareAndSet(last, now)) {
            rejected.set(0);
            logger.warn("Store: refused {} webhook request(s) without a valid signature in the last minute (latest from {})",
                    count, ex.getRemoteAddress().getAddress().getHostAddress());
        }
    }

    private static void reply(HttpExchange ex, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(1);
    }
}
