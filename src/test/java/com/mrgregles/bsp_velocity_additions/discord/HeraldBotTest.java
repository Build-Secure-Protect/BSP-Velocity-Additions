package com.mrgregles.bsp_velocity_additions.discord;

import com.mrgregles.bsp_velocity_additions.config.Messages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.invocation.Invocation;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

class HeraldBotTest {

    static final long ALICE = 712345678901234567L;
    static final long BOB = 798765432109876543L;
    static final long CHANNEL = 1100000000000000001L;
    static final UUID MRGREGLES = UUID.fromString("0f3c1a2b-3c4d-4e5f-8a9b-0c1d2e3f4a5b");
    static final UUID ALT = UUID.fromString("1a2b3c4d-5e6f-4a1b-9c2d-3e4f5a6b7c8d");
    static final String PREFIX = "bsp_core:discord";

    final ManualClock clock = new ManualClock(1_761_419_040_000L);
    final FakeRedis redis = new FakeRedis();
    final FakeDiscord discord = new FakeDiscord();
    final Logger logger = mock(Logger.class);
    LinkStore links;
    HeraldBot bot;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        links = new LinkStore(dir.resolve(LinkStore.FILE_NAME), Runnable::run, logger);
        bot = bot(CHANNEL, 5);
    }

    HeraldBot bot(long channel, int wrongCodeLimit) {
        return new HeraldBot(links, redis, discord, new HeraldMessages(Messages.defaults()), clock, clock, logger,
                PREFIX, channel, wrongCodeLimit);
    }

    // ------------------------------------------------------------------ helpers

    void code(String code, UUID player, String name) {
        redis.values.put(PREFIX + ":code:" + code, "{\"v\":1,\"player\":\"" + player + "\",\"name\":\"" + name
                + "\",\"server\":\"BSP-Spawn\",\"expiresAt\":" + (clock.getAsLong() + 600_000L) + "}");
    }

    OutgoingMessage link(long user, String code) {
        OutgoingMessage reply = bot.link(user, code);
        clock.runDue();
        return reply;
    }

    void linked(long user, UUID player, String name) {
        links.link(player, name, user, clock.getAsLong());
    }

    void alert(String json) {
        bot.onRedisMessage(json);
        clock.runDue();
    }

    String warning(UUID player, int secondsLeft) {
        return "{\"v\":1,\"type\":\"warning\",\"server\":\"Frostwarf\",\"sentAt\":" + clock.getAsLong() + ",\"player\":\"" + player
                + "\",\"world\":\"minecraft:overworld\",\"x\":120,\"y\":64,\"z\":-340,\"thief\":\"Steve\",\"secondsLeft\":" + secondsLeft + "}";
    }

    String saved(UUID player, String reason) {
        return "{\"v\":1,\"type\":\"saved\",\"server\":\"Frostwarf\",\"sentAt\":" + clock.getAsLong() + ",\"player\":\"" + player
                + "\",\"world\":\"minecraft:overworld\",\"x\":120,\"y\":64,\"z\":-340,\"thief\":\"Steve\",\"reason\":\"" + reason + "\"}";
    }

    String channelLine() {
        return "{\"v\":1,\"type\":\"channel\",\"server\":\"Frostwarf\",\"sentAt\":" + clock.getAsLong() + "}";
    }

    long warnings() {
        return mockingDetails(logger).getInvocations().stream().filter(i -> i.getMethod().getName().equals("warn")).count();
    }

    /** Decision 11: no Discord id in any log line, Redis key or Redis value. */
    void assertNoDiscordIdLeaked() {
        for (Invocation i : mockingDetails(logger).getInvocations()) {
            String args = Arrays.deepToString(i.getArguments());
            for (long id : List.of(ALICE, BOB, CHANNEL)) {
                assertFalse(args.contains(Long.toString(id)), "a Discord id reached the log: " + args);
            }
        }
        for (String command : redis.commands) {
            assertFalse(command.contains(Long.toString(ALICE)) || command.contains(Long.toString(BOB)), command);
        }
        for (var e : redis.values.entrySet()) {
            assertFalse(e.getValue().contains(Long.toString(ALICE)) || e.getValue().contains(Long.toString(BOB)), e.toString());
        }
    }

    // ------------------------------------------------------------------ alerts

    @Test
    void aWarningGoesOnlyToTheOwnersLinkedDiscordInLookA() {
        linked(ALICE, MRGREGLES, "Mrgregles");

        alert(warning(MRGREGLES, 45));

        assertEquals(1, discord.sends.size());
        FakeDiscord.Send dm = discord.dms().get(0);
        assertEquals(ALICE, dm.to());
        assertEquals("Your totem on Frostwarf is being stolen!", dm.message().text());
        OutgoingMessage.Card card = dm.message().card();
        assertEquals(HeraldMessages.RED, card.colour());
        assertEquals("⚠ Totem under attack", card.title());
        assertEquals(List.of("Server", "Where", "Thief", "Time left"), card.fields().stream().map(OutgoingMessage.Field::name).toList());
        assertEquals(List.of("Frostwarf", "120 64 -340", "Steve", "45 seconds"), card.fields().stream().map(OutgoingMessage.Field::value).toList());
        assertTrue(card.fields().stream().allMatch(OutgoingMessage.Field::inline));
        assertEquals("Log in and stand by your totem to stop the steal.", card.footer());
        assertNoDiscordIdLeaked();
    }

    @Test
    void aPlayerWithNoLinkGetsNothingAndNothingFallsBackToPublic() {
        alert(warning(MRGREGLES, 45));
        alert(saved(MRGREGLES, "left"));

        assertTrue(discord.sends.isEmpty());
    }

    @Test
    void thePublicLineNamesTheServerAndNothingElse() {
        linked(ALICE, MRGREGLES, "Mrgregles");

        alert(channelLine());

        assertEquals(1, discord.sends.size());
        FakeDiscord.Send post = discord.posts().get(0);
        assertEquals(CHANNEL, post.to());
        assertEquals("", post.message().text());
        assertEquals(HeraldMessages.GREY, post.message().card().colour());
        assertEquals("A totem is being stolen on **Frostwarf**", post.message().card().description());
        assertEquals("", post.message().card().title());
        assertTrue(post.message().card().fields().isEmpty());
        assertEquals("", post.message().card().footer());
    }

    @Test
    void thePublicLineIgnoresExtraFieldsEvenIfTheGameSentThem() {
        alert("{\"v\":1,\"type\":\"channel\",\"server\":\"Frostwarf\",\"sentAt\":1,\"player\":\"" + MRGREGLES
                + "\",\"thief\":\"Steve\",\"x\":120}");

        String all = discord.posts().get(0).message().toString();
        assertFalse(all.contains("Steve"));
        assertFalse(all.contains("120"));
        assertFalse(all.contains(MRGREGLES.toString()));
    }

    @Test
    void withoutAnAlertChannelThePublicLineIsSkippedAndLoggedOnce() {
        bot = bot(0L, 5);

        alert(channelLine());
        alert(channelLine());

        assertTrue(discord.sends.isEmpty());
        assertEquals(1, warnings());
    }

    @Test
    void eachSavedReasonHasItsOwnLineAndStolenIsRed() {
        linked(ALICE, MRGREGLES, "Mrgregles");

        for (String reason : List.of("left", "interrupted", "totem_gone", "cancelled", "ended", "something_new")) {
            alert(saved(MRGREGLES, reason));
        }
        alert("{\"v\":1,\"type\":\"stolen\",\"server\":\"Frostwarf\",\"sentAt\":1,\"player\":\"" + MRGREGLES
                + "\",\"world\":\"minecraft:overworld\",\"x\":120,\"y\":64,\"z\":-340,\"thief\":\"Steve\"}");

        List<String> descriptions = discord.dms().stream().map(s -> s.message().card().description()).toList();
        assertEquals(List.of("Steve left the totem for too long.", "Steve left the game or could not go on.",
                "The totem was taken down, so the steal stopped.", "An operator stopped the steal.", "The steal ended.",
                "The steal ended.", "Steve now owns your totem at 120 64 -340 on Frostwarf."), descriptions);
        assertEquals("Your totem on Frostwarf is safe.", discord.dms().get(0).message().text());
        assertEquals(HeraldMessages.GREEN, discord.dms().get(0).message().card().colour());
        assertEquals("✔ Steal failed", discord.dms().get(0).message().card().title());
        OutgoingMessage stolen = discord.dms().get(6).message();
        assertEquals("Your totem on Frostwarf was stolen.", stolen.text());
        assertEquals("✖ Totem stolen", stolen.card().title());
        assertEquals(HeraldMessages.RED, stolen.card().colour());
    }

    @Test
    void unknownVersionsTypesAndBrokenJsonAreIgnored() {
        linked(ALICE, MRGREGLES, "Mrgregles");

        alert(warning(MRGREGLES, 45).replace("\"v\":1", "\"v\":2"));
        alert(warning(MRGREGLES, 45).replace("\"warning\"", "\"party\""));
        alert("{not json");
        alert("[1,2]");
        alert("{\"v\":1,\"type\":\"warning\"}");

        assertTrue(discord.sends.isEmpty());
    }

    @Test
    void namesAndServersAreEscapedSoTheyCannotFormatOrMention() {
        linked(ALICE, MRGREGLES, "Mrgregles");

        alert(warning(MRGREGLES, 45).replace("\"Steve\"", "\"_Steve_\"").replace("\"Frostwarf\"", "\"<@123> **x**\""));

        OutgoingMessage m = discord.dms().get(0).message();
        assertEquals("Your totem on \\<\\@123\\> \\*\\*x\\*\\* is being stolen!", m.text());
        assertEquals("\\_Steve\\_", m.card().fields().get(2).value());
    }

    // ------------------------------------------------------------------ limits (decision 17)

    @Test
    void a429IsWaitedOutThenSent() {
        linked(ALICE, MRGREGLES, "Mrgregles");
        discord.then(new DiscordPort.Result.RateLimited(1_500L));

        alert(warning(MRGREGLES, 45));
        assertEquals(1, discord.sends.size());
        clock.advance(1_499L);
        assertEquals(1, discord.sends.size());
        clock.advance(1L);

        assertEquals(2, discord.sends.size());
        assertEquals(0, clock.pending());
        assertEquals(0, warnings());
    }

    @Test
    void a429LongerThanTheStealDropsTheWarningAndLogsOnce() {
        linked(ALICE, MRGREGLES, "Mrgregles");
        discord.then(new DiscordPort.Result.RateLimited(50_000L), new DiscordPort.Result.RateLimited(50_000L));

        alert(warning(MRGREGLES, 45));
        alert(warning(MRGREGLES, 45));

        assertEquals(2, discord.sends.size());
        assertEquals(0, clock.pending());
        assertEquals(1, warnings());
    }

    @Test
    void failuresAreTriedThreeTimesAtMostAndLoggedOnceUntilASendWorks() {
        linked(ALICE, MRGREGLES, "Mrgregles");
        DiscordPort.Result down = new DiscordPort.Result.Failed("SERVER_ERROR");
        discord.then(down, down, down, down, down, down);

        alert(saved(MRGREGLES, "left"));
        clock.advance(60_000L);
        assertEquals(HeraldBot.MAX_TRIES, discord.sends.size());
        alert(saved(MRGREGLES, "left"));
        clock.advance(60_000L);

        assertEquals(2 * HeraldBot.MAX_TRIES, discord.sends.size());
        assertEquals(0, clock.pending());
        assertEquals(1, warnings());

        alert(saved(MRGREGLES, "left"));
        discord.then(down, down, down);
        alert(saved(MRGREGLES, "left"));
        clock.advance(60_000L);
        assertEquals(2, warnings());
    }

    @Test
    void aWarningWhoseStealIsAlreadyOverIsNotSent() {
        linked(ALICE, MRGREGLES, "Mrgregles");

        alert(warning(MRGREGLES, 0));

        assertTrue(discord.sends.isEmpty());
    }

    @Test
    void aRetryThatWouldArriveAfterTheStealIsDropped() {
        linked(ALICE, MRGREGLES, "Mrgregles");
        DiscordPort.Result down = new DiscordPort.Result.Failed("SERVER_ERROR");
        discord.then(down, down);

        alert(warning(MRGREGLES, 3));
        clock.advance(10_000L);

        assertEquals(2, discord.sends.size(), "first try, a retry after 2 s, and no third try past the 3 s steal");
        assertEquals(0, clock.pending());
    }

    @Test
    void aClosedDmSetsTheUndeliveredFlagForAWeekAndNothingPublic() {
        linked(ALICE, MRGREGLES, "Mrgregles");
        discord.then(new DiscordPort.Result.Undeliverable());

        alert(warning(MRGREGLES, 45));

        assertEquals(1, discord.sends.size());
        assertTrue(discord.posts().isEmpty());
        assertEquals("1", redis.values.get(PREFIX + ":undelivered:" + MRGREGLES));
        assertEquals(HeraldBot.UNDELIVERED_SECONDS, redis.expirySeconds.get(PREFIX + ":undelivered:" + MRGREGLES));
        assertEquals(604_800L, HeraldBot.UNDELIVERED_SECONDS);
        assertTrue(links.get(MRGREGLES).isPresent(), "the link stays (decision 15)");
        assertEquals(0, clock.pending(), "no retry for a closed DM");
        assertNoDiscordIdLeaked();
    }

    // ------------------------------------------------------------------ /link

    @Test
    void aGoodCodeLinksTheUserWhoRanTheCommandAndSetsTheFlag() {
        code("K7QX2M", MRGREGLES, "Mrgregles");

        OutgoingMessage reply = link(ALICE, " k7qx2m ");

        assertEquals("Linked", reply.card().title());
        assertEquals("Steal warnings for **Mrgregles** will come to your DMs. A test message is on its way.", reply.card().description());
        assertEquals(HeraldMessages.GREEN, reply.card().colour());
        assertEquals(ALICE, links.get(MRGREGLES).orElseThrow().discordUser());
        assertEquals("1", redis.values.get(PREFIX + ":linked:" + MRGREGLES));
        assertTrue(redis.commands.contains("GETDEL " + PREFIX + ":code:K7QX2M"));
        assertFalse(redis.values.containsKey(PREFIX + ":code:K7QX2M"));
        assertEquals(1, discord.dms().size());
        assertEquals(ALICE, discord.dms().get(0).to());
        assertEquals("Herald test: steal warnings for Mrgregles will arrive here.", discord.dms().get(0).message().text());
        assertNoDiscordIdLeaked();
    }

    @Test
    void anUnknownCodeIsRefused() {
        OutgoingMessage reply = link(ALICE, "K7QX2M");

        assertEquals("Code not accepted", reply.card().title());
        assertTrue(links.all().isEmpty());
        assertFalse(redis.values.containsKey(PREFIX + ":linked:" + MRGREGLES));
        assertTrue(discord.sends.isEmpty());
    }

    @Test
    void aCodeWorksOnce() {
        code("K7QX2M", MRGREGLES, "Mrgregles");

        link(ALICE, "K7QX2M");
        OutgoingMessage second = link(BOB, "K7QX2M");

        assertEquals("Code not accepted", second.card().title());
        assertEquals(ALICE, links.get(MRGREGLES).orElseThrow().discordUser());
    }

    @Test
    void anExpiredCodeIsRefusedEvenIfRedisStillHasIt() {
        redis.values.put(PREFIX + ":code:K7QX2M", "{\"v\":1,\"player\":\"" + MRGREGLES + "\",\"name\":\"Mrgregles\",\"expiresAt\":"
                + (clock.getAsLong() - 1) + "}");

        assertEquals("Code not accepted", link(ALICE, "K7QX2M").card().title());
        assertTrue(links.all().isEmpty());
    }

    @Test
    void aCodeOutsideTheAlphabetIsRefusedWithoutAskingRedis() {
        bot = bot(CHANNEL, 0);
        for (String typed : List.of("", "K7QX2", "K7QX2MM", "K7QX20", "K7QXIM", "K7*X2M", "K7QX2M:linked")) {
            assertEquals("Code not accepted", link(ALICE, typed).card().title(), typed);
        }
        assertTrue(redis.commands.isEmpty());
    }

    @Test
    void tooManyWrongCodesMakeLinkWaitTenMinutes() {
        for (int i = 0; i < 5; i++) {
            link(ALICE, "AAAAA" + "BCDEF".charAt(i));
        }
        code("K7QX2M", MRGREGLES, "Mrgregles");

        assertEquals("Too many wrong codes", link(ALICE, "K7QX2M").card().title());
        assertTrue(redis.values.containsKey(PREFIX + ":code:K7QX2M"), "the code is not used up while waiting");
        assertEquals("Linked", link(BOB, "K7QX2M").card().title(), "the limit is per Discord user");

        clock.advance(HeraldBot.WRONG_CODE_WINDOW_MS);
        code("K7QX2N", ALT, "Mrgregles_Alt");
        assertEquals("Linked", link(ALICE, "K7QX2N").card().title());
    }

    @Test
    void theWrongCodeLimitCanBeSwitchedOff() {
        bot = bot(CHANNEL, 0);
        for (int i = 0; i < 20; i++) {
            link(ALICE, "AAAAAA");
        }
        code("K7QX2M", MRGREGLES, "Mrgregles");

        assertEquals("Linked", link(ALICE, "K7QX2M").card().title());
    }

    @Test
    void redisDownMeansTryAgainSoonAndTheCodeIsKept() {
        code("K7QX2M", MRGREGLES, "Mrgregles");
        redis.down = true;

        OutgoingMessage reply = link(ALICE, "K7QX2M");

        assertEquals("Try again soon", reply.card().title());
        assertTrue(redis.values.containsKey(PREFIX + ":code:K7QX2M"));
        assertTrue(links.all().isEmpty());
        redis.down = false;
        assertEquals("Linked", link(ALICE, "K7QX2M").card().title(), "a Redis outage does not count as a wrong code");
    }

    @Test
    void aNewLinkReplacesTheOldOneAndTellsTheOldAccount() {
        code("K7QX2M", MRGREGLES, "Mrgregles");
        link(ALICE, "K7QX2M");
        discord.sends.clear();
        code("K7QX2N", MRGREGLES, "Mrgregles");
        discord.then(new DiscordPort.Result.Undeliverable());

        link(BOB, "K7QX2N");

        assertEquals(BOB, links.get(MRGREGLES).orElseThrow().discordUser());
        assertEquals(List.of(ALICE, BOB), discord.dms().stream().map(FakeDiscord.Send::to).toList());
        assertEquals("Steal warnings for Mrgregles now go to another Discord account.", discord.dms().get(0).message().text());
        assertFalse(redis.values.containsKey(PREFIX + ":undelivered:" + MRGREGLES),
                "the old account's closed DMs say nothing about the player's new link");
        assertNoDiscordIdLeaked();
    }

    @Test
    void oneDiscordUserMayHoldSeveralMinecraftAccounts() {
        code("K7QX2M", MRGREGLES, "Mrgregles");
        code("K7QX2N", ALT, "Mrgregles_Alt");

        link(ALICE, "K7QX2M");
        link(ALICE, "K7QX2N");
        alert(warning(ALT, 45));

        assertEquals(2, links.linksOf(ALICE).size());
        assertEquals(ALICE, discord.dms().get(2).to());
        assertEquals("1", redis.values.get(PREFIX + ":linked:" + MRGREGLES));
        assertEquals("1", redis.values.get(PREFIX + ":linked:" + ALT));
    }

    // ------------------------------------------------------------------ /unlink, /linkstatus, unlink from the game

    @Test
    void linkStatusListsOnlyTheCallersOwnNames() {
        linked(ALICE, MRGREGLES, "Mrgregles");
        linked(ALICE, ALT, "Mrgregles_Alt");
        linked(BOB, UUID.randomUUID(), "Someone_Else");

        OutgoingMessage reply = bot.status(ALICE);

        assertEquals("Linked accounts", reply.card().title());
        assertEquals("• Mrgregles\n• Mrgregles\\_Alt", reply.card().description());
        assertEquals("/unlink and a name to remove one", reply.card().footer());
        assertFalse(reply.toString().contains("Someone"));
        assertEquals("Not linked", bot.status(123L).card().title());
    }

    @Test
    void unlinkWithoutANameUnlinksNothingAndListsTheAccounts() {
        linked(ALICE, MRGREGLES, "Mrgregles");
        linked(ALICE, ALT, "Mrgregles_Alt");
        redis.values.put(PREFIX + ":linked:" + MRGREGLES, "1");

        for (String typed : new String[] {"", "   ", null}) {
            OutgoingMessage reply = bot.unlink(ALICE, typed);
            assertEquals("Linked accounts", reply.card().title());
            assertEquals("/unlink and a name to remove one", reply.card().footer());
        }

        assertEquals(2, links.linksOf(ALICE).size());
        assertEquals("1", redis.values.get(PREFIX + ":linked:" + MRGREGLES));
        assertTrue(redis.commands.isEmpty());
    }

    @Test
    void unlinkWithANameEndsOnlyThatOneAndClearsItsFlag() {
        linked(ALICE, MRGREGLES, "Mrgregles");
        linked(ALICE, ALT, "Mrgregles_Alt");
        redis.values.put(PREFIX + ":linked:" + MRGREGLES, "1");
        redis.values.put(PREFIX + ":linked:" + ALT, "1");

        OutgoingMessage reply = bot.unlink(ALICE, " mrgregles_alt ");

        assertEquals("Unlinked", reply.card().title());
        assertEquals("Steal warnings for **Mrgregles\\_Alt** will no longer come here.", reply.card().description());
        assertEquals(List.of("Mrgregles"), links.linksOf(ALICE).stream().map(LinkStore.Link::name).toList());
        assertEquals("1", redis.values.get(PREFIX + ":linked:" + MRGREGLES));
        assertFalse(redis.values.containsKey(PREFIX + ":linked:" + ALT));
        assertEquals("Not found", bot.unlink(ALICE, "Notch").card().title());
        assertEquals("Not linked", bot.unlink(BOB, "Mrgregles").card().title());
    }

    @Test
    void unlinkCannotTouchSomeoneElsesAccount() {
        linked(BOB, MRGREGLES, "Mrgregles");

        assertEquals("Not linked", bot.unlink(ALICE, "Mrgregles").card().title());
        assertTrue(links.get(MRGREGLES).isPresent());
    }

    @Test
    void anUnlinkFromTheGameDeletesTheLinkWithoutTouchingRedis() {
        linked(ALICE, MRGREGLES, "Mrgregles");

        alert("{\"v\":1,\"type\":\"unlink\",\"player\":\"" + MRGREGLES + "\",\"sentAt\":1}");
        alert(warning(MRGREGLES, 45));

        assertTrue(links.get(MRGREGLES).isEmpty());
        assertTrue(redis.commands.isEmpty());
        assertTrue(discord.sends.isEmpty());
    }

    @Test
    void onStartEveryLinkIsMarkedAgainAndUnlinksMadeWhileRedisWasDownAreFinished() {
        linked(ALICE, MRGREGLES, "Mrgregles");
        linked(ALICE, ALT, "Mrgregles_Alt");
        redis.values.put(PREFIX + ":linked:" + ALT, "1");
        redis.down = true;
        bot.unlink(ALICE, "Mrgregles_Alt");
        redis.down = false;
        assertEquals("1", redis.values.get(PREFIX + ":linked:" + ALT), "Redis was down, so the flag is still there");

        bot.onRedisReady();
        clock.runDue();

        assertEquals("1", redis.values.get(PREFIX + ":linked:" + MRGREGLES));
        assertNull(redis.values.get(PREFIX + ":linked:" + ALT));
        assertNoDiscordIdLeaked();
    }

    @Test
    void noDiscordIdIsEverLoggedOrWrittenToRedisAcrossAWholeSession() {
        code("K7QX2M", MRGREGLES, "Mrgregles");
        link(ALICE, "K7QX2M");
        code("K7QX2N", MRGREGLES, "Mrgregles");
        link(BOB, "K7QX2N");
        discord.then(new DiscordPort.Result.Undeliverable(), new DiscordPort.Result.Failed("X"),
                new DiscordPort.Result.Failed("X"), new DiscordPort.Result.Failed("X"));
        alert(warning(MRGREGLES, 45));
        alert(saved(MRGREGLES, "left"));
        clock.advance(60_000L);
        redis.down = true;
        bot.unlink(BOB, "Mrgregles");
        link(ALICE, "AAAAAA");
        redis.down = false;
        bot.onRedisReady();
        clock.runDue();

        assertNoDiscordIdLeaked();
    }
}
