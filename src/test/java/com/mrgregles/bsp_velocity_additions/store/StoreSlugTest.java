package com.mrgregles.bsp_velocity_additions.store;

import com.google.gson.JsonObject;
import com.moandjiezana.toml.Toml;
import com.mrgregles.bsp_velocity_additions.store.StoreTesting.Rig;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.mrgregles.bsp_velocity_additions.store.StoreTesting.order;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D42: Fourthwall's digital products have no SKU box, so a product is matched on its variant's SKU when the rewards
 * know it, otherwise on its slug (the last part of its address, {@code /products/vip}).
 */
class StoreSlugTest {

    private static final String REWARDS = StoreTesting.REWARDS + """

            [products."vip"]
            displayName = "VIP rank (by slug)"
            target = "any"
            safeToRetry = true
            perQuantity = false
            grant = ["lp user {uuid} parent add storetest"]
            revoke = ["lp user {uuid} parent remove storetest"]
            """;

    /** An order whose lines carry these slugs (and SKUs as given, null for none). */
    private static JsonObject withSlugs(JsonObject order, String... slugs) {
        var offers = order.getAsJsonArray("offers");
        for (int i = 0; i < slugs.length; i++) {
            offers.get(i).getAsJsonObject().addProperty("slug", slugs[i]);
        }
        return order;
    }

    private static Rig rig() {
        Rig rig = new Rig();
        rig.rewards = RewardsConfig.parse(new Toml().read(REWARDS), Set.of("BSP-Frostwarf")::contains);
        assertTrue(rig.rewards.problems().isEmpty(), rig.rewards.problems().toString());
        return rig;
    }

    @Test
    void aDigitalProductWithNoSkuIsMatchedOnItsSlug() throws Exception {
        Rig rig = rig();
        rig.event("weve_1", Fourthwall.ORDER_PLACED, false,
                withSlugs(order("ord_1", "AAA1", "a@b.c", "Steve", "CONFIRMED", null, 1), "vip"));

        var row = rig.one("SELECT dedupe_key, reward_key, display_name, commands FROM shop_rewards");
        assertEquals("ORDER:ord_1:vip:0:0:GRANT", row.get("dedupe_key"));
        assertEquals("vip", row.get("reward_key"));
        assertEquals("[\"lp user 8667ba71-b85a-4004-af54-457a9734eed7 parent add storetest\"]", row.get("commands"));
        assertTrue(rig.staff.isEmpty(), rig.staff.toString());
    }

    @Test
    void aKnownSkuWinsOverTheSlugAndAnUnknownSkuFallsBackToTheSlug() throws Exception {
        Rig rig = rig();
        rig.event("weve_1", Fourthwall.ORDER_PLACED, false, withSlugs(order("ord_1", "AAA1", "a@b.c", "Steve", "CONFIRMED",
                "BSP-COINS", 1, "UNKNOWN-SKU", 1), "vip", "vip"));

        assertEquals(1, rig.count("SELECT COUNT(*) FROM shop_rewards WHERE reward_key = 'BSP-COINS'"));
        assertEquals(1, rig.count("SELECT COUNT(*) FROM shop_rewards WHERE dedupe_key = 'ORDER:ord_1:vip:1:0:GRANT'"));
    }

    @Test
    void aLineMatchingNeitherWarnsStaffNamingBoth() throws Exception {
        Rig rig = rig();
        rig.event("weve_1", Fourthwall.ORDER_PLACED, false,
                withSlugs(order("ord_1", "AAA1", "a@b.c", "Steve", "CONFIRMED", null, 1, "T-SHIRT", 1), "mystery", "tee"));

        assertEquals(0, rig.count("SELECT COUNT(*) FROM shop_rewards"));
        assertEquals(2, rig.staff.size());
        assertTrue(rig.staff.get(0).contains("mystery"), rig.staff.get(0));
        assertTrue(rig.staff.get(1).contains("T-SHIRT") && rig.staff.get(1).contains("tee"), rig.staff.get(1));
    }

    @Test
    void cancellingASlugMatchedOrderRevokesThroughTheSlugReward() throws Exception {
        Rig rig = rig();
        JsonObject placed = withSlugs(order("ord_1", "AAA1", "a@b.c", "Steve", "CONFIRMED", null, 1), "vip");
        rig.event("weve_1", Fourthwall.ORDER_PLACED, false, placed);
        rig.exec("UPDATE shop_rewards SET status = 'DELIVERED'");

        rig.event("weve_2", Fourthwall.ORDER_UPDATED, false, StoreTesting.updated(
                withSlugs(order("ord_1", "AAA1", "a@b.c", "Steve", "CANCELLED", null, 1), "vip"), "STATUS"));

        assertEquals("ORDER:ord_1:vip:0:0:REVOKE", rig.one("SELECT dedupe_key FROM shop_rewards WHERE action = 'REVOKE'").get("dedupe_key"));
    }
}
