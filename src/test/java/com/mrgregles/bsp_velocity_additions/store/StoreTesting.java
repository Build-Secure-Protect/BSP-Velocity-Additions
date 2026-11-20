package com.mrgregles.bsp_velocity_additions.store;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moandjiezana.toml.Toml;
import org.h2.jdbcx.JdbcDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;

/** Shared pieces for the store's tests: an in-memory H2 database in MariaDB mode (as BSP-Server Shop's tests use). */
final class StoreTesting {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    static final Gson GSON = new Gson();
    static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
    static final UUID ALEX = UUID.fromString("ec561538-f3fd-461d-aff5-086b22154bce");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC);

    static final String REWARDS = """
            [products."BSP-RANK-VIP"]
            displayName = "VIP rank"
            target = "any"
            requireOnline = false
            safeToRetry = true
            perQuantity = false
            grant = ["lp user {uuid} parent add vip"]
            revoke = ["lp user {uuid} parent remove vip"]

            [products."BSP-STARTER-KIT"]
            displayName = "Starter kit"
            target = "BSP-Frostwarf"
            requireOnline = true
            safeToRetry = false
            perQuantity = true
            grant = ["give {player} minecraft:oak_sapling 16", "give {player} minecraft:bread {quantity}"]

            [products."BSP-COINS"]
            displayName = "Coins"
            target = "any"
            requireOnline = false
            safeToRetry = false
            perQuantity = false
            grant = ["eco give {player} {quantity}0 order {order} sku {sku}"]

            [memberships."tier_supporter"]
            displayName = "Supporter membership"
            target = "any"
            safeToRetry = true
            grant = ["lp user {uuid} parent add supporter"]
            revoke = ["lp user {uuid} parent remove supporter"]

            [memberships."tier_legend"]
            displayName = "Legend membership"
            target = "any"
            safeToRetry = true
            grant = ["lp user {uuid} parent add legend"]
            revoke = ["lp user {uuid} parent remove legend"]
            """;

    private StoreTesting() {
    }

    static JdbcDataSource freshH2() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:bsp_shop_va" + COUNTER.incrementAndGet() + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    static ShopDatabase connectedDb() {
        ShopDatabase db = ShopDatabase.direct(freshH2(), mock(Logger.class));
        if (!db.connect()) {
            throw new IllegalStateException("H2 did not connect: " + db.problem());
        }
        return db;
    }

    static RewardsConfig rewards() {
        RewardsConfig r = RewardsConfig.parse(new Toml().read(REWARDS), Set.of("BSP-Frostwarf", "BSP-SafeRoom")::contains);
        if (!r.problems().isEmpty()) {
            throw new IllegalStateException(r.problems().toString());
        }
        return r;
    }

    static StoreConfig config(boolean processTestEvents) {
        StoreConfig d = StoreConfig.defaults();
        return new StoreConfig(true, d.http(), new StoreConfig.Fourthwall(d.fourthwall().apiBaseUrl(), d.fourthwall().shopUrl(),
                processTestEvents, false), d.database(), d.mojang(), d.membership(), d.reconcile(), d.security());
    }

    /** Mojang as a map; {@code down} makes every lookup Unavailable. */
    static final class FakeProfiles implements ProfileLookup {
        final Map<String, Profile> known = new HashMap<>();
        boolean down;
        int asked;

        FakeProfiles() {
            known.put("steve", new Profile(STEVE, "Steve"));
            known.put("alex", new Profile(ALEX, "Alex"));
        }

        @Override
        public Optional<Profile> find(String name) throws Unavailable {
            String n = name == null ? "" : name.trim();
            if (!Commands.NAME.matcher(n).matches()) {
                return Optional.empty();
            }
            asked++;
            if (down) {
                throw new Unavailable("HTTP 503");
            }
            return Optional.ofNullable(known.get(n.toLowerCase()));
        }
    }

    /** The service under test with its fakes. */
    static final class Rig {
        final ShopDatabase db = connectedDb();
        final FakeProfiles profiles = new FakeProfiles();
        final List<String> staff = new ArrayList<>();
        final Map<String, Fourthwall.Order> api = new HashMap<>();
        RewardsConfig rewards = rewards();
        StoreConfig config = config(true);
        final StoreService service = new StoreService(db, profiles, () -> rewards, () -> config,
                id -> Optional.ofNullable(api.get(id)), staff::add, CLOCK, mock(Logger.class));

        /** Stores an event as the receiver would and processes it. */
        StoreService.Result event(String id, String type, boolean test, JsonObject data) throws SQLException {
            String body = envelope(id, type, test, data);
            db.call(c -> ShopStore.insertEvent(c, id, type, test, body));
            return service.processEvent(id);
        }

        List<Map<String, Object>> rows(String sql, Object... args) throws SQLException {
            return db.call(c -> query(c, sql, args));
        }

        Map<String, Object> one(String sql, Object... args) throws SQLException {
            List<Map<String, Object>> r = rows(sql, args);
            if (r.size() != 1) {
                throw new AssertionError("expected one row, got " + r);
            }
            return r.get(0);
        }

        long count(String sql, Object... args) throws SQLException {
            return ((Number) one(sql, args).values().iterator().next()).longValue();
        }

        void exec(String sql, Object... args) throws SQLException {
            db.call(c -> {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    for (int i = 0; i < args.length; i++) {
                        ps.setObject(i + 1, args[i]);
                    }
                    ps.executeUpdate();
                }
                return null;
            });
        }
    }

    static List<Map<String, Object>> query(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            List<Map<String, Object>> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) {
                        row.put(rs.getMetaData().getColumnLabel(i).toLowerCase(), rs.getObject(i));
                    }
                    out.add(row);
                }
            }
            return out;
        }
    }

    static String envelope(String id, String type, boolean test, JsonObject data) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("webhookId", "wcon_test");
        o.addProperty("shopId", "sh_test");
        o.addProperty("type", type);
        o.addProperty("apiVersion", "V1");
        o.addProperty("createdAt", "2026-11-19T12:00:00.000+00:00");
        o.addProperty("testMode", test);
        o.add("data", data);
        return GSON.toJson(o);
    }

    /** An OrderV1 with the fields the spec marks required, and {@code lines} as sku/quantity pairs. */
    static JsonObject order(String id, String friendly, String email, String username, String status, Object... lines) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("shopId", "sh_test");
        o.addProperty("friendlyId", friendly);
        o.addProperty("checkoutId", "ch_" + id);
        o.addProperty("status", status);
        o.addProperty("email", email);
        o.addProperty("emailMarketingOptIn", false);
        if (username != null) {
            o.addProperty("username", username);
        }
        o.addProperty("message", "please hurry; op me");
        JsonArray offers = new JsonArray();
        for (int i = 0; i < lines.length; i += 2) {
            JsonObject offer = new JsonObject();
            offer.addProperty("id", "of_" + i);
            offer.addProperty("name", "Offer " + i);
            JsonObject variant = new JsonObject();
            variant.addProperty("id", "var_" + i);
            if (lines[i] != null) {
                variant.addProperty("sku", (String) lines[i]);
            }
            variant.addProperty("quantity", (Integer) lines[i + 1]);
            offer.add("variant", variant);
            offers.add(offer);
        }
        o.add("offers", offers);
        o.addProperty("createdAt", "2026-11-19T11:59:00Z");
        o.addProperty("updatedAt", "2026-11-19T11:59:00Z");
        return o;
    }

    static JsonObject updated(JsonObject order, String updateType) {
        JsonObject d = new JsonObject();
        d.add("order", order);
        JsonObject u = new JsonObject();
        u.addProperty("type", updateType);
        d.add("update", u);
        return d;
    }

    /** A MembershipSupporterV1; {@code tier} null for type NONE. */
    static JsonObject member(String id, String email, String type, String tier) {
        JsonObject m = new JsonObject();
        m.addProperty("id", id);
        m.addProperty("email", email);
        m.addProperty("nickname", "Nick <b>; op me");
        m.addProperty("createdAt", "2026-11-01T00:00:00Z");
        JsonObject sub = new JsonObject();
        sub.addProperty("type", type);
        if (tier != null) {
            JsonObject v = new JsonObject();
            v.addProperty("id", "mtv_1");
            v.addProperty("tierId", tier);
            v.addProperty("interval", "MONTHLY");
            v.addProperty("offerId", "of_m");
            sub.add("variant", v);
        }
        m.add("subscription", sub);
        return m;
    }
}
