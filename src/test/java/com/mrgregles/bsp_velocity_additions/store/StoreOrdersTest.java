package com.mrgregles.bsp_velocity_additions.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_velocity_additions.store.StoreTesting.Rig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.mrgregles.bsp_velocity_additions.store.StoreTesting.ALEX;
import static com.mrgregles.bsp_velocity_additions.store.StoreTesting.STEVE;
import static com.mrgregles.bsp_velocity_additions.store.StoreTesting.order;
import static com.mrgregles.bsp_velocity_additions.store.StoreTesting.updated;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SH-R1 5.2, 5.3 and 4.2 to 4.4: orders, their reward rows, replays and cancellations, against H2 in MariaDB mode. */
class StoreOrdersTest {

    private static final String PLACED = Fourthwall.ORDER_PLACED;

    @Test
    void aValidUsernameAssignsTheOrderAndWritesOneRowPerUnitWithTheContractsColumns() throws Exception {
        Rig rig = new Rig();
        StoreService.Result r = rig.event("weve_1", PLACED, false,
                order("ord_1", "D3XZFWPP", " Steve@Example.com ", " steve ", "CONFIRMED", "BSP-STARTER-KIT", 2));

        assertEquals(StoreService.Outcome.DONE, r.outcome());
        Map<String, Object> o = rig.one("SELECT * FROM shop_orders");
        assertEquals("ASSIGNED", o.get("state"));
        assertEquals(STEVE.toString(), o.get("player_uuid"));
        assertEquals("Steve", o.get("player_name"));
        assertEquals("steve@example.com", o.get("email"));
        assertEquals(" steve ", o.get("username_raw"));
        List<Map<String, Object>> rows = rig.rows("SELECT * FROM shop_rewards ORDER BY id");
        assertEquals(2, rows.size());
        Map<String, Object> first = rows.get(0);
        assertEquals("ORDER:ord_1:BSP-STARTER-KIT:0:0:GRANT", first.get("dedupe_key"));
        assertEquals("ORDER:ord_1:BSP-STARTER-KIT:0:1:GRANT", rows.get(1).get("dedupe_key"));
        assertEquals("ORDER", first.get("source_type"));
        assertEquals("D3XZFWPP", first.get("source_id"));
        assertEquals("BSP-STARTER-KIT", first.get("reward_key"));
        assertEquals("Starter kit", first.get("display_name"));
        assertEquals("GRANT", first.get("action"));
        assertEquals("8667ba71-b85a-4004-af54-457a9734eed7", first.get("player_uuid"));
        assertEquals("Steve", first.get("player_name"));
        assertEquals("BSP-Frostwarf", first.get("target"));
        assertEquals(true, first.get("require_online"));
        assertEquals(false, first.get("safe_to_retry"));
        assertEquals("[\"give Steve minecraft:oak_sapling 16\",\"give Steve minecraft:bread 1\"]", first.get("commands"));
        assertEquals("PENDING", first.get("status"));
        assertEquals(0, ((Number) first.get("attempts")).intValue());
        assertEquals(false, first.get("test_mode"));
        assertNull(first.get("claimed_by"));
        assertNotNull(first.get("created_at"));
        assertNotNull(rig.one("SELECT processed_at FROM shop_events").get("processed_at"));
    }

    @Test
    void perQuantityFalseWritesOneRowWithTheQuantityFilledIn() throws Exception {
        Rig rig = new Rig();
        rig.event("weve_1", PLACED, false, order("ord_1", "AAA1", "a@b.c", "Steve", "CONFIRMED", "BSP-COINS", 2));

        Map<String, Object> row = rig.one("SELECT dedupe_key, commands FROM shop_rewards");
        assertEquals("ORDER:ord_1:BSP-COINS:0:0:GRANT", row.get("dedupe_key"));
        assertEquals("[\"eco give Steve 20 order AAA1 sku BSP-COINS\"]", row.get("commands"));
    }

    @Test
    void aReplayedWebhookOrASecondEventForTheSameOrderWritesNothingMore() throws Exception {
        Rig rig = new Rig();
        rig.event("weve_1", PLACED, false, order("ord_1", "AAA1", "a@b.c", "Steve", "CONFIRMED", "BSP-STARTER-KIT", 3));
        rig.event("weve_1", PLACED, false, order("ord_1", "AAA1", "a@b.c", "Steve", "CONFIRMED", "BSP-STARTER-KIT", 3));
        rig.event("weve_2", PLACED, false, order("ord_1", "AAA1", "a@b.c", "Steve", "CONFIRMED", "BSP-STARTER-KIT", 3));

        assertEquals(3, rig.count("SELECT COUNT(*) FROM shop_rewards"));
        assertEquals(2, rig.count("SELECT COUNT(*) FROM shop_events"));
    }

    @Test
    void nothingTheBuyerTypesReachesACommand() throws Exception {
        Rig rig = new Rig();
        for (String typed : new String[] {"Steve; op Steve", "Steve\nop Steve", "§cSteve", "Steve op", ""}) {
            rig.profiles.known.put(typed.toLowerCase(), new ProfileLookup.Profile(STEVE, "Steve"));
        }
        rig.event("weve_1", PLACED, false, order("ord_1", "AAA1", "a@b.c", "Steve; op Steve", "CONFIRMED", "BSP-RANK-VIP", 1));
        rig.event("weve_2", PLACED, false, order("ord_2", "AAA2", "a@b.c", "Steve\nop Steve", "CONFIRMED", "BSP-RANK-VIP", 1));
        rig.event("weve_3", PLACED, false, order("ord_3", "AAA3", "a@b.c", null, "CONFIRMED", "BSP-RANK-VIP", 1));

        assertEquals(0, rig.count("SELECT COUNT(*) FROM shop_rewards"));
        assertEquals(3, rig.count("SELECT COUNT(*) FROM shop_orders WHERE state = 'AWAITING_PLAYER'"));
        assertEquals(0, rig.profiles.asked, "an invalid name is never even looked up");
    }

    @Test
    void anUnknownJavaNameWaitsForAPlayer() throws Exception {
        Rig rig = new Rig();
        rig.event("weve_1", PLACED, false, order("ord_1", "AAA1", "a@b.c", "NoSuchPlayer", "CONFIRMED", "BSP-RANK-VIP", 1));

        assertEquals("AWAITING_PLAYER", rig.one("SELECT state FROM shop_orders").get("state"));
        assertEquals(0, rig.count("SELECT COUNT(*) FROM shop_rewards"));
    }

    @Test
    void aBlankUsernameFromALinkedEmailGoesToTheLinkedPlayer() throws Exception {
        Rig rig = new Rig();
        rig.exec("INSERT INTO shop_links VALUES (?, 'Alex', 'alex@x.com', 'player', CURRENT_TIMESTAMP(3))", ALEX.toString());

        rig.event("weve_1", PLACED, false, order("ord_1", "AAA1", "ALEX@x.com", "", "CONFIRMED", "BSP-RANK-VIP", 1));

        assertEquals("Alex", rig.one("SELECT player_name FROM shop_orders").get("player_name"));
        assertEquals(ALEX.toString(), rig.one("SELECT player_uuid FROM shop_rewards").get("player_uuid"));
    }

    @Test
    void aSkuWithNoRewardWarnsStaffAndTheOtherLinesStillArrive() throws Exception {
        Rig rig = new Rig();
        rig.event("weve_1", PLACED, false, order("ord_1", "AAA1", "a@b.c", "Steve", "CONFIRMED", "T-SHIRT-L", 1, "BSP-RANK-VIP", 1, null, 1));

        assertEquals(1, rig.count("SELECT COUNT(*) FROM shop_rewards"));
        assertEquals("ORDER:ord_1:BSP-RANK-VIP:1:0:GRANT", rig.one("SELECT dedupe_key FROM shop_rewards").get("dedupe_key"));
        assertEquals(2, rig.staff.size());
        assertTrue(rig.staff.get(0).contains("T-SHIRT-L"));
    }

    @Test
    void mojangBeingDownLeavesTheEventToTryAgainAndWritesNothing() throws Exception {
        Rig rig = new Rig();
        rig.profiles.down = true;

        StoreService.Result r = rig.event("weve_1", PLACED, false, order("ord_1", "AAA1", "a@b.c", "Steve", "CONFIRMED", "BSP-RANK-VIP", 1));

        assertEquals(StoreService.Outcome.RETRY, r.outcome());
        Map<String, Object> ev = rig.one("SELECT processed_at, error FROM shop_events");
        assertNull(ev.get("processed_at"));
        assertTrue(((String) ev.get("error")).contains("Mojang"));
        assertEquals(0, rig.count("SELECT COUNT(*) FROM shop_orders"));

        rig.profiles.down = false;
        assertEquals(StoreService.Outcome.DONE, rig.service.processEvent("weve_1").outcome());
        assertEquals(1, rig.count("SELECT COUNT(*) FROM shop_rewards"));
    }

    @Test
    void testEventsWaitUnlessProcessTestEventsIsOnAndTheirRowsAreTestRows() throws Exception {
        Rig rig = new Rig();
        rig.config = StoreTesting.config(false);
        assertEquals(StoreService.Outcome.SKIPPED,
                rig.event("weve_t", PLACED, true, order("ord_t", "TTT1", "a@b.c", "Steve", "CONFIRMED", "BSP-RANK-VIP", 1)).outcome());
        assertEquals(0, rig.count("SELECT COUNT(*) FROM shop_orders"));

        rig.config = StoreTesting.config(true);
        assertEquals(StoreService.Outcome.DONE, rig.service.processEvent("weve_t").outcome());
        assertEquals(true, rig.one("SELECT test_mode FROM shop_rewards").get("test_mode"));
        assertEquals(true, rig.one("SELECT test_mode FROM shop_orders").get("test_mode"));
    }

    @Test
    void anEventTypeTheStoreDoesNotUseIsMarkedProcessed() throws Exception {
        Rig rig = new Rig();
        rig.event("weve_d", "DONATION", false, new com.google.gson.JsonObject());

        Map<String, Object> ev = rig.one("SELECT processed_at, error FROM shop_events");
        assertNotNull(ev.get("processed_at"));
        assertTrue(((String) ev.get("error")).startsWith("ignored"));
    }

    @Test
    void cancellingCancelsPendingRowsRevokesDeliveredRanksAndWarnsAboutDeliveredItems() throws Exception {
        Rig rig = new Rig();
        rig.event("weve_1", PLACED, false, order("ord_1", "AAA1", "a@b.c", "Steve", "CONFIRMED",
                "BSP-RANK-VIP", 1, "BSP-STARTER-KIT", 2));
        // a backend delivered the rank and one kit; the second kit is still waiting
        rig.exec("UPDATE shop_rewards SET status = 'DELIVERED', claimed_by = 'BSP-Spawn' WHERE dedupe_key LIKE '%VIP%' "
                + "OR dedupe_key LIKE '%KIT:1:0:GRANT'");

        rig.event("weve_2", Fourthwall.ORDER_UPDATED, false, updated(order("ord_1", "AAA1", "a@b.c", "Steve", "CANCELLED",
                "BSP-RANK-VIP", 1, "BSP-STARTER-KIT", 2), "STATUS"));

        assertEquals("CANCELLED", rig.one("SELECT state FROM shop_orders").get("state"));
        assertEquals("CANCELLED", rig.one("SELECT status FROM shop_rewards WHERE dedupe_key LIKE '%KIT:1:1:GRANT'").get("status"));
        assertEquals("DELIVERED", rig.one("SELECT status FROM shop_rewards WHERE dedupe_key LIKE '%VIP:0:0:GRANT'").get("status"),
                "a delivered row is never changed");
        Map<String, Object> revoke = rig.one("SELECT * FROM shop_rewards WHERE action = 'REVOKE'");
        assertEquals("ORDER:ord_1:BSP-RANK-VIP:0:0:REVOKE", revoke.get("dedupe_key"));
        assertEquals("[\"lp user 8667ba71-b85a-4004-af54-457a9734eed7 parent remove vip\"]", revoke.get("commands"));
        assertEquals("PENDING", revoke.get("status"));
        assertEquals(1, rig.staff.size());
        assertTrue(rig.staff.get(0).contains("Starter kit"));

        rig.event("weve_3", Fourthwall.ORDER_UPDATED, false, updated(order("ord_1", "AAA1", "a@b.c", "Steve", "CANCELLED",
                "BSP-RANK-VIP", 1, "BSP-STARTER-KIT", 2), "STATUS"));
        assertEquals(1, rig.count("SELECT COUNT(*) FROM shop_rewards WHERE action = 'REVOKE'"), "a replay writes no second revoke");
        assertEquals(1, rig.staff.size(), "nor a second warning");
    }

    @Test
    void aRowBeingDeliveredIsWaitedForBeforeItsRevokeIsWritten() throws Exception {
        Rig rig = new Rig();
        rig.event("weve_1", PLACED, false, order("ord_1", "AAA1", "a@b.c", "Steve", "CONFIRMED", "BSP-RANK-VIP", 1));
        rig.exec("UPDATE shop_rewards SET status = 'CLAIMED', claimed_by = 'BSP-Spawn'");

        StoreService.Result r = rig.event("weve_2", Fourthwall.ORDER_UPDATED, false,
                updated(order("ord_1", "AAA1", "a@b.c", "Steve", "CANCELLED", "BSP-RANK-VIP", 1), "STATUS"));
        assertEquals(StoreService.Outcome.RETRY, r.outcome());
        assertEquals(0, rig.count("SELECT COUNT(*) FROM shop_rewards WHERE action = 'REVOKE'"));

        rig.exec("UPDATE shop_rewards SET status = 'DELIVERED'");
        assertEquals(StoreService.Outcome.DONE, rig.service.processEvent("weve_2").outcome());
        assertEquals(1, rig.count("SELECT COUNT(*) FROM shop_rewards WHERE action = 'REVOKE'"));
    }

    @Test
    void anOrderFirstSeenAsCancelledIsStoredCancelledWithNoRows() throws Exception {
        Rig rig = new Rig();
        rig.event("weve_1", Fourthwall.ORDER_UPDATED, false,
                updated(order("ord_1", "AAA1", "a@b.c", "Steve", "CANCELLED", "BSP-RANK-VIP", 1), "STATUS"));

        assertEquals("CANCELLED", rig.one("SELECT state FROM shop_orders").get("state"));
        assertEquals(0, rig.count("SELECT COUNT(*) FROM shop_rewards"));
    }

    @Test
    void anEmailUpdateChangesTheStoredEmailAndAnyOtherUpdateChangesNothing() throws Exception {
        Rig rig = new Rig();
        rig.event("weve_1", PLACED, false, order("ord_1", "AAA1", "old@b.c", "", "CONFIRMED", "BSP-RANK-VIP", 1));
        rig.event("weve_2", Fourthwall.ORDER_UPDATED, false, updated(order("ord_1", "AAA1", "New@B.c", "", "CONFIRMED",
                "BSP-RANK-VIP", 1), "EMAIL"));
        assertEquals("new@b.c", rig.one("SELECT email FROM shop_orders").get("email"));

        rig.event("weve_3", Fourthwall.ORDER_UPDATED, false, updated(order("ord_1", "AAA1", "new@b.c", "", "SHIPPED",
                "BSP-RANK-VIP", 1), "SHIPPING.ADDRESS"));
        assertEquals("AWAITING_PLAYER", rig.one("SELECT state FROM shop_orders").get("state"));
        assertEquals(3, rig.count("SELECT COUNT(*) FROM shop_events WHERE processed_at IS NOT NULL"));
    }

    @Test
    void theCommandsColumnIsAJsonArrayOfStringsBspServerShopAccepts() throws Exception {
        Rig rig = new Rig();
        rig.event("weve_1", PLACED, false, order("ord_1", "AAA1", "a@b.c", "Steve", "CONFIRMED", "BSP-STARTER-KIT", 1));

        JsonArray commands = JsonParser.parseString((String) rig.one("SELECT commands FROM shop_rewards").get("commands")).getAsJsonArray();
        assertEquals(2, commands.size());
        for (var c : commands) {
            assertTrue(c.isJsonPrimitive() && c.getAsJsonPrimitive().isString());
            assertNull(Commands.problem(c.getAsString()));
        }
    }

    @Test
    void aBadPayloadIsRecordedAndNeverRetriedAsIfFine() throws Exception {
        Rig rig = new Rig();
        com.google.gson.JsonObject broken = order("ord_1", "###", "a@b.c", "Steve", "CONFIRMED", "BSP-RANK-VIP", 1);

        assertEquals(StoreService.Outcome.FAILED, rig.event("weve_1", PLACED, false, broken).outcome());
        assertFalse(((String) rig.one("SELECT error FROM shop_events").get("error")).isEmpty());
        assertEquals(0, rig.count("SELECT COUNT(*) FROM shop_rewards"));
    }
}
