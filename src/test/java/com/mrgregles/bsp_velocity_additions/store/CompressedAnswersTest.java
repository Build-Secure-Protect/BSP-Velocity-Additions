package com.mrgregles.bsp_velocity_additions.store;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B16: Fourthwall's API answers gzip-compressed even when not asked; the answer must be unpacked, not misread. */
class CompressedAnswersTest {

    private HttpServer server;
    private final List<String> acceptEncoding = new ArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static byte[] gzip(String s) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (GZIPOutputStream z = new GZIPOutputStream(b)) {
            z.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return b.toByteArray();
    }

    private static byte[] deflate(String s) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(b, new Deflater())) {
            z.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return b.toByteArray();
    }

    /** Answers every request with {@code body} compressed as {@code encoding}, whatever the request asked for. */
    private String serve(String encoding, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            acceptEncoding.add(String.valueOf(ex.getRequestHeaders().getFirst("Accept-Encoding")));
            byte[] out;
            try {
                out = switch (encoding) {
                    case "gzip" -> gzip(body);
                    case "deflate" -> deflate(body);
                    default -> body.getBytes(StandardCharsets.UTF_8);
                };
            } catch (Exception e) {
                throw new java.io.IOException(e);
            }
            ex.getResponseHeaders().set("Content-Type", "application/json");
            if (!encoding.equals("identity")) {
                ex.getResponseHeaders().set("Content-Encoding", encoding);
            }
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(out);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static final String PAGE = "{\"results\":[{\"id\":\"ord_1\"}],\"page\":0,\"size\":100,\"total\":1,\"totalPages\":1}";

    @Test
    void aGzipAnswerFromFourthwallIsUnpacked() throws Exception {
        String base = serve("gzip", PAGE);

        List<com.google.gson.JsonObject> orders = new FourthwallApi(HttpClient.newHttpClient(), base, "u", "p", ms -> { }, 0)
                .ordersSince(java.time.Instant.parse("2026-11-01T00:00:00Z"));

        assertEquals(1, orders.size());
        assertEquals("ord_1", orders.get(0).get("id").getAsString());
        assertTrue(acceptEncoding.get(0).contains("gzip"), "asks for gzip openly: " + acceptEncoding);
    }

    @Test
    void aDeflateAnswerIsUnpackedToo() throws Exception {
        String base = serve("deflate", PAGE);

        assertEquals(1, new FourthwallApi(HttpClient.newHttpClient(), base, "u", "p", ms -> { }, 0).members().size());
    }

    @Test
    void anEncodingThatCannotBeUnpackedIsNamedAndTheBodyNeverShown() throws Exception {
        String base = serve("br", "{\"results\":[{\"email\":\"secret@example.com\"}]}");

        FourthwallApi.ApiException e = assertThrows(FourthwallApi.ApiException.class,
                () -> new FourthwallApi(HttpClient.newHttpClient(), base, "u", "p", ms -> { }, 0).members());

        assertTrue(e.getMessage().contains("br"), e.getMessage());
        assertFalse(e.getMessage().contains("secret@example.com"));
    }

    @Test
    void aGzipAnswerFromMojangIsUnpacked() throws Exception {
        String base = serve("gzip", "{\"id\":\"8667ba71b85a4004af54457a9734eed7\",\"name\":\"Steve\"}");

        var p = new MojangProfiles(HttpClient.newHttpClient(), base + "/users/profiles/minecraft/", Duration.ZERO,
                StoreTesting.CLOCK, ms -> { }).find("Steve");

        assertEquals("Steve", p.orElseThrow().name());
    }
}
