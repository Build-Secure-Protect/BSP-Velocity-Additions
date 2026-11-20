package com.mrgregles.bsp_velocity_additions.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** SH-R1 5.1: the receiver on a real local port, called with a real HTTP client and real signatures. */
class WebhookReceiverTest {

    /** Fourthwall's documentation example secret. */
    private static final String SECRET = "00000000-0000-0000-0000-000000000000";
    private static final String VECTOR_BODY = "{\"id\":\"weve_vec\",\"webhookId\":\"wcon_1\",\"shopId\":\"sh_1\",\"type\":\"ORDER_PLACED\","
            + "\"apiVersion\":\"V1\",\"createdAt\":\"2026-11-19T12:00:00Z\",\"testMode\":true,\"data\":{}}";
    /** {@code printf '%s' BODY | openssl dgst -sha256 -hmac SECRET -binary | base64}, computed outside Java. */
    private static final String VECTOR_SIGNATURE = "QwsXe/rBNbZtPklqO2SL6D419is4E2+TKGFQJszRmho=";

    private final HttpClient client = HttpClient.newHttpClient();
    private final ExecutorService threads = Executors.newFixedThreadPool(2);
    private final List<String> stored = new ArrayList<>();
    private final List<String> processed = new ArrayList<>();
    private WebhookReceiver receiver;
    private boolean failStore;

    private int start(int maxBody) throws Exception {
        receiver = WebhookReceiver.start(new StoreConfig.Http("127.0.0.1", 0, "/fourthwall/webhook", maxBody), SECRET,
                (env, body) -> {
                    if (failStore) {
                        throw new SQLException("Access denied for user", "28000");
                    }
                    stored.add(env.id());
                    return true;
                }, processed::add, threads, mock(Logger.class));
        return receiver.port();
    }

    @AfterEach
    void stop() {
        if (receiver != null) {
            receiver.close();
        }
        threads.shutdownNow();
    }

    private HttpResponse<String> post(int port, String path, String body, String signature) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) {
            b.header(WebhookReceiver.SIGNATURE_HEADER, signature);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theSignatureMatchesOneComputedByOpenssl() {
        assertEquals(VECTOR_SIGNATURE, WebhookReceiver.sign(VECTOR_BODY.getBytes(StandardCharsets.UTF_8), SECRET.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aSignedEventIsStoredAnswered200AndHandedOnForProcessing() throws Exception {
        int port = start(1_048_576);

        HttpResponse<String> r = post(port, "/fourthwall/webhook", VECTOR_BODY, VECTOR_SIGNATURE);

        assertEquals(200, r.statusCode());
        assertEquals(List.of("weve_vec"), stored);
        Thread.sleep(50);
        assertEquals(List.of("weve_vec"), processed);
    }

    @Test
    void aMissingOrWrongSignatureIs401AndNothingIsStored() throws Exception {
        int port = start(1_048_576);

        assertEquals(401, post(port, "/fourthwall/webhook", VECTOR_BODY, null).statusCode());
        assertEquals(401, post(port, "/fourthwall/webhook", VECTOR_BODY, "").statusCode());
        assertEquals(401, post(port, "/fourthwall/webhook", VECTOR_BODY, VECTOR_SIGNATURE.replace('Q', 'R')).statusCode());
        assertEquals(401, post(port, "/fourthwall/webhook", VECTOR_BODY + " ", VECTOR_SIGNATURE).statusCode(), "the body was changed");
        assertTrue(stored.isEmpty());
    }

    @Test
    void wrongPathsAndMethodsAre404And405() throws Exception {
        int port = start(1_048_576);

        assertEquals(404, post(port, "/other", VECTOR_BODY, VECTOR_SIGNATURE).statusCode());
        assertEquals(404, post(port, "/fourthwall/webhook/x", VECTOR_BODY, VECTOR_SIGNATURE).statusCode());
        HttpResponse<String> get = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/fourthwall/webhook"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, get.statusCode());
        assertEquals("POST", get.headers().firstValue("Allow").orElse(""));
        assertTrue(stored.isEmpty());
    }

    @Test
    void aBodyOverTheLimitIs413() throws Exception {
        int port = start(1024);
        String big = "{\"x\":\"" + "a".repeat(2000) + "\"}";

        assertEquals(413, post(port, "/fourthwall/webhook", big, WebhookReceiver.sign(big.getBytes(StandardCharsets.UTF_8),
                SECRET.getBytes(StandardCharsets.UTF_8))).statusCode());
        assertTrue(stored.isEmpty());
    }

    @Test
    void aDatabaseFailureIs500SoFourthwallSendsItAgain() throws Exception {
        int port = start(1_048_576);
        failStore = true;

        assertEquals(500, post(port, "/fourthwall/webhook", VECTOR_BODY, VECTOR_SIGNATURE).statusCode());
        assertTrue(processed.isEmpty());
    }

    @Test
    void aSignedBodyThatIsNotAnEnvelopeIs400() throws Exception {
        int port = start(1_048_576);
        String body = "{\"hello\":1}";

        assertEquals(400, post(port, "/fourthwall/webhook", body, WebhookReceiver.sign(body.getBytes(StandardCharsets.UTF_8),
                SECRET.getBytes(StandardCharsets.UTF_8))).statusCode());
        assertTrue(stored.isEmpty());
    }

    @Test
    void theAnswerIsFastAndTheSameEventTwiceIsStoredOnceInTheDatabase() throws Exception {
        ShopDatabase db = StoreTesting.connectedDb();
        receiver = WebhookReceiver.start(new StoreConfig.Http("127.0.0.1", 0, "/fourthwall/webhook", 1_048_576), SECRET,
                (env, body) -> db.call(c -> ShopStore.insertEvent(c, env.id(), env.type(), env.testMode(), body)),
                processed::add, threads, mock(Logger.class));
        int port = receiver.port();

        long start = System.nanoTime();
        assertEquals(200, post(port, "/fourthwall/webhook", VECTOR_BODY, VECTOR_SIGNATURE).statusCode());
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(200, post(port, "/fourthwall/webhook", VECTOR_BODY, VECTOR_SIGNATURE).statusCode());

        assertTrue(millis < 2000, "answered in " + millis + " ms");
        long events = db.call(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM shop_events")) {
                rs.next();
                return rs.getLong(1);
            }
        });
        assertEquals(1L, events);
        Thread.sleep(50);
        assertEquals(List.of("weve_vec"), processed, "the replay is not processed again");
        assertFalse(WebhookReceiver.verify(new byte[0], SECRET.getBytes(StandardCharsets.UTF_8), null));
    }
}
