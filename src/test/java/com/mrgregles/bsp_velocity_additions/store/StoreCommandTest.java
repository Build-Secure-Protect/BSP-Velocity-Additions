package com.mrgregles.bsp_velocity_additions.store;

import com.mrgregles.bsp_velocity_additions.config.Messages;
import com.mrgregles.bsp_velocity_additions.store.StoreTesting.Rig;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;

import static com.mrgregles.bsp_velocity_additions.store.StoreTesting.order;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SH-R1 5.7: what players and staff see. Work runs on the calling thread here (the worker in the proxy). */
class StoreCommandTest {

    private final Rig rig = new Rig();
    private final Messages messages = Messages.defaults();
    private final FailedTries tries = new FailedTries(5, StoreTesting.CLOCK);
    private final StoreCommand store = new StoreCommand(rig.service, Runnable::run, messages, () -> rig.config, tries, () -> true,
            mock(Logger.class));

    private static Player steve() {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(StoreTesting.STEVE);
        when(p.getUsername()).thenReturn("Steve");
        return p;
    }

    private static SimpleCommand.Invocation run(CommandSource source, String... args) {
        SimpleCommand.Invocation inv = mock(SimpleCommand.Invocation.class);
        when(inv.source()).thenReturn(source);
        when(inv.arguments()).thenReturn(args);
        return inv;
    }

    private static String last(CommandSource source) {
        ArgumentCaptor<Component> c = ArgumentCaptor.forClass(Component.class);
        verify(source, atLeastOnce()).sendMessage(c.capture());
        List<Component> all = c.getAllValues();
        return PlainTextComponentSerializer.plainText().serialize(all.get(all.size() - 1));
    }

    @Test
    void aWrongEmailAndAWrongOrderNumberGetTheSameAnswerAndTheSixthTryIsRefused() throws Exception {
        rig.event("weve_1", Fourthwall.ORDER_PLACED, false, order("ord_1", "AAA1", "s@x.com", "", "CONFIRMED", "BSP-RANK-VIP", 1));
        Player p = steve();

        store.execute(run(p, "claim", "AAA1", "wrong@x.com"));
        String wrongEmail = last(p);
        clearInvocations(p);
        store.execute(run(p, "claim", "ZZZ9", "s@x.com"));
        assertEquals(wrongEmail, last(p));
        assertEquals("No order matches that order number and email.", wrongEmail);
        for (int i = 0; i < 3; i++) {
            store.execute(run(p, "link", "nobody" + i + "@x.com"));
        }
        clearInvocations(p);

        store.execute(run(p, "claim", "AAA1", "s@x.com"));
        assertTrue(last(p).startsWith("Too many failed tries"), "even with the right pair");
        assertEquals(0, rig.count("SELECT COUNT(*) FROM shop_rewards"));
    }

    @Test
    void triesWhileLockedOutAreNotCountedSoTheLockEndsTenMinutesAfterTheFirstFailure() throws Exception {
        java.time.Instant[] now = {java.time.Instant.parse("2026-11-20T01:00:00Z")};
        java.time.Clock clock = new java.time.Clock() {
            public java.time.ZoneId getZone() {
                return java.time.ZoneOffset.UTC;
            }

            public java.time.Clock withZone(java.time.ZoneId z) {
                return this;
            }

            public java.time.Instant instant() {
                return now[0];
            }
        };
        StoreCommand limited = new StoreCommand(rig.service, Runnable::run, messages, () -> rig.config, new FailedTries(5, clock),
                () -> true, mock(Logger.class));
        Player p = steve();
        for (int i = 0; i < 5; i++) { // five failures, one a minute: 01:00 to 01:04
            limited.execute(run(p, "claim", "ZZZ" + i, "wrong@x.com"));
            now[0] = now[0].plusSeconds(60);
        }
        for (int i = 0; i < 20; i++) { // hammering while locked, 01:05 to 01:09:45
            limited.execute(run(p, "claim", "ZZZZ", "wrong@x.com"));
            assertTrue(last(p).startsWith("Too many failed tries"));
            now[0] = now[0].plusSeconds(15);
        }

        now[0] = java.time.Instant.parse("2026-11-20T01:10:01Z"); // 10 min after the first failure, not after the last try
        limited.execute(run(p, "claim", "ZZZZ", "wrong@x.com"));
        assertEquals("No order matches that order number and email.", last(p));
    }

    @Test
    void theRightPairClaimsOnceAndShowsTheEmailMasked() throws Exception {
        rig.event("weve_1", Fourthwall.ORDER_PLACED, false, order("ord_1", "AAA1", "steve@x.com", "", "CONFIRMED", "BSP-RANK-VIP", 1));
        Player p = steve();

        store.execute(run(p, "claim", "AAA1", "steve@x.com"));
        assertEquals("Order AAA1 is yours. Your rewards are on their way.", last(p));
        store.execute(run(p, "claim", "AAA1", "steve@x.com"));
        assertEquals("Order AAA1 has already been claimed.", last(p));

        store.execute(run(p, "link", "steve@x.com"));
        assertTrue(last(p).contains("s***@x.com"), last(p));
        assertFalse(last(p).contains("steve@x.com"));

        store.execute(run(p, "status"));
        assertTrue(last(p).contains("VIP rank"), last(p));
    }

    @Test
    void storeAloneShowsTheHelpWithTheShopLinkAndTheConsoleCannotClaim() {
        Player p = steve();
        store.execute(run(p));
        assertTrue(last(p).contains("open the shop"));

        ConsoleCommandSource console = mock(ConsoleCommandSource.class);
        store.execute(run(console, "claim", "A", "b@c"));
        assertEquals("Only players can use this.", last(console));
    }

    @Test
    void whileTheDatabaseIsNotReadyPlayersAreToldToTryLater() {
        StoreCommand down = new StoreCommand(rig.service, Runnable::run, messages, () -> rig.config, tries, () -> false, mock(Logger.class));
        Player p = steve();

        down.execute(run(p, "status"));

        assertTrue(last(p).startsWith("The store is not available"));
    }

    @Test
    void staffCommandsNeedThePermissionAndShowEmailsMasked() throws Exception {
        rig.event("weve_1", Fourthwall.ORDER_PLACED, false, order("ord_1", "AAA1", "gary@domain.com", "", "CONFIRMED", "BSP-RANK-VIP", 1));
        ProxyServer proxy = mock(ProxyServer.class);
        when(proxy.getPlayer(any(String.class))).thenReturn(Optional.empty());
        StoreAdminCommand admin = new StoreAdminCommand(proxy, rig.service, rig.profiles, mock(StoreAdminCommand.Controls.class),
                Runnable::run, messages, () -> true, mock(Logger.class));
        Player player = steve();
        assertFalse(admin.hasPermission(run(player, "lookup", "x")));
        when(player.hasPermission(StoreAdminCommand.PERMISSION)).thenReturn(true);
        assertTrue(admin.hasPermission(run(player, "lookup", "x")));
        ConsoleCommandSource console = mock(ConsoleCommandSource.class);
        assertTrue(admin.hasPermission(run(console)));

        admin.execute(run(console, "lookup", "AAA1"));
        String line = last(console);
        assertTrue(line.contains("g***@domain.com"), line);
        assertFalse(line.contains("gary@"));

        admin.execute(run(console, "assign", "AAA1", "Alex"));
        assertEquals("Order AAA1 assigned to Alex; its rewards are written.", last(console));
        admin.execute(run(console, "grant", "Steve", "BSP-RANK-VIP"));
        assertEquals("Granted VIP rank to Steve.", last(console));
        admin.execute(run(console, "grant", "NoSuchPlayer", "BSP-RANK-VIP"));
        assertTrue(last(console).startsWith("No Minecraft Java account"));
        assertEquals(1, rig.count("SELECT COUNT(*) FROM shop_rewards WHERE source_type = 'MANUAL' AND source_id = 'console'"));
    }
}
