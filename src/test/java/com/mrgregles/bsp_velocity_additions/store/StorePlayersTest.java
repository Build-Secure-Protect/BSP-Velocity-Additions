package com.mrgregles.bsp_velocity_additions.store;

import com.mrgregles.bsp_velocity_additions.store.ProfileLookup.Profile;
import com.mrgregles.bsp_velocity_additions.store.StoreTesting.Rig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.mrgregles.bsp_velocity_additions.store.StoreTesting.ALEX;
import static com.mrgregles.bsp_velocity_additions.store.StoreTesting.STEVE;
import static com.mrgregles.bsp_velocity_additions.store.StoreTesting.member;
import static com.mrgregles.bsp_velocity_additions.store.StoreTesting.order;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SH-R1 5.4 and 5.7: memberships, /store link, /store claim and the staff commands. */
class StorePlayersTest {

    private static final Profile STEVE_P = new Profile(STEVE, "Steve");
    private static final Profile ALEX_P = new Profile(ALEX, "Alex");

    private static void link(Rig rig, Profile p, String email) throws Exception {
        rig.exec("INSERT INTO shop_links VALUES (?, ?, ?, 'player', CURRENT_TIMESTAMP(3))", p.uuid().toString(), p.name(), email);
    }

    // ---------------------------------------------------------------- memberships

    @Test
    void aMemberWithNoLinkIsStoredAndGetsTheirRewardsTheMomentTheyLink() throws Exception {
        Rig rig = new Rig();
        rig.event("weve_1", Fourthwall.SUBSCRIPTION_PURCHASED, false, member("mem_1", "S@x.com", "ACTIVE", "tier_supporter"));

        Map<String, Object> m = rig.one("SELECT * FROM shop_members");
        assertEquals("s@x.com", m.get("email"));
        assertEquals("ACTIVE", m.get("sub_type"));
        assertEquals("tier_supporter", m.get("tier_id"));
        assertNull(m.get("granted_tier"));
        assertEquals(0, rig.count("SELECT COUNT(*) FROM shop_rewards"));
        assertTrue(rig.staff.isEmpty(), "D40: no staff message");

        StoreService.LinkResult r = rig.service.link(STEVE_P, " s@X.com ");

        assertEquals(StoreService.LinkOutcome.LINKED, r.outcome());
        assertEquals(1, r.memberships());
        Map<String, Object> row = rig.one("SELECT * FROM shop_rewards");
        assertEquals("MEMBERSHIP:mem_1:1:tier_supporter:GRANT", row.get("dedupe_key"));
        assertEquals("MEMBERSHIP", row.get("source_type"));
        assertEquals("mem_1", row.get("source_id"));
        assertEquals("[\"lp user 8667ba71-b85a-4004-af54-457a9734eed7 parent add supporter\"]", row.get("commands"));
        assertEquals("tier_supporter", rig.one("SELECT granted_tier FROM shop_members").get("granted_tier"));
        assertEquals("player", rig.one("SELECT linked_by FROM shop_links").get("linked_by"));
    }

    @Test
    void replaysChangeNothingAndATierChangeRevokesTheOldTierAndGrantsTheNew() throws Exception {
        Rig rig = new Rig();
        link(rig, STEVE_P, "s@x.com");
        rig.event("weve_1", Fourthwall.SUBSCRIPTION_PURCHASED, false, member("mem_1", "s@x.com", "ACTIVE", "tier_supporter"));
        rig.event("weve_2", Fourthwall.SUBSCRIPTION_CHANGED, false, member("mem_1", "s@x.com", "ACTIVE", "tier_supporter"));
        rig.event("weve_1", Fourthwall.SUBSCRIPTION_PURCHASED, false, member("mem_1", "s@x.com", "ACTIVE", "tier_supporter"));
        assertEquals(1, rig.count("SELECT COUNT(*) FROM shop_rewards"));

        rig.event("weve_3", Fourthwall.SUBSCRIPTION_CHANGED, false, member("mem_1", "s@x.com", "ACTIVE", "tier_legend"));

        List<Map<String, Object>> rows = rig.rows("SELECT dedupe_key FROM shop_rewards ORDER BY id");
        assertEquals("MEMBERSHIP:mem_1:2:tier_supporter:REVOKE", rows.get(1).get("dedupe_key"));
        assertEquals("MEMBERSHIP:mem_1:3:tier_legend:GRANT", rows.get(2).get("dedupe_key"));
        assertEquals("tier_legend", rig.one("SELECT granted_tier FROM shop_members").get("granted_tier"));
    }

    @Test
    void aCancelledMembershipKeepsItsRewardsUntilSuspendedNoneOrExpired() throws Exception {
        Rig rig = new Rig();
        link(rig, STEVE_P, "s@x.com");
        rig.event("weve_1", Fourthwall.SUBSCRIPTION_PURCHASED, false, member("mem_1", "s@x.com", "ACTIVE", "tier_supporter"));
        rig.event("weve_2", Fourthwall.SUBSCRIPTION_CHANGED, false, member("mem_1", "s@x.com", "CANCELLED", "tier_supporter"));
        assertEquals(1, rig.count("SELECT COUNT(*) FROM shop_rewards"));

        rig.event("weve_3", Fourthwall.SUBSCRIPTION_EXPIRED, false, member("mem_1", "s@x.com", "CANCELLED", "tier_supporter"));
        assertEquals("MEMBERSHIP:mem_1:2:tier_supporter:REVOKE",
                rig.one("SELECT dedupe_key FROM shop_rewards WHERE action = 'REVOKE'").get("dedupe_key"));
        assertNull(rig.one("SELECT granted_tier FROM shop_members").get("granted_tier"));

        rig.event("weve_4", Fourthwall.SUBSCRIPTION_CHANGED, false, member("mem_1", "s@x.com", "NONE", null));
        assertEquals(2, rig.count("SELECT COUNT(*) FROM shop_rewards"), "nothing left to take back");

        // re-subscribing later grants again under a new seq
        rig.event("weve_5", Fourthwall.SUBSCRIPTION_PURCHASED, false, member("mem_1", "s@x.com", "ACTIVE", "tier_supporter"));
        assertEquals(1, rig.count("SELECT COUNT(*) FROM shop_rewards WHERE dedupe_key = 'MEMBERSHIP:mem_1:3:tier_supporter:GRANT'"));
    }

    @Test
    void suspendedTakesTheRewardsAwayByDefault() throws Exception {
        Rig rig = new Rig();
        link(rig, STEVE_P, "s@x.com");
        rig.event("weve_1", Fourthwall.SUBSCRIPTION_PURCHASED, false, member("mem_1", "s@x.com", "ACTIVE", "tier_supporter"));
        rig.event("weve_2", Fourthwall.SUBSCRIPTION_CHANGED, false, member("mem_1", "s@x.com", "SUSPENDED", "tier_supporter"));

        assertEquals(1, rig.count("SELECT COUNT(*) FROM shop_rewards WHERE action = 'REVOKE'"));
    }

    @Test
    void aTierWithNoRewardWarnsStaffAndIsGrantedOnceItIsAdded() throws Exception {
        Rig rig = new Rig();
        link(rig, STEVE_P, "s@x.com");
        rig.event("weve_1", Fourthwall.SUBSCRIPTION_PURCHASED, false, member("mem_1", "s@x.com", "ACTIVE", "tier_unknown"));
        assertEquals(0, rig.count("SELECT COUNT(*) FROM shop_rewards"));
        assertEquals(1, rig.staff.size());

        rig.event("weve_2", Fourthwall.SUBSCRIPTION_CHANGED, false, member("mem_1", "s@x.com", "ACTIVE", "tier_legend"));
        assertEquals(1, rig.count("SELECT COUNT(*) FROM shop_rewards WHERE action = 'GRANT'"));
    }

    @Test
    void nicknamesNeverReachACommand() throws Exception {
        Rig rig = new Rig();
        link(rig, STEVE_P, "s@x.com");
        rig.event("weve_1", Fourthwall.SUBSCRIPTION_PURCHASED, false, member("mem_1", "s@x.com", "ACTIVE", "tier_supporter"));

        assertFalse(((String) rig.one("SELECT commands FROM shop_rewards").get("commands")).contains("op me"));
    }

    // ---------------------------------------------------------------- link

    @Test
    void linkOnlyWorksForAnEmailTheStoreHasSeenAndNotLinkedByAnyoneElse() throws Exception {
        Rig rig = new Rig();
        assertEquals(StoreService.LinkOutcome.FAILED, rig.service.link(STEVE_P, "nobody@x.com").outcome());
        assertEquals(StoreService.LinkOutcome.FAILED, rig.service.link(STEVE_P, "not an email").outcome());

        rig.event("weve_1", Fourthwall.ORDER_PLACED, false, order("ord_1", "AAA1", "s@x.com", "", "CONFIRMED", "BSP-RANK-VIP", 1));
        rig.event("weve_2", Fourthwall.ORDER_PLACED, false, order("ord_2", "AAA2", "s@x.com", null, "CONFIRMED", "BSP-COINS", 3));
        StoreService.LinkResult r = rig.service.link(STEVE_P, "s@x.com");
        assertEquals(StoreService.LinkOutcome.LINKED, r.outcome());
        assertEquals(2, r.orders());
        assertEquals(2, rig.count("SELECT COUNT(*) FROM shop_orders WHERE state = 'ASSIGNED' AND player_name = 'Steve'"));
        assertEquals(2, rig.count("SELECT COUNT(*) FROM shop_rewards"));

        assertEquals(StoreService.LinkOutcome.FAILED, rig.service.link(ALEX_P, "s@x.com").outcome(), "taken by Steve");
        StoreService.LinkResult again = rig.service.link(STEVE_P, "other@x.com");
        assertEquals(StoreService.LinkOutcome.ALREADY_LINKED, again.outcome());
        assertEquals("s@x.com", again.email());
    }

    @Test
    void aLinkedEmailsNextOrderWithABlankUsernameIsAssignedAutomatically() throws Exception {
        Rig rig = new Rig();
        rig.event("weve_1", Fourthwall.ORDER_PLACED, false, order("ord_1", "AAA1", "s@x.com", "", "CONFIRMED", "BSP-RANK-VIP", 1));
        rig.service.link(STEVE_P, "s@x.com");

        rig.event("weve_2", Fourthwall.ORDER_PLACED, false, order("ord_2", "AAA2", "S@X.COM", "", "CONFIRMED", "BSP-COINS", 1));

        assertEquals("ASSIGNED", rig.one("SELECT state FROM shop_orders WHERE order_id = 'ord_2'").get("state"));
    }

    // ---------------------------------------------------------------- claim

    @Test
    void claimNeedsBothTheOrderNumberAndTheEmailAndWorksOnce() throws Exception {
        Rig rig = new Rig();
        rig.event("weve_1", Fourthwall.ORDER_PLACED, false, order("ord_1", "D3XZFWPP", "s@x.com", "", "CONFIRMED", "BSP-RANK-VIP", 1));

        assertEquals(StoreService.ClaimOutcome.FAILED, rig.service.claim(STEVE_P, "D3XZFWPP", "wrong@x.com").outcome());
        assertEquals(StoreService.ClaimOutcome.FAILED, rig.service.claim(STEVE_P, "WRONG1", "s@x.com").outcome());
        assertEquals(StoreService.ClaimOutcome.FAILED, rig.service.claim(STEVE_P, "D3XZFWP", "s@x.com").outcome());
        assertEquals(0, rig.count("SELECT COUNT(*) FROM shop_rewards"));

        assertEquals(StoreService.ClaimOutcome.CLAIMED, rig.service.claim(STEVE_P, "#d3xzfwpp", " S@x.com").outcome());
        assertEquals(1, rig.count("SELECT COUNT(*) FROM shop_rewards WHERE player_name = 'Steve'"));

        assertEquals(StoreService.ClaimOutcome.ALREADY_CLAIMED, rig.service.claim(ALEX_P, "D3XZFWPP", "s@x.com").outcome());
        assertEquals(1, rig.count("SELECT COUNT(*) FROM shop_rewards"));
    }

    @Test
    void aCancelledOrderCannotBeClaimed() throws Exception {
        Rig rig = new Rig();
        rig.event("weve_1", Fourthwall.ORDER_PLACED, false, order("ord_1", "AAA1", "s@x.com", "", "CANCELLED", "BSP-RANK-VIP", 1));

        assertEquals(StoreService.ClaimOutcome.CANCELLED, rig.service.claim(STEVE_P, "AAA1", "s@x.com").outcome());
        assertEquals(0, rig.count("SELECT COUNT(*) FROM shop_rewards"));
    }

    @Test
    void anOrderWhoseEventIsGoneIsReadFromFourthwallsApiToBeClaimed() throws Exception {
        Rig rig = new Rig();
        rig.event("weve_1", Fourthwall.ORDER_PLACED, false, order("ord_1", "AAA1", "s@x.com", "", "CONFIRMED", "BSP-RANK-VIP", 1));
        rig.exec("DELETE FROM shop_events");
        assertEquals(StoreService.ClaimOutcome.UNAVAILABLE, rig.service.claim(STEVE_P, "AAA1", "s@x.com").outcome());

        rig.api.put("ord_1", Fourthwall.Order.parse(order("ord_1", "AAA1", "s@x.com", "", "CONFIRMED", "BSP-RANK-VIP", 1)));
        assertEquals(StoreService.ClaimOutcome.CLAIMED, rig.service.claim(STEVE_P, "AAA1", "s@x.com").outcome());
    }

    // ---------------------------------------------------------------- staff

    @Test
    void staffAssignAWaitingOrderByNumberOrId() throws Exception {
        Rig rig = new Rig();
        rig.event("weve_1", Fourthwall.ORDER_PLACED, false, order("ord_1", "AAA1", "s@x.com", "", "CONFIRMED", "BSP-RANK-VIP", 1));
        rig.event("weve_2", Fourthwall.ORDER_PLACED, false, order("ord_2", "AAA2", "s@x.com", "", "CONFIRMED", "BSP-RANK-VIP", 1));

        assertEquals(StoreService.ClaimOutcome.CLAIMED, rig.service.staffAssign("aaa1", ALEX_P).outcome());
        assertEquals(StoreService.ClaimOutcome.CLAIMED, rig.service.staffAssign("ord_2", ALEX_P).outcome());
        assertEquals(StoreService.ClaimOutcome.ALREADY_CLAIMED, rig.service.staffAssign("AAA1", STEVE_P).outcome());
        assertEquals(StoreService.ClaimOutcome.FAILED, rig.service.staffAssign("NOPE", STEVE_P).outcome());
        assertEquals(2, rig.count("SELECT COUNT(*) FROM shop_rewards WHERE player_name = 'Alex'"));
    }

    @Test
    void unlinkTakesBackMembershipRewardsAndLeavesOrdersAlone() throws Exception {
        Rig rig = new Rig();
        rig.event("weve_1", Fourthwall.ORDER_PLACED, false, order("ord_1", "AAA1", "s@x.com", "", "CONFIRMED", "BSP-RANK-VIP", 1));
        rig.event("weve_2", Fourthwall.SUBSCRIPTION_PURCHASED, false, member("mem_1", "s@x.com", "ACTIVE", "tier_supporter"));
        rig.service.link(STEVE_P, "s@x.com");

        StoreService.UnlinkResult r = rig.service.unlink("steve").orElseThrow();

        assertEquals(1, r.revoked());
        assertEquals(0, rig.count("SELECT COUNT(*) FROM shop_links"));
        assertEquals(1, rig.count("SELECT COUNT(*) FROM shop_rewards WHERE dedupe_key = 'MEMBERSHIP:mem_1:2:tier_supporter:REVOKE'"));
        assertEquals("ASSIGNED", rig.one("SELECT state FROM shop_orders").get("state"));
        assertTrue(rig.service.unlink("steve").isEmpty());
    }

    @Test
    void aStaffGrantWritesAManualRowEveryTime() throws Exception {
        Rig rig = new Rig();
        assertTrue(rig.service.grant("Mrgregles", STEVE_P, "BSP-RANK-VIP").isPresent());
        assertTrue(rig.service.grant("Mrgregles", STEVE_P, "tier_legend").isPresent());
        assertTrue(rig.service.grant("console", STEVE_P, "NOPE").isEmpty());

        Map<String, Object> row = rig.rows("SELECT * FROM shop_rewards ORDER BY id").get(0);
        assertEquals("MANUAL:Mrgregles:" + StoreTesting.CLOCK.millis() + ":BSP-RANK-VIP:GRANT", row.get("dedupe_key"));
        assertEquals("MANUAL", row.get("source_type"));
        assertEquals("Mrgregles", row.get("source_id"));
        assertEquals(2, rig.count("SELECT COUNT(*) FROM shop_rewards WHERE source_type = 'MANUAL'"));
    }

    @Test
    void lookupFindsByEmailOrderNumberAndPlayer() throws Exception {
        Rig rig = new Rig();
        rig.event("weve_1", Fourthwall.ORDER_PLACED, false, order("ord_1", "AAA1", "s@x.com", "", "CONFIRMED", "BSP-RANK-VIP", 1));
        rig.service.link(STEVE_P, "s@x.com");

        StoreService.Lookup byEmail = rig.service.lookup("S@x.com");
        assertEquals(1, byEmail.links().size());
        assertEquals(1, byEmail.orders().size());
        StoreService.Lookup byOrder = rig.service.lookup("#aaa1");
        assertEquals(1, byOrder.orders().size());
        assertEquals(1, byOrder.rewards().size());
        StoreService.Lookup byName = rig.service.lookup("STEVE");
        assertEquals(1, byName.links().size());
        assertEquals(1, byName.orders().size());
        assertEquals(1, byName.rewards().size());
        assertTrue(rig.service.lookup("nobody").isEmpty());
    }

    @Test
    void statusShowsThePlayersOrdersAndRows() throws Exception {
        Rig rig = new Rig();
        rig.event("weve_1", Fourthwall.ORDER_PLACED, false, order("ord_1", "AAA1", "s@x.com", "Steve", "CONFIRMED", "BSP-STARTER-KIT", 2));

        StoreService.Status s = rig.service.status(STEVE);
        assertEquals(1, s.orders().size());
        assertEquals(2, s.rewards().size());
        assertTrue(rig.service.status(ALEX).orders().isEmpty());
    }
}
