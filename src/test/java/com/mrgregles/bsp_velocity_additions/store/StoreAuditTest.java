package com.mrgregles.bsp_velocity_additions.store;

import com.mrgregles.bsp_velocity_additions.config.Messages;
import com.mrgregles.bsp_velocity_additions.store.StoreTesting.Rig;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import javax.sql.DataSource;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static com.mrgregles.bsp_velocity_additions.store.StoreTesting.order;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Problems found in the 2026-11-19 audit of the store (bugs B7 to B13); each test failed against the code before its fix. */
class StoreAuditTest {

    private static final String SECRET = "audit-secret";

    /** An H2 database whose new connections can be refused, as a database that went away. */
    static final class Flaky implements DataSource {
        final JdbcDataSource inner = StoreTesting.freshH2();
        volatile boolean down;

        @Override
        public Connection getConnection() throws SQLException {
            if (down) {
                throw new SQLException("Could not connect: Connection refused", "08000");
            }
            return inner.getConnection();
        }

        @Override
        public Connection getConnection(String u, String p) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return null;
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            return null;
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    private final List<String> apiRequests = new ArrayList<>();
    private HttpServer fakeFourthwall;
    private StoreFeature feature;

    @AfterEach
    void stop() {
        if (feature != null) {
            feature.stop();
        }
        if (fakeFourthwall != null) {
            fakeFourthwall.stop(0);
        }
    }

    private String fourthwall() throws Exception {
        fakeFourthwall = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeFourthwall.createContext("/", ex -> {
            byte[] b;
            if (ex.getRequestURI().getPath().startsWith("/users/profiles/minecraft/")) {
                b = "{\"id\":\"8667ba71b85a4004af54457a9734eed7\",\"name\":\"Steve\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                apiRequests.add(ex.getRequestURI().toString());
                b = "{\"results\":[],\"page\":0,\"size\":100,\"total\":0,\"totalPages\":0}".getBytes(StandardCharsets.UTF_8);
            }
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(b);
            }
        });
        fakeFourthwall.start();
        return "http://127.0.0.1:" + fakeFourthwall.getAddress().getPort();
    }

    private static StoreConfig config(boolean capture, boolean test, String api) {
        StoreConfig d = StoreConfig.defaults();
        return new StoreConfig(true, new StoreConfig.Http("127.0.0.1", 0, "/fourthwall/webhook", 1_048_576),
                new StoreConfig.Fourthwall(api, "https://shop.example.com", test, capture), d.database(),
                new StoreConfig.Mojang(api + "/users/profiles/minecraft/", 60),
                d.membership(), d.reconcile(), d.security());
    }

    private StoreFeature feature(DataSource ds, StoreConfig config, Path dir) {
        ProxyServer proxy = mock(ProxyServer.class);
        when(proxy.getAllPlayers()).thenReturn(List.of());
        when(proxy.getAllServers()).thenReturn(List.of());
        when(proxy.getPlayer(any(String.class))).thenReturn(Optional.empty());
        Secrets secrets = Secrets.read(Map.of(Secrets.WEBHOOK_SECRET, SECRET, Secrets.API_USERNAME, "u", Secrets.API_PASSWORD, "p",
                Secrets.DB_PASSWORD, "d")::get);
        feature = new StoreFeature(proxy, dir, Messages.defaults(), mock(Logger.class), config, StoreTesting.rewards(), secrets,
                ShopDatabase.direct(ds, mock(Logger.class)));
        return feature;
    }

    private static int post(int port, String body) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/fourthwall/webhook"))
                .header(WebhookReceiver.SIGNATURE_HEADER, WebhookReceiver.sign(body.getBytes(StandardCharsets.UTF_8),
                        SECRET.getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(r, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static long count(DataSource ds, String sql) throws SQLException {
        try (Connection c = ds.getConnection()) {
            return ((Number) StoreTesting.query(c, sql).get(0).values().iterator().next()).longValue();
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier done) throws InterruptedException {
        long until = System.currentTimeMillis() + 5_000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
    }

    // ---------------------------------------------------------------- the feature end to end

    @Test
    void aSignedWebhookThroughTheWholeFeatureBecomesARewardRow(@TempDir Path dir) throws Exception {
        Flaky ds = new Flaky();
        StoreFeature f = feature(ds, config(false, true, fourthwall()), dir);
        f.connectTick();
        assertTrue(f.receiverPort() > 0);

        String body = StoreTesting.envelope("weve_1", Fourthwall.ORDER_PLACED, false,
                order("ord_1", "AAA1", "a@b.c", "Steve", "CONFIRMED", "BSP-RANK-VIP", 1));
        assertEquals(200, post(f.receiverPort(), body));
        waitFor(() -> {
            try {
                return count(ds.inner, "SELECT COUNT(*) FROM shop_rewards") == 1;
            } catch (SQLException e) {
                return false;
            }
        });
        assertEquals(1, count(ds.inner, "SELECT COUNT(*) FROM shop_rewards WHERE player_name = 'Steve'"));
        assertEquals(1, count(ds.inner, "SELECT COUNT(*) FROM shop_events WHERE processed_at IS NOT NULL"));
    }

    /** B7: after a lost connection the store stayed "not available" until the proxy restarted. */
    @Test
    void theStoreComesBackByItselfAfterTheDatabaseWasLost(@TempDir Path dir) throws Exception {
        Flaky ds = new Flaky();
        StoreFeature f = feature(ds, config(false, true, fourthwall()), dir);
        f.tickSeconds = 1;
        f.startConnecting();
        waitFor(() -> f.receiverPort() > 0);
        assertTrue(f.db().isConnected());

        ds.down = true;
        assertThrows(SQLException.class, () -> f.db().call(c -> null));
        assertFalse(f.db().isConnected());
        ds.down = false;
        waitFor(() -> f.db().isConnected());

        assertTrue(f.healthCheckRunning(), "the database is still checked after start-up");
        assertTrue(f.db().isConnected(), "connected again without a restart");
    }

    /** B8: with captureOnly on, the resync two minutes after start still made reward rows. */
    @Test
    void captureOnlyAlsoStopsTheResync(@TempDir Path dir) throws Exception {
        StoreFeature f = feature(new Flaky(), config(true, false, fourthwall()), dir);
        f.connectTick();

        Reconciler.Summary s = f.reconcile();

        assertTrue(apiRequests.isEmpty(), "Fourthwall was asked: " + apiRequests);
        assertEquals(0, s.ordersChecked());
    }

    /** B9: a broken store.toml on /storeadmin reload switched every setting back to the defaults. */
    @Test
    void aBrokenStoreTomlOnReloadKeepsTheSettingsInUse(@TempDir Path dir) throws Exception {
        StoreFeature f = feature(new Flaky(), config(true, true, fourthwall()), dir);
        Files.writeString(dir.resolve(StoreConfig.FILE_NAME), "enabled = true\n[fourthwall\ncaptureOnly = ");

        assertThrows(Exception.class, f::reloadConfig);

        assertTrue(f.config().fourthwall().captureOnly(), "still capturing");
        assertTrue(f.config().fourthwall().processTestEvents());
    }

    /** B10: stopping the proxy waited 5 s whenever a retry was scheduled. */
    @Test
    void stoppingDoesNotWaitForAScheduledRetry(@TempDir Path dir) throws Exception {
        Flaky ds = new Flaky();
        StoreFeature f = feature(ds, config(false, true, fourthwall()), dir);
        f.connectTick();
        String body = StoreTesting.envelope("weve_c", Fourthwall.ORDER_PLACED, false,
                order("ord_c", "CCC1", "a@b.c", "Steve", "CONFIRMED", "BSP-RANK-VIP", 1));
        try (Connection c = ds.getConnection()) {
            ShopStore.insertEvent(c, "weve_c", Fourthwall.ORDER_PLACED, false, body);
            ShopStore.insertOrder(c, Fourthwall.Order.parse(order("ord_c", "CCC1", "a@b.c", "Steve", "CONFIRMED", "BSP-RANK-VIP", 1)),
                    "ASSIGNED", new ProfileLookup.Profile(StoreTesting.STEVE, "Steve"), false);
            c.createStatement().execute("INSERT INTO shop_rewards (dedupe_key, source_type, source_id, reward_key, display_name, action, "
                    + "player_uuid, player_name, target, require_online, safe_to_retry, commands, status, attempts, test_mode, created_at) "
                    + "VALUES ('ORDER:ord_c:BSP-RANK-VIP:0:0:GRANT', 'ORDER', 'CCC1', 'BSP-RANK-VIP', 'VIP rank', 'GRANT', '"
                    + StoreTesting.STEVE + "', 'Steve', 'any', FALSE, TRUE, '[\"x\"]', 'CLAIMED', 0, FALSE, CURRENT_TIMESTAMP(3))");
        }
        String cancel = StoreTesting.envelope("weve_x", Fourthwall.ORDER_UPDATED, false, StoreTesting.updated(
                order("ord_c", "CCC1", "a@b.c", "Steve", "CANCELLED", "BSP-RANK-VIP", 1), "STATUS"));
        assertEquals(200, post(f.receiverPort(), cancel)); // a CLAIMED row: the cancel is retried in 2 min
        Thread.sleep(300);

        long start = System.nanoTime();
        f.stop();
        feature = null;
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(millis < 2_000, "stop took " + millis + " ms");
    }

    // ---------------------------------------------------------------- the service

    /** B11: a payload that can never be used stayed unprocessed and was tried again at every start. */
    @Test
    void aBadPayloadIsMarkedProcessedWithItsError() throws Exception {
        Rig rig = new Rig();
        rig.event("weve_1", Fourthwall.ORDER_PLACED, false, new com.google.gson.JsonObject());

        Map<String, Object> ev = rig.one("SELECT processed_at, error FROM shop_events");
        assertNotNull(ev.get("processed_at"));
        assertTrue(((String) ev.get("error")).startsWith("bad payload"));
    }

    /** B12: an order number with any other character (a "#", a space) made the whole order unusable. */
    @Test
    void anOrderNumberWithOtherCharactersIsCleanedNotRefused() throws Exception {
        Rig rig = new Rig();
        StoreService.Result r = rig.event("weve_1", Fourthwall.ORDER_PLACED, false,
                order("ord_1", "#d3x-zf 1", "s@x.com", "", "CONFIRMED", "BSP-RANK-VIP", 1));

        assertEquals(StoreService.Outcome.DONE, r.outcome());
        assertEquals("D3X-ZF1", rig.one("SELECT friendly_id FROM shop_orders").get("friendly_id"));
        assertEquals(StoreService.ClaimOutcome.CLAIMED, rig.service.claim(new ProfileLookup.Profile(StoreTesting.STEVE, "Steve"),
                "#d3x-zf1", "s@x.com").outcome());
    }

    /** B13: tries queued before the first failure was counted all ran, so the limit could be beaten by typing fast. */
    @Test
    void triesQueuedBehindEachOtherStillStopAtTheLimit() throws Exception {
        Rig rig = new Rig();
        List<Runnable> queue = new ArrayList<>();
        Executor later = queue::add;
        StoreCommand store = new StoreCommand(rig.service, later, Messages.defaults(), () -> rig.config,
                new FailedTries(5, StoreTesting.CLOCK), () -> true, mock(Logger.class));
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(StoreTesting.STEVE);
        when(p.getUsername()).thenReturn("Steve");
        for (int i = 0; i < 8; i++) {
            SimpleCommand.Invocation inv = mock(SimpleCommand.Invocation.class);
            when(inv.source()).thenReturn(p);
            when(inv.arguments()).thenReturn(new String[] {"claim", "ZZZ" + i, "s@x.com"});
            store.execute(inv);
        }
        queue.forEach(Runnable::run);

        ArgumentCaptor<Component> c = ArgumentCaptor.forClass(Component.class);
        verify(p, atLeastOnce()).sendMessage(c.capture());
        long failed = c.getAllValues().stream().map(PlainTextComponentSerializer.plainText()::serialize)
                .filter(s -> s.startsWith("No order matches")).count();
        assertEquals(5, failed);
    }

    /** B14: the resync asked Fourthwall for orders since a time with nine decimal places. */
    @Test
    void theOrdersSinceTimeIsSentInWholeSeconds() throws Exception {
        String base = fourthwall();
        new FourthwallApi(HttpClient.newHttpClient(), base, "u", "p", ms -> { }, 0)
                .ordersSince(Instant.parse("2026-11-05T12:00:00.123456789Z"));

        assertTrue(apiRequests.get(0).contains("createdAt%5Bgt%5D=2026-11-05T12%3A00%3A00Z&"), apiRequests.get(0));
    }
}
