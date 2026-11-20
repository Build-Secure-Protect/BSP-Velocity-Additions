package com.mrgregles.bsp_velocity_additions.store;

import com.google.gson.JsonObject;
import com.mrgregles.bsp_velocity_additions.config.Messages;
import com.mrgregles.bsp_velocity_additions.store.StoreTesting.Rig;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.util.List;

import static com.mrgregles.bsp_velocity_additions.store.StoreTesting.order;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T27, D43: {@code /storeadmin revoke <order>}, taking an order back by hand after a refund or chargeback. */
class StoreRevokeTest {

    private static JsonObject vipOrder(String status) {
        JsonObject o = order("ord_1", "Q3ZUA32R", "a@b.c", "Steve", status, null, 1, "BSP-STARTER-KIT", 1);
        o.getAsJsonArray("offers").get(0).getAsJsonObject().addProperty("slug", "BSP-RANK-VIP");
        return o;
    }

    @Test
    void aDeliveredRankIsTakenBackAPendingItemCancelledAndADeliveredItemReported() throws Exception {
        Rig rig = new Rig();
        rig.event("weve_1", Fourthwall.ORDER_PLACED, false, vipOrder("DELIVERED"));
        rig.exec("UPDATE shop_rewards SET status = 'DELIVERED' WHERE reward_key = 'BSP-RANK-VIP'");

        StoreService.RevokeResult r = rig.service.staffRevoke("q3zua32r");

        assertEquals(StoreService.RevokeOutcome.DONE, r.outcome());
        assertEquals(1, r.cancelled().cancelled());
        assertEquals(1, r.cancelled().revoked());
        assertEquals("CANCELLED", rig.one("SELECT state FROM shop_orders").get("state"));
        assertEquals("ORDER:ord_1:BSP-RANK-VIP:0:0:REVOKE", rig.one("SELECT dedupe_key FROM shop_rewards WHERE action = 'REVOKE'")
                .get("dedupe_key"));
        assertEquals("CANCELLED", rig.one("SELECT status FROM shop_rewards WHERE reward_key = 'BSP-STARTER-KIT'").get("status"));
    }

    @Test
    void runningItAgainWritesNothingMore() throws Exception {
        Rig rig = new Rig();
        rig.event("weve_1", Fourthwall.ORDER_PLACED, false, vipOrder("DELIVERED"));
        rig.exec("UPDATE shop_rewards SET status = 'DELIVERED'");
        rig.service.staffRevoke("Q3ZUA32R");
        long rows = rig.count("SELECT COUNT(*) FROM shop_rewards");

        StoreService.RevokeResult again = rig.service.staffRevoke("ord_1");

        assertEquals(0, again.cancelled().revoked());
        assertEquals(rows, rig.count("SELECT COUNT(*) FROM shop_rewards"));
        assertEquals(1, rig.staff.size(), "the starter kit, which has no revoke commands, is reported once");
    }

    @Test
    void anUnknownOrderOrOneWithNoContentsIsReported() throws Exception {
        Rig rig = new Rig();
        assertEquals(StoreService.RevokeOutcome.NOT_FOUND, rig.service.staffRevoke("NOPE1234").outcome());

        rig.event("weve_1", Fourthwall.ORDER_PLACED, false, vipOrder("DELIVERED"));
        rig.exec("DELETE FROM shop_events");
        assertEquals(StoreService.RevokeOutcome.UNAVAILABLE, rig.service.staffRevoke("Q3ZUA32R").outcome());
    }

    @Test
    void theConsoleSeesWhatWasTakenBack() throws Exception {
        Rig rig = new Rig();
        rig.event("weve_1", Fourthwall.ORDER_PLACED, false, vipOrder("DELIVERED"));
        rig.exec("UPDATE shop_rewards SET status = 'DELIVERED'");
        StoreAdminCommand admin = new StoreAdminCommand(mock(ProxyServer.class), rig.service, rig.profiles,
                mock(StoreAdminCommand.Controls.class), Runnable::run, Messages.defaults(), () -> true, mock(Logger.class));
        ConsoleCommandSource console = mock(ConsoleCommandSource.class);
        SimpleCommand.Invocation inv = mock(SimpleCommand.Invocation.class);
        when(inv.source()).thenReturn(console);
        when(inv.arguments()).thenReturn(new String[] {"revoke", "Q3ZUA32R"});

        admin.execute(inv);

        ArgumentCaptor<Component> c = ArgumentCaptor.forClass(Component.class);
        verify(console, atLeastOnce()).sendMessage(c.capture());
        List<Component> all = c.getAllValues();
        String line = PlainTextComponentSerializer.plainText().serialize(all.get(all.size() - 1));
        assertEquals("Order Q3ZUA32R taken back: 0 waiting reward(s) cancelled, 1 take-back(s) written; 1 given that cannot be "
                + "taken back automatically (see the warning).", line);
    }
}
