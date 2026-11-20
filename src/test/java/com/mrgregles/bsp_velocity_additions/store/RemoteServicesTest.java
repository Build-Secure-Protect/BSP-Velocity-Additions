package com.mrgregles.bsp_velocity_additions.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_velocity_additions.store.StoreTesting.Rig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static com.mrgregles.bsp_velocity_additions.store.StoreTesting.member;
import static com.mrgregles.bsp_velocity_additions.store.StoreTesting.order;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** SH-R1 5.2 and 5.6: Mojang lookups and Fourthwall's API, against local HTTP servers that answer as they do. */
class RemoteServicesTest {

    private record Answer(int status, String body) {
    }

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private final List<Long> slept = new ArrayList<>();
    private final HttpClient client = HttpClient.newHttpClient();

    private String serve(Function<HttpExchange, Answer> answer) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requests.add(ex.getRequestURI().toString() + " " + ex.getRequestHeaders().getFirst("Authorization"));
            Answer a = answer.apply(ex);
            byte[] b = a.body().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(a.status(), a.status() == 204 ? -1 : b.length);
            if (a.status() != 204) {
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(b);
                }
            }
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private MojangProfiles mojang(String base, Duration cache) {
        return new MojangProfiles(client, base + "/users/profiles/minecraft/", cache, StoreTesting.CLOCK, slept::add);
    }

    // ---------------------------------------------------------------- Mojang

    @Test
    void mojangGivesTheHyphenatedUuidAndTheCorrectlyCasedName() throws Exception {
        String base = serve(ex -> new Answer(200, "{\"id\":\"8667BA71B85A4004AF54457A9734EED7\",\"name\":\"Steve\"}"));

        Optional<ProfileLookup.Profile> p = mojang(base, Duration.ZERO).find(" sTeVe ");

        assertEquals(UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7"), p.orElseThrow().uuid());
        assertEquals("8667ba71-b85a-4004-af54-457a9734eed7", p.get().uuid().toString());
        assertEquals("Steve", p.get().name());
        assertEquals("/users/profiles/minecraft/sTeVe null", requests.get(0));
    }

    @Test
    void a404OrA204MeansNoSuchPlayer() throws Exception {
        String base = serve(ex -> ex.getRequestURI().getPath().endsWith("Gone") ? new Answer(204, "") : new Answer(404, "{}"));

        assertTrue(mojang(base, Duration.ZERO).find("Nobody").isEmpty());
        assertTrue(mojang(base, Duration.ZERO).find("Gone").isEmpty());
    }

    @Test
    void rateLimitsAndServerErrorsAreTriedAgainWithBackoffThenReported() throws Exception {
        int[] calls = {0};
        String base = serve(ex -> ++calls[0] < 3 ? new Answer(calls[0] == 1 ? 429 : 503, "") : new Answer(200,
                "{\"id\":\"8667ba718b5a4004af54457a9734eed7\",\"name\":\"Steve\"}"));
        assertTrue(mojang(base, Duration.ZERO).find("Steve").isPresent());
        assertEquals(List.of(1000L, 2000L), slept);

        stop();
        slept.clear();
        String down = serve(ex -> new Answer(500, ""));
        assertThrows(ProfileLookup.Unavailable.class, () -> mojang(down, Duration.ZERO).find("Steve"));
        assertEquals(List.of(1000L, 2000L, 4000L), slept);
    }

    @Test
    void answersAreCachedAndInvalidNamesAreNeverSent() throws Exception {
        String base = serve(ex -> new Answer(200, "{\"id\":\"8667ba718b5a4004af54457a9734eed7\",\"name\":\"Steve\"}"));
        MojangProfiles m = mojang(base, Duration.ofMinutes(60));

        m.find("Steve");
        m.find("STEVE");
        m.find("Steve; op x");
        m.find("ab");
        m.find("a".repeat(17));

        assertEquals(1, requests.size());
    }

    // ---------------------------------------------------------------- Fourthwall API and reconciliation

    private static String page(List<JsonObject> results, int page, int totalPages) {
        JsonObject o = new JsonObject();
        JsonArray a = new JsonArray();
        results.forEach(a::add);
        o.add("results", a);
        o.addProperty("total", results.size());
        o.addProperty("page", page);
        o.addProperty("size", 100);
        o.addProperty("totalPages", totalPages);
        return StoreTesting.GSON.toJson(o);
    }

    @Test
    void theApiUsesBasicAuthTheDocumentedPathsAndEveryPage() throws Exception {
        String base = serve(ex -> {
            String q = ex.getRequestURI().getRawQuery();
            if (ex.getRequestURI().getPath().equals("/open-api/v1.0/order")) {
                return new Answer(200, page(List.of(order("ord_" + q.charAt(q.indexOf("page=") + 5), "AAA1", "a@b.c", "", "CONFIRMED")),
                        q.contains("page=0") ? 0 : 1, 2));
            }
            return new Answer(404, "{}");
        });
        FourthwallApi api = new FourthwallApi(client, base, "user", "pass", slept::add, 0);

        List<JsonObject> orders = api.ordersSince(java.time.Instant.parse("2026-11-05T12:00:00Z"));

        assertEquals(2, orders.size());
        assertEquals("/open-api/v1.0/order?createdAt%5Bgt%5D=2026-11-05T12%3A00%3A00Z&page=0&size=100 Basic dXNlcjpwYXNz",
                requests.get(0));
        assertTrue(requests.get(1).contains("page=1"));
        FourthwallApi.ApiException e = assertThrows(FourthwallApi.ApiException.class, () -> api.order("nope"));
        assertTrue(e.getMessage().contains("404"));
    }

    @Test
    void aRefusedLoginNamesTheVariablesNotTheirValues() throws Exception {
        String base = serve(ex -> new Answer(401, "{}"));
        FourthwallApi api = new FourthwallApi(client, base, "user", "secret-pass", slept::add, 0);

        FourthwallApi.ApiException e = assertThrows(FourthwallApi.ApiException.class, api::members);
        assertTrue(e.getMessage().contains("BSP_FOURTHWALL_API_PASSWORD"));
        assertTrue(!e.getMessage().contains("secret-pass"));
    }

    @Test
    void resyncAddsMissedOrdersCancelsCancelledOnesFixesMembersAndASecondRunAddsNothing() throws Exception {
        Rig rig = new Rig();
        rig.exec("INSERT INTO shop_links VALUES (?, 'Alex', 'alex@x.com', 'player', CURRENT_TIMESTAMP(3))", StoreTesting.ALEX.toString());
        rig.event("weve_1", Fourthwall.ORDER_PLACED, false, order("ord_known", "KNOWN1", "a@b.c", "Steve", "CONFIRMED", "BSP-RANK-VIP", 1));
        rig.event("weve_t", Fourthwall.ORDER_PLACED, true, order("ord_test", "TEST1", "a@b.c", "Steve", "CONFIRMED", "BSP-RANK-VIP", 1));
        rig.exec("DELETE FROM shop_orders WHERE order_id = 'ord_test'"); // as if processTestEvents had been off
        rig.exec("DELETE FROM shop_rewards WHERE dedupe_key LIKE 'ORDER:ord_test:%'");
        List<JsonObject> orders = List.of(
                order("ord_missed", "MISSED1", "a@b.c", "Steve", "CONFIRMED", "BSP-STARTER-KIT", 1),
                order("ord_known", "KNOWN1", "a@b.c", "Steve", "CANCELLED", "BSP-RANK-VIP", 1),
                order("ord_test", "TEST1", "a@b.c", "Steve", "CONFIRMED", "BSP-RANK-VIP", 1));
        List<JsonObject> members = List.of(member("mem_1", "alex@x.com", "ACTIVE", "tier_supporter"));
        String base = serve(ex -> ex.getRequestURI().getPath().endsWith("/order") ? new Answer(200, page(orders, 0, 1))
                : new Answer(200, page(members, 0, 1)));
        Reconciler recon = new Reconciler(new FourthwallApi(client, base, "u", "p", slept::add, 0), rig.db, rig.service,
                StoreTesting.CLOCK, mock(Logger.class));

        Reconciler.Summary first = recon.run(14);

        assertEquals(3, first.ordersChecked());
        assertEquals(1, first.ordersNew());
        assertEquals(1, first.ordersCancelled());
        assertEquals(1, first.membersChanged());
        assertEquals(1, rig.count("SELECT COUNT(*) FROM shop_rewards WHERE dedupe_key = 'ORDER:ord_missed:BSP-STARTER-KIT:0:0:GRANT'"));
        assertEquals("CANCELLED", rig.one("SELECT state FROM shop_orders WHERE order_id = 'ord_known'").get("state"));
        assertEquals(0, rig.count("SELECT COUNT(*) FROM shop_orders WHERE order_id = 'ord_test'"), "a test order is never made real");
        assertEquals(1, rig.count("SELECT COUNT(*) FROM shop_events WHERE event_id = 'recon:ord_missed' AND processed_at IS NOT NULL"));
        long rows = rig.count("SELECT COUNT(*) FROM shop_rewards");

        Reconciler.Summary second = recon.run(14);

        assertEquals(0, second.ordersNew());
        assertEquals(0, second.ordersCancelled());
        assertEquals(0, second.membersChanged());
        assertEquals(rows, rig.count("SELECT COUNT(*) FROM shop_rewards"));
        assertTrue(first.line().startsWith("Store resync: 3 order(s) checked, 1 new, 1 cancelled; 1 member(s) checked, 1 changed"));
    }
}
