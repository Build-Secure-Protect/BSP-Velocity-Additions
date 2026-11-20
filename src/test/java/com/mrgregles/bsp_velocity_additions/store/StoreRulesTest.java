package com.mrgregles.bsp_velocity_additions.store;

import com.moandjiezana.toml.Toml;
import com.mrgregles.bsp_velocity_additions.config.Messages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** SH-R1: the schema contract, the config files, command checks, emails, secrets and the failed-try limit. */
class StoreRulesTest {

    // ---------------------------------------------------------------- schema (4.1)

    @Test
    void theBundledSchemaIsAnExactCopyOfBspServerShopsWhenThatRepoIsBesideThisOne() throws Exception {
        Path theirs = Path.of("..", "BSP-Server Shop", "src", "main", "resources", "schema.sql");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(theirs), "BSP-Server Shop is not checked out beside this repo");
        assertEquals(Files.readString(theirs, StandardCharsets.UTF_8), ShopSchema.load());
    }

    @Test
    void anEmptyDatabaseGetsTheSixTablesAndOneVersionRowEvenWhenSetUpTwice() throws Exception {
        try (Connection c = StoreTesting.freshH2().getConnection()) {
            assertEquals(1, ShopSchema.ensure(c));
            assertEquals(1, ShopSchema.ensure(c));
            for (String t : List.of("shop_schema", "shop_events", "shop_orders", "shop_members", "shop_links", "shop_rewards")) {
                try (Statement st = c.createStatement()) {
                    st.executeQuery("SELECT COUNT(*) FROM " + t).close();
                }
            }
            assertEquals(1L, StoreTesting.query(c, "SELECT COUNT(*) AS n FROM shop_schema").get(0).get("n") instanceof Number n
                    ? n.longValue() : -1L);
        }
    }

    @Test
    void aNewerSchemaKeepsTheStoreOffAndNothingIsAltered() throws Exception {
        javax.sql.DataSource ds = StoreTesting.freshH2();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE shop_schema (version INT NOT NULL)");
            st.execute("INSERT INTO shop_schema VALUES (2)");
            assertThrows(ShopSchema.TooNewException.class, () -> ShopSchema.ensure(c));
        }
        ShopDatabase db = ShopDatabase.direct(ds, mock(Logger.class));
        assertFalse(db.connect());
        assertEquals(ShopDatabase.State.INCOMPATIBLE, db.state());
        assertFalse(db.connect(), "never tried again");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            SQLException e = assertThrows(SQLException.class, () -> st.executeQuery("SELECT * FROM shop_rewards"));
            assertNotNull(e);
        }
    }

    @Test
    void anExistingVersionOneDatabaseIsUsedAsItIs() throws Exception {
        try (Connection c = StoreTesting.freshH2().getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE shop_schema (version INT NOT NULL)");
            st.execute("INSERT INTO shop_schema VALUES (1)");
            assertEquals(1, ShopSchema.ensure(c));
            assertThrows(SQLException.class, () -> st.executeQuery("SELECT * FROM shop_rewards"),
                    "with a version row present no table is created");
        }
    }

    // ---------------------------------------------------------------- store.toml

    @Test
    void theBundledStoreTomlGivesTheBriefsDefaultsAndTheStoreOff(@TempDir Path dir) {
        StoreConfig c = StoreConfig.load(dir, mock(Logger.class));

        assertTrue(Files.exists(dir.resolve(StoreConfig.FILE_NAME)));
        assertEquals(StoreConfig.defaults(), c);
        assertFalse(c.enabled());
        assertEquals("0.0.0.0", c.http().bind());
        assertEquals(8765, c.http().port());
        assertEquals("/fourthwall/webhook", c.http().path());
        assertEquals(1_048_576, c.http().maxBodyBytes());
        assertEquals(Set.of("SUSPENDED", "NONE"), c.membership().revokeOn());
        assertEquals(5, c.security().maxFailedClaimsPer10Min());
    }

    @Test
    void storeTomlValuesAreReadAndCheckedAndActiveNeverRevokes() {
        StoreConfig c = StoreConfig.parse(new Toml().read("""
                enabled = true
                [http]
                port = 99999
                path = "hooks"
                [membership]
                revokeOn = ["suspended", "ACTIVE", "CANCELLED"]
                [fourthwall]
                apiBaseUrl = "https://api.fourthwall.com/"
                """));

        assertTrue(c.enabled());
        assertEquals(65535, c.http().port());
        assertEquals("/hooks", c.http().path());
        assertEquals(Set.of("SUSPENDED", "CANCELLED"), c.membership().revokeOn());
        assertEquals("https://api.fourthwall.com", c.fourthwall().apiBaseUrl());
    }

    @Test
    void theStoreTomlTemplateHoldsNoSecret() throws Exception {
        String text = bundled("store.toml");
        assertFalse(text.matches("(?s).*\\bpassword\\s*=.*"));
        assertFalse(text.matches("(?s).*\\bsecret\\s*=.*"));
        for (String v : Secrets.ALL) {
            assertTrue(text.contains(v), v + " is named in the template");
        }
    }

    // ---------------------------------------------------------------- store-rewards.toml

    @Test
    void theBundledRewardsTemplateLoadsWithItsThreeExamples(@TempDir Path dir) throws Exception {
        RewardsConfig r = RewardsConfig.load(dir, Set.of("BSP-Frostwarf")::contains, mock(Logger.class));

        assertEquals(List.of(), r.problems());
        assertEquals(Set.of("BSP-RANK-VIP", "BSP-STARTER-KIT"), r.products().keySet());
        assertEquals(Set.of("TIER_ID_SUPPORTER"), r.memberships().keySet());
        RewardsConfig.Reward kit = r.products().get("BSP-STARTER-KIT");
        assertEquals("BSP-Frostwarf", kit.target());
        assertTrue(kit.requireOnline());
        assertFalse(kit.safeToRetry());
        assertTrue(kit.perQuantity());
        assertFalse(kit.canRevoke());
        assertEquals(List.of("lp user {uuid} parent remove vip"), r.products().get("BSP-RANK-VIP").revoke());
    }

    @Test
    void aRewardWithAMistakeIsLeftOutWithAReasonAndTheRestLoad() {
        RewardsConfig r = RewardsConfig.parse(new Toml().read("""
                [products."GOOD"]
                displayName = "Good"
                grant = ["say {player}"]
                [products."WRONG-CASE"]
                displayName = "Wrong case"
                target = "bsp-frostwarf"
                grant = ["say hi"]
                [products."NO-SERVER"]
                displayName = "No server"
                target = "BSP-Nowhere"
                grant = ["say hi"]
                [products."TYPO"]
                displayName = "Typo"
                grant = ["give {playr} dirt"]
                [products."NEWLINE"]
                displayName = "Newline"
                grant = ["say a\\nop b"]
                [products."SECTION"]
                displayName = "Colour"
                grant = ["say §chi"]
                [products."NOGRANT"]
                displayName = "Nothing"
                [products."NONAME"]
                grant = ["say hi"]
                [memberships."m1"]
                displayName = "Member"
                grant = ["lp user {uuid} parent add m1"]
                """), Set.of("BSP-Frostwarf")::contains);

        assertEquals(Set.of("GOOD"), r.products().keySet());
        assertEquals(Set.of("m1"), r.memberships().keySet());
        assertEquals(7, r.problems().size(), r.problems().toString());
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("bsp-frostwarf") && p.contains("exactly")));
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("{playr}")));
        assertEquals("any", r.products().get("GOOD").target());
        assertFalse(r.products().get("GOOD").requireOnline());
    }

    // ---------------------------------------------------------------- commands (4.2, 5.5)

    @Test
    void placeholdersAreFilledAndTheResultIsCheckedAsBspServerShopWill() {
        Commands.Values v = new Commands.Values("Steve", UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7"), 3, "D3XZFWPP", "SKU-1");

        assertEquals("[\"give Steve dirt 3\",\"lp user 8667ba71-b85a-4004-af54-457a9734eed7 parent add x\",\"say D3XZFWPP SKU-1\"]",
                Commands.toJson(List.of("give {player} dirt {quantity}", "lp user {uuid} parent add x", "say {order} {sku}"), v));
        assertNull(Commands.problem("/say hi"));
        assertEquals("is blank", Commands.problem(" / "));
        assertNotNull(Commands.problem("say a\nb"));
        assertNotNull(Commands.problem("say a\rb"));
        assertNotNull(Commands.problem("say §c"));
        assertNotNull(Commands.problem("say \u007f"));
        assertNotNull(Commands.problem("x".repeat(Commands.MAX_LENGTH + 1)));
        assertThrows(IllegalArgumentException.class, () -> Commands.toJson(List.of(), v));
        assertEquals(List.of("{nope}"), Commands.unknownPlaceholders("say {player} {nope}"));
    }

    @Test
    void onlyValidatedValuesCanBeFilledIn() {
        UUID u = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new Commands.Values("Steve op", u, 1, "A1", "S"));
        assertThrows(IllegalArgumentException.class, () -> new Commands.Values("St", u, 1, "A1", "S"));
        assertThrows(IllegalArgumentException.class, () -> new Commands.Values("Steve", u, 0, "A1", "S"));
        assertThrows(IllegalArgumentException.class, () -> new Commands.Values("Steve", u, 1, "A1; op x", "S"));
        assertThrows(IllegalArgumentException.class, () -> new ProfileLookup.Profile(u, "bad name"));
    }

    @Test
    void aRowsTextColumnsAreHeldToTheContractsLengths() {
        UUID u = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new ShopStore.NewReward("k".repeat(192), "ORDER", "s", "r", "d", "GRANT",
                u, "Steve", "any", false, true, "[\"x\"]", false));
        assertThrows(IllegalArgumentException.class, () -> new ShopStore.NewReward("k", "ORDER", "s".repeat(65), "r", "d", "GRANT",
                u, "Steve", "any", false, true, "[\"x\"]", false));
    }

    // ---------------------------------------------------------------- LuckPerms groups (6.5)

    @Test
    void theGroupsInParentCommandsAreCheckedAgainstLuckPerms() {
        List<String> warnings = LuckPermsGroups.check(StoreTesting.rewards(), Set.of("vip", "supporter")::contains);

        assertEquals(Set.of("vip", "supporter", "legend"), LuckPermsGroups.groupsIn(StoreTesting.rewards()));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("\"legend\""));
    }

    // ---------------------------------------------------------------- emails, secrets, tries

    @Test
    void emailsAreComparedLowerCaseAndShownMasked() {
        assertEquals("gary@domain.com", Emails.normalize("  Gary@Domain.COM "));
        assertEquals("g***@domain.com", Emails.mask("Gary@Domain.com"));
        assertEquals("***", Emails.mask("no-at-sign"));
        assertEquals("***", Emails.mask("@x.com"));
        assertTrue(Emails.looksValid("a@b"));
        assertFalse(Emails.looksValid("a b@c.d"));
        assertFalse(Emails.looksValid("a@b@c"));
    }

    @Test
    void missingSecretsAreNamedAndValuesNeverPrinted() {
        Secrets s = Secrets.read(Map.of(Secrets.WEBHOOK_SECRET, "hush", Secrets.DB_PASSWORD, " ")::get);

        assertEquals(List.of(Secrets.API_USERNAME, Secrets.API_PASSWORD, Secrets.DB_PASSWORD), s.missing());
        assertFalse(s.toString().contains("hush"));
        assertEquals("hush", s.webhookSecret());
        assertTrue(Secrets.read(Map.of(Secrets.WEBHOOK_SECRET, "a", Secrets.API_USERNAME, "b", Secrets.API_PASSWORD, "c",
                Secrets.DB_PASSWORD, "d")::get).missing().isEmpty());
    }

    @Test
    void theSixthFailedTryInTenMinutesIsRefusedUntilTheWindowPasses() {
        Instant[] now = {Instant.parse("2026-11-19T12:00:00Z")};
        java.time.Clock clock = new java.time.Clock() {
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            public java.time.Clock withZone(java.time.ZoneId z) {
                return this;
            }

            public Instant instant() {
                return now[0];
            }
        };
        FailedTries t = new FailedTries(5, clock);
        UUID p = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            assertTrue(t.mayTry(p));
            t.fail(p);
        }
        assertFalse(t.mayTry(p));
        assertTrue(t.mayTry(UUID.randomUUID()), "per player");
        now[0] = now[0].plus(Duration.ofMinutes(10)).plusSeconds(1);
        assertTrue(t.mayTry(p));
    }

    // ---------------------------------------------------------------- messages

    @Test
    void everyStoreMessageTheCodeUsesIsInTheBundledMessagesToml() throws Exception {
        Messages m = Messages.defaults();
        Pattern key = Pattern.compile("P \\+ \"([A-Za-z]+)\"");
        for (String file : List.of("StoreCommand.java", "StoreAdminCommand.java", "StoreFeature.java")) {
            String src = Files.readString(Path.of("src/main/java/com/mrgregles/bsp_velocity_additions/store", file));
            String prefix = file.equals("StoreCommand.java") ? "store." : "storeAdmin.";
            Matcher mm = key.matcher(src);
            while (mm.find()) {
                String k = (src.substring(Math.max(0, mm.start() - 20), mm.start()).contains("StoreCommand.") ? "store."
                        : src.substring(Math.max(0, mm.start() - 25), mm.start()).contains("StoreAdminCommand.") ? "storeAdmin." : prefix)
                        + mm.group(1);
                assertFalse(m.raw(k).equals(k), k + " is missing from messages.toml");
            }
        }
        for (String k : List.of("claimed", "alreadyClaimed", "claimCancelled", "claimFailed", "linked", "alreadyLinked", "linkFailed")) {
            assertFalse(m.raw("store." + k).equals("store." + k), k);
        }
    }

    private static String bundled(String name) throws Exception {
        try (InputStream in = StoreRulesTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
